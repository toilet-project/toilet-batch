package com.geupddong.account;

public interface ErasureLedger extends AutoCloseable {
    /** Durable external acknowledgement is required BEFORE Redis or SQL erasure. */
    void ensureRecorded(ErasureRecord record);
    @Override default void close() { }
}
