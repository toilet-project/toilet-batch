package com.geupddong.account;

import java.time.Clock;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** Production beans use this factory; raw ledger factory remains for isolated/read-only tools. */
public final class ProtectedErasureLedgerFactory {
    private ProtectedErasureLedgerFactory() { }
    public static ErasureLedger create(Environment env, JdbcTemplate jdbc) {
        if (!env.getProperty("erasure.ledger.enabled",Boolean.class,false)) return ErasureLedgerFactory.create(env);
        // Missing configuration never falls back to unprotected R2-only erasure.
        if (!env.getProperty("ERASURE_CHECKPOINT_ENABLED",Boolean.class,false)
                || !env.getProperty("erasure.ledger.catalogue-enabled",Boolean.class,false))
            return record->{throw new IllegalStateException("ERASURE_CHECKPOINT_NOT_CONFIGURED");};
        try {
            String realm=env.getRequiredProperty("erasure.ledger.realm");
            String epoch=env.getRequiredProperty("ERASURE_CHECKPOINT_DATABASE_EPOCH");
            if (!java.util.UUID.fromString(epoch).toString().equals(epoch)) throw new IllegalArgumentException();
            var store=GitHubErasureCheckpointStore.configured(env.getRequiredProperty("ERASURE_CHECKPOINT_GITHUB_TOKEN"));
            CheckpointedErasureLedger.Exclusive lock=work->jdbc.execute((ConnectionCallback<Void>) connection->{
                boolean held=false;
                try {
                    // Uses the transaction-bound physical connection in API and batch alike.
                    try (var acquire=connection.prepareStatement("SELECT GET_LOCK('geupddong-erasure-checkpoint-v1', 2)")) {
                        acquire.setQueryTimeout(3);
                        try (var result=acquire.executeQuery()) {
                            held=result.next() && result.getInt(1)==1 && !result.wasNull();
                        }
                    } catch (Exception failure) {
                        // Never return a connection with an uncertain advisory lock to the pool.
                        try { connection.abort(Runnable::run); } catch (Exception ignored) { }
                        throw failure;
                    }
                    if (!held) throw new IllegalStateException("ERASURE_CHECKPOINT_LOCK_BUSY");
                    work.run();
                    return null;
                } finally {
                    if (held) try (var release=connection.prepareStatement("SELECT RELEASE_LOCK('geupddong-erasure-checkpoint-v1')")) {
                        release.setQueryTimeout(3);
                        try (var result=release.executeQuery()) {
                            if (!result.next() || result.getInt(1)!=1 || result.wasNull())
                                throw new IllegalStateException("ERASURE_CHECKPOINT_LOCK_RELEASE_FAILED");
                        }
                    } catch (Exception failure) {
                        try { connection.abort(Runnable::run); } catch (Exception ignored) { }
                        throw failure;
                    }
                }
            });
            return new CheckpointedErasureLedger(ErasureLedgerFactory.configured(env),store,lock,Clock.systemUTC(),realm,epoch);
        } catch (Exception ignored) { throw new IllegalStateException("ERASURE_CHECKPOINT_CONFIGURATION_INVALID"); }
    }
}
