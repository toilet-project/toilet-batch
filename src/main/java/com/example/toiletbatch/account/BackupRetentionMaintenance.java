package com.example.toiletbatch.account;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Filesystem-only maintenance. Never removes binlogs, R2 records, legacy or unidentified backups. */
public final class BackupRetentionMaintenance {
    public static final Duration RETENTION = Duration.ofDays(14);
    public static final Duration RECENT_RESTORE_VERIFIED_BACKUP = Duration.ofHours(36);
    public enum Status { READY, NO_EXPIRED, HOLD_STALE_SCAN, HOLD_UNKNOWN_FILES, HOLD_LEGACY_METADATA,
        HOLD_IDENTITY, HOLD_NO_RECENT_VERIFIED_BACKUP }
    public record Context(String serverUuid, String databaseEpoch, String restoreVerifiedBackupSha256) {
        public Context {
            if (!UUID.fromString(serverUuid).toString().equals(serverUuid)
                    || !UUID.fromString(databaseEpoch).toString().equals(databaseEpoch)
                    || !restoreVerifiedBackupSha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("INVALID_BACKUP_CONTEXT");
        }
    }
    public record Plan(Status status, String digest, List<BackupEvidenceInventory.Entry> expired) {
        public Plan { expired = List.copyOf(expired); }
    }
    private record Fingerprint(int version, Context context, Status status, List<BackupEvidenceInventory.Entry> files, int unknown) { }

    public Plan plan(BackupEvidenceInventory inventory, Instant now, Context context) {
        var files = inventory.files().stream().sorted(Comparator.comparing(BackupEvidenceInventory.Entry::filename)).toList();
        Status status = Status.READY;
        if (inventory.scannedAt().isAfter(now) || Duration.between(inventory.scannedAt(),now).compareTo(Duration.ofMinutes(5)) > 0)
            status = Status.HOLD_STALE_SCAN;
        else if (inventory.unclassifiedEntries() > 0) status = Status.HOLD_UNKNOWN_FILES;
        else if (files.stream().anyMatch(f -> f.capture() == null)) status = Status.HOLD_LEGACY_METADATA;
        else if (files.stream().anyMatch(f -> !f.capture().serverUuid().equals(context.serverUuid())
                || !f.capture().databaseEpoch().equals(context.databaseEpoch())
                || Instant.parse(f.capture().captureCompletedAt()).isAfter(now))) status = Status.HOLD_IDENTITY;
        else if (files.stream().noneMatch(f -> f.sha256().equals(context.restoreVerifiedBackupSha256())
                && !Instant.parse(f.capture().captureCompletedAt()).isBefore(now.minus(RECENT_RESTORE_VERIFIED_BACKUP))))
            status = Status.HOLD_NO_RECENT_VERIFIED_BACKUP;
        var expired = status != Status.READY ? List.<BackupEvidenceInventory.Entry>of() : files.stream()
                .filter(f -> !f.sha256().equals(context.restoreVerifiedBackupSha256()))
                .filter(f -> !Instant.parse(f.capture().captureCompletedAt()).plus(RETENTION).isAfter(now)).toList();
        if (status == Status.READY && expired.isEmpty()) status = Status.NO_EXPIRED;
        try {
            byte[] bytes = json().writeValueAsBytes(
                    new Fingerprint(1,context,status,files,inventory.unclassifiedEntries()));
            return new Plan(status,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),expired);
        } catch (Exception ignored) { throw new IllegalStateException("BACKUP_PLAN_INVALID"); }
    }

    @FunctionalInterface public interface Deleter { void delete(Path path) throws IOException; }

    /** Caller must hold the backup/restore maintenance exclusion for the ENTIRE operation. */
    public int apply(Path root, Context context, String approvedDigest, Path journal,
                     Clock clock, boolean maintenanceExclusive, Deleter deleter) throws IOException {
        if (!maintenanceExclusive || approvedDigest == null || !approvedDigest.matches("[a-f0-9]{64}"))
            throw new IllegalStateException("BACKUP_APPLY_NOT_AUTHORIZED");
        Path actualRoot = root.toAbsolutePath().normalize();
        if (!actualRoot.equals(actualRoot.toRealPath())) throw new IllegalStateException("BACKUP_ROOT_INVALID");
        Path journalPath = journal.toAbsolutePath().normalize();
        if (journalPath.startsWith(actualRoot) || !journalPath.getParent().equals(journalPath.getParent().toRealPath()))
            throw new IllegalStateException("BACKUP_JOURNAL_INVALID");
        var current = plan(BackupEvidenceInventory.scan(actualRoot,clock.instant()),clock.instant(),context);
        if (current.status() != Status.READY || !current.digest().equals(approvedDigest))
            throw new IllegalStateException("BACKUP_PLAN_CHANGED_OR_BLOCKED");
        // Journal persists BEFORE the first deletion. Never reuse/overwrite a previous operation journal.
        var permissions = Files.getFileStore(journalPath.getParent()).supportsFileAttributeView("posix")
                ? new java.nio.file.attribute.FileAttribute<?>[]{java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))}
                : new java.nio.file.attribute.FileAttribute<?>[0];
        try (var out = java.nio.channels.FileChannel.open(journalPath,
                Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE),permissions)) {
            byte[] header = json().writeValueAsBytes(current);
            write(out,header);write(out,new byte[]{'\n'});out.force(true);
            int deleted = 0;
            try {
                for (var entry : current.expired()) {
                    String name = entry.filename();
                    // All names and all three files were verified in a fresh full-directory scan above.
                    for (String suffix : List.of("", ".sha256", ".metadata.json")) {
                        deleter.delete(actualRoot.resolve(name + suffix));
                        write(out,("removed=" + name + suffix + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));out.force(true);
                    }
                    deleted++;
                }
                write(out,"COMPLETE\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));out.force(true);
                return deleted;
            } catch (Exception failure) {
                write(out,"INCOMPLETE_REVIEW_REQUIRED\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));out.force(true);
                throw new IOException("BACKUP_CLEANUP_INCOMPLETE");
            }
        }
    }
    private static void write(java.nio.channels.FileChannel out, byte[] bytes) throws IOException {
        var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())out.write(buffer);
    }
    private static com.fasterxml.jackson.databind.ObjectMapper json() {
        var module = new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addSerializer(Instant.class,new com.fasterxml.jackson.databind.JsonSerializer<Instant>() {
            @Override public void serialize(Instant value,com.fasterxml.jackson.core.JsonGenerator generator,
                                            com.fasterxml.jackson.databind.SerializerProvider provider)throws IOException {
                generator.writeString(value.toString());
            }
        });
        return new com.fasterxml.jackson.databind.ObjectMapper().registerModule(module);
    }
}
