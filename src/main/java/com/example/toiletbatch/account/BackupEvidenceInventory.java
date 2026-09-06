package com.example.toiletbatch.account;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Read-only, non-recursive scan. No decrypt, cleanup, timestamp repair or DB access. */
public record BackupEvidenceInventory(Instant scannedAt, List<Entry> files, int unclassifiedEntries) {
    public record Entry(String filename, String sha256, long bytes, Instant modifiedAt, BackupCaptureMetadata capture) {
        public Entry(String filename, String sha256, long bytes, Instant modifiedAt) { this(filename, sha256, bytes, modifiedAt, null); }
    }
    public record Comparison(int dumpFiles, int modifiedBeforeConfirmation,
                             int captureMetadataUnknown, int captureStartedBeforeConfirmation, boolean allCopiesCleared) { }
    public BackupEvidenceInventory { files = List.copyOf(files); }

    public Comparison compare(Instant confirmedAbsentAt) {
        int before = (int) files.stream().filter(f -> !f.modifiedAt().isAfter(confirmedAbsentAt)).count();
        // Even an empty directory says nothing about logs/other copies; never issue clearance.
        int capturedBefore = (int)files.stream().filter(f -> f.capture() != null
                && !Instant.parse(f.capture().captureStartedAt()).isAfter(confirmedAbsentAt)).count();
        return new Comparison(files.size(), before, (int)files.stream().filter(f -> f.capture() == null).count(), capturedBefore, false);
    }

    public static BackupEvidenceInventory scan(Path approvedDirectory, Instant now) {
        try {
            Path absolute = approvedDirectory.toAbsolutePath().normalize();
            Path root = absolute.toRealPath();
            if (!root.equals(absolute) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw invalid();
            var entries = new ArrayList<Entry>();
            var names = new HashSet<String>();
            var sidecars = new HashSet<String>();
            var manifests = new HashSet<String>();
            int unknown = 0, count = 0;
            try (var stream = Files.newDirectoryStream(root)) {
                for (Path file : stream) {
                    if (++count > 20000) throw invalid();
                    String name = file.getFileName().toString();
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) { unknown++; continue; }
                    if (name.matches("toilet-db-[0-9]{8}-[0-9]{6}\\.sql\\.gz\\.enc\\.metadata\\.json")) {
                        manifests.add(name.substring(0, name.length() - 14)); continue;
                    }
                    if (name.matches("toilet-db-[0-9]{8}-[0-9]{6}\\.sql\\.gz\\.enc\\.sha256")) {
                        sidecars.add(name.substring(0, name.length() - 7)); continue;
                    }
                    if (!name.matches("toilet-db-[0-9]{8}-[0-9]{6}\\.sql\\.gz\\.enc")) { unknown++; continue; }
                    var before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (before.size() > 20L * 1024 * 1024 * 1024 || before.lastModifiedTime().toInstant().isAfter(now)) throw invalid();
                    var digest = MessageDigest.getInstance("SHA-256");
                    long total = 0;
                    try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                        byte[] buffer = new byte[65536]; int n;
                        while ((n = in.read(buffer)) != -1) {
                            total += n;
                            if (total > before.size()) throw invalid();
                            digest.update(buffer, 0, n);
                        }
                    }
                    var after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (total != before.size() || before.size() != after.size()
                            || !before.lastModifiedTime().equals(after.lastModifiedTime())
                            || !Objects.equals(before.fileKey(), after.fileKey())) throw invalid();
                    String hash = HexFormat.of().formatHex(digest.digest());
                    Path sidecar = root.resolve(name + ".sha256");
                    byte[] checksum;
                    try (var in = Files.newInputStream(sidecar, LinkOption.NOFOLLOW_LINKS)) { checksum = in.readNBytes(1025); }
                    String text = new String(checksum, java.nio.charset.StandardCharsets.UTF_8).strip();
                    // Existing sha256sum stores the full original path. Never execute/trust that path.
                    if (checksum.length > 1024 || !(text.equals(hash + "  " + name)
                            || text.equals(hash + "  " + file.toString()))) throw invalid();
                    names.add(name);
                    BackupCaptureMetadata capture = null;
                    Path metadata = root.resolve(name + ".metadata.json");
                    if (Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) {
                        byte[] json;
                        try (var in = Files.newInputStream(metadata, LinkOption.NOFOLLOW_LINKS)) { json = in.readNBytes(4097); }
                        if (json.length > 4096) throw invalid();
                        var factory = new com.fasterxml.jackson.core.JsonFactory();
                        factory.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                        capture = new com.fasterxml.jackson.databind.ObjectMapper(factory).readValue(json, BackupCaptureMetadata.class);
                        if (!name.equals(capture.filename()) || !hash.equals(capture.sha256()) || total != capture.bytes()
                                || Instant.parse(capture.captureCompletedAt()).isAfter(now)) throw invalid();
                    }
                    entries.add(new Entry(name, hash, total, before.lastModifiedTime().toInstant(), capture));
                }
            }
            sidecars.removeAll(names); unknown += sidecars.size();
            manifests.removeAll(names); unknown += manifests.size();
            entries.sort(Comparator.comparing(Entry::filename));
            return new BackupEvidenceInventory(now, entries, unknown);
        } catch (Exception ignored) { throw invalid(); }
    }
    private static IllegalStateException invalid() { return new IllegalStateException("ERASURE_BACKUP_INVENTORY_INVALID"); }
}
