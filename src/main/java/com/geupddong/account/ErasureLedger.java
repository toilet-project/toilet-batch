package com.geupddong.account;

public interface ErasureLedger extends AutoCloseable {
    /** Durable acknowledgement is required BEFORE Redis or SQL erasure; production also checks an independent inventory. */
    void ensureRecorded(ErasureRecord record);
    @Override default void close() { }
}
