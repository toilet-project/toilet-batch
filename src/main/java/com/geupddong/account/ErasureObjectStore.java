package com.geupddong.account;

import java.util.List;

/** Bounded encrypted objects. putIfAbsent must durably acknowledge even an exact retry. */
public interface ErasureObjectStore extends AutoCloseable {
    byte[] read(String key);
    void putIfAbsent(String key, byte[] encrypted);
    List<String> list(String prefix, int maximum);
    @Override default void close() { }
}
