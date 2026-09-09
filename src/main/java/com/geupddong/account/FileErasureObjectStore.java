package com.geupddong.account;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Private, write-once encrypted files on a pre-provisioned Linux filesystem.
 * No auto-bootstrap, overwrite, delete, cloud fallback, or local "latest" checkpoint.
 * A partial write is quarantined by authenticated read-back failure, never repaired by guessing.
 * This protects against ordinary crashes, NOT a hostile root or whole-disk rollback.
 */
public final class FileErasureObjectStore implements ErasureObjectStore {
    private static final String MARKER = ".ledger-store";
    private static final String LOCK = ".ledger.lock";
    private static final String UUID_PATTERN = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
    private final Path root;
    private final String realm;
    private final byte[] marker;
    private final Safety safety;
    private final Pattern keyPattern;

    /** There is deliberately no production configuration switch to bypass POSIX/fsync checks. */
    public FileErasureObjectStore(Path root, String realm, String storeId) {
        this(root, realm, storeId, new LinuxSafety());
    }

    interface Safety {
        void directory(Path path) throws IOException;
        void file(Path root, Path path) throws IOException;
        void syncDirectory(Path path) throws IOException;
        void createFile(Path path) throws IOException;
    }

    // Package-private seam for fault injection on non-Linux developer machines. Never selected by Environment.
    FileErasureObjectStore(Path root, String realm, String storeId, Safety safety) {
        try {
            if (!root.isAbsolute() || !root.equals(root.normalize()) || root.getParent() == null
                    || !realm.matches("[a-z0-9-]{3,40}") || !UUID.fromString(storeId).toString().equals(storeId)) throw unavailable();
            this.root = root; this.realm = realm; this.safety = safety;
            this.marker = markerBytes(realm, storeId);
            this.keyPattern = Pattern.compile("(?:(?:v1|catalogue-v1)/" + Pattern.quote(realm) + "/" + UUID_PATTERN
                    + "|completion-v1/" + Pattern.quote(realm) + "/" + UUID_PATTERN + "/" + UUID_PATTERN + ")\\.bin");
            validateRoot();
            safety.syncDirectory(root); // Reject filesystems/providers that cannot acknowledge directory durability.
        } catch (Exception ignored) { throw unavailable(); }
    }

    static byte[] markerBytes(String realm, String storeId) {
        return ("LOCAL_ERASURE_LEDGER_V1\n" + realm + "\n" + storeId + "\n").getBytes(StandardCharsets.US_ASCII);
    }

    private void validateRoot() throws IOException {
        for (Path part = root; part != null; part = part.getParent())
            if (Files.isSymbolicLink(part)) throw unavailable();
        if (!root.toRealPath().equals(root)) throw unavailable();
        safety.directory(root);
        if (!java.util.Arrays.equals(marker, readFile(root.resolve(MARKER)))) throw unavailable();
        safety.file(root, root.resolve(LOCK));
    }

    private Path path(String key) {
        if (key == null || !keyPattern.matcher(key).matches()) throw unavailable();
        return root.resolve(key.replace("/", "__"));
    }

    private <T> T locked(Supplier<T> action) {
        try {
            validateRoot();
            try (var channel = FileChannel.open(root.resolve(LOCK), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var lock = channel.tryLock()) {
                if (lock == null) throw unavailable();
                return action.get();
            }
        } catch (Exception ignored) { throw unavailable(); }
    }

    @Override public byte[] read(String key) {
        Path target = path(key);
        return locked(() -> {
            try { return readFile(target); }
            catch (NoSuchFileException absent) { return null; }
            catch (IOException ignored) { throw unavailable(); }
        });
    }

    private byte[] readFile(Path target) throws IOException {
        safety.file(root, target);
        try (var channel = FileChannel.open(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size < 1 || size > 8192) throw unavailable();
            var buffer = ByteBuffer.allocate((int) size);
            while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw unavailable();
            if (channel.size() != size) throw unavailable();
            return buffer.array();
        }
    }

    @Override public void putIfAbsent(String key, byte[] encrypted) {
        Path target = path(key);
        if (encrypted == null || encrypted.length < 34 || encrypted.length > 8192) throw unavailable();
        locked(() -> {
            try {
                boolean created = false;
                try { safety.createFile(target); created = true; }
                catch (FileAlreadyExistsException exists) { /* Exact retry: NEVER truncate or replace. */ }
                safety.file(root, target);
                try (var channel = FileChannel.open(target, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    if (created) {
                        var buffer = ByteBuffer.wrap(encrypted);
                        while (buffer.hasRemaining()) channel.write(buffer);
                    }
                    // Also re-force on retry: the previous process may have lost the acknowledgement.
                    channel.force(true);
                }
                safety.syncDirectory(root);
                // Content identity is authenticated by ObjectErasureLedger, not ciphertext equality (random nonces).
                readFile(target);
                return null;
            } catch (IOException ignored) { throw unavailable(); }
        });
    }

    @Override public List<String> list(String prefix, int maximum) {
        if (maximum < 0 || maximum > 1000000
                || !(prefix.equals("v1/" + realm + "/") || prefix.equals("catalogue-v1/" + realm + "/")
                    || prefix.equals("completion-v1/" + realm + "/"))) throw unavailable();
        return locked(() -> {
            try (var entries = Files.newDirectoryStream(root)) {
                var result = new ArrayList<String>();
                int scanned = 0;
                for (Path entry : entries) {
                    if (++scanned > 3000002) throw unavailable();
                    String name = entry.getFileName().toString();
                    if (name.equals(MARKER) || name.equals(LOCK)) continue;
                    String key = name.replace("__", "/");
                    if (!path(key).equals(entry)) throw unavailable(); // Unknown entries, subdirectories, links fail closed.
                    safety.file(root, entry);
                    if (key.startsWith(prefix)) {
                        if (result.size() >= maximum) throw unavailable();
                        result.add(key);
                    }
                }
                result.sort(String::compareTo);
                return List.copyOf(result);
            } catch (IOException ignored) { throw unavailable(); }
        });
    }

    static final class LinuxSafety implements Safety {
        private void linux() {
            if (!System.getProperty("os.name", "").equals("Linux")) throw unavailable();
        }
        @Override public void directory(Path path) throws IOException {
            linux();
            var attrs = Files.readAttributes(path, java.nio.file.attribute.PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isDirectory() || !attrs.permissions().equals(PosixFilePermissions.fromString("rwx------"))) throw unavailable();
            // A deliberately narrow deployment contract. tmpfs, NFS, SMB, overlay and untested providers are rejected.
            if (!Set.of("ext4", "ext3", "xfs", "btrfs").contains(Files.getFileStore(path).type())) throw unavailable();
        }
        @Override public void file(Path root, Path path) throws IOException {
            linux();
            var attrs = Files.readAttributes(path, java.nio.file.attribute.PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile() || !attrs.permissions().equals(PosixFilePermissions.fromString("rw-------"))
                    || !attrs.owner().equals(Files.getOwner(root, LinkOption.NOFOLLOW_LINKS))
                    || ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1) throw unavailable();
        }
        @Override public void syncDirectory(Path path) throws IOException {
            linux();
            try (var channel = FileChannel.open(path, StandardOpenOption.READ)) { channel.force(true); }
        }
        @Override public void createFile(Path path) throws IOException {
            linux();
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
    }

    private static IllegalStateException unavailable() { return new IllegalStateException("ERASURE_LOCAL_STORE_UNAVAILABLE"); }
}
