package com.geupddong.account;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;

/** Not auto-wired. Provision once, never unlink/replace this lock. Python peers MUST use lockf, not flock.
 * Acquire before SQL transactions; retain through commit, completion evidence and snapshot verification.
 */
public final class LocalMaintenanceLease implements AutoCloseable {
    // POSIX record locks are process-scoped: do not open/close a second descriptor in this JVM.
    private static final java.util.Set<Path> HELD=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Path path;
    private final Object identity;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;
    private LocalMaintenanceLease(Path path, Object identity, FileChannel channel, FileLock lock) {
        this.path=path; this.identity=identity; this.channel=channel; this.lock=lock;
    }
    public static LocalMaintenanceLease acquire(Path path, int expectedUid) {
        FileChannel channel=null;
        boolean reserved=false;
        try {
            if (!System.getProperty("os.name").equals("Linux") || expectedUid<0 || !path.isAbsolute()
                    || !path.equals(path.normalize()) || !path.getFileName().toString().equals(".maintenance.lock")) fail();
            for (Path p=path; p!=null; p=p.getParent()) if (Files.isSymbolicLink(p)) fail();
            if (!path.toRealPath().equals(path)) fail();
            if(!HELD.add(path)) fail();
            reserved=true;
            var parent=path.getParent();
            if (!Files.getPosixFilePermissions(parent).equals(PosixFilePermissions.fromString("rwx------"))
                    || ((Number)Files.getAttribute(parent,"unix:uid")).intValue()!=expectedUid) fail();
            if (!Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rw-------"))
                    || ((Number)Files.getAttribute(path,"unix:uid")).intValue()!=expectedUid
                    || ((Number)Files.getAttribute(path,"unix:nlink")).intValue()!=1) fail();
            var before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile() || before.size()!=0 || before.fileKey()==null) fail();
            channel=FileChannel.open(path,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            var lock=channel.tryLock();
            if (lock==null) fail();
            var after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if (!Objects.equals(before.fileKey(),after.fileKey()) || after.size()!=0) fail();
            return new LocalMaintenanceLease(path,before.fileKey(),channel,lock);
        } catch(Exception ignored) {
            if(channel!=null) try {channel.close();} catch(Exception suppressed) { }
            if(reserved) HELD.remove(path);
            throw new IllegalStateException("ERASURE_MAINTENANCE_LOCK_UNAVAILABLE");
        }
    }
    @Override public void close() {
        if(closed) return;
        closed=true;
        boolean valid=false;
        try {
            valid=lock.isValid() && !Files.isSymbolicLink(path) && Objects.equals(identity,
                Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey());
            lock.release();
        } catch(Exception ignored) { valid=false; }
        finally { try {channel.close();} catch(Exception ignored) {valid=false;} finally {HELD.remove(path);} }
        if(!valid) throw new IllegalStateException("ERASURE_MAINTENANCE_LOCK_RELEASE_UNVERIFIED");
    }
    private static void fail() {throw new IllegalStateException();}
}
