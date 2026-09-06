package com.example.toiletbatch.account;

import com.example.toiletbatch.batch.BatchFailureNotifier;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Invoked AFTER the daily public-data attempt terminates, never on its own timer. */
@Service
public class AccountErasureJob {
    private static final Logger log = LoggerFactory.getLogger(AccountErasureJob.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final AccountErasureWorker worker;
    private final BatchFailureNotifier notifier;
    private final MeterRegistry metrics;
    private final boolean enabled;
    private final boolean maintenance;
    private final int maxPerRun;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong overdue = new AtomicLong();

    public AccountErasureJob(AccountErasureWorker worker, BatchFailureNotifier notifier, MeterRegistry metrics,
            @Value("${batch.account-erasure.enabled:false}") boolean enabled,
            @Value("${batch.account-erasure.max-per-run:5000}") int maxPerRun,
            @Value("${account.lifecycle.maintenance:true}") boolean maintenance) {
        if (maxPerRun < 1 || maxPerRun > 100000) throw new IllegalArgumentException("Invalid erasure run limit");
        this.worker = worker; this.notifier = notifier; this.metrics = metrics;
        this.enabled = enabled; this.maxPerRun = maxPerRun;
        this.maintenance = maintenance;
        metrics.gauge("account.erasure.overdue", overdue);
    }

    public void runAfterSync() {
        if (!enabled || maintenance || !running.compareAndSet(false, true)) return;
        var cutoff = LocalDateTime.now(KST);
        int attempted = 0, completed = 0, failed = 0;
        long afterId = 0;
        boolean infrastructureFailure = false;
        try {
            scan: while (attempted < maxPerRun) {
                var ids = worker.findDue(cutoff, afterId, Math.min(50, maxPerRun - attempted));
                if (ids.isEmpty()) break;
                for (long id : ids) {
                    afterId = id;
                    attempted++;
                    try {
                        if (worker.eraseIfDue(id, cutoff)) {
                            completed++;
                            metrics.counter("account.erasure.completed").increment();
                        }
                    } catch (RuntimeException failure) {
                        failed++;
                        metrics.counter("account.erasure.failed").increment();
                        log.error("Account erasure failed (ERASURE_RETRY_REQUIRED)");
                        try { worker.recordFailure(id, LocalDateTime.now(KST)); }
                        catch (RuntimeException unavailable) {
                            log.error("Account erasure checkpoint unavailable");
                        }
                        // Stop a systemic R2 outage/configuration error from holding the daily chain for hours.
                        if ("ERASURE_LEDGER_UNAVAILABLE".equals(failure.getMessage())
                                || "ERASURE_LEDGER_NOT_CONFIGURED".equals(failure.getMessage())) {
                            infrastructureFailure = true;
                            break scan;
                        }
                    }
                }
            }
            overdue.set(worker.countOverdue(LocalDateTime.now(KST)));
        } catch (RuntimeException unavailable) {
            infrastructureFailure = true;
            metrics.counter("account.erasure.failed").increment();
            log.error("Account erasure infrastructure unavailable");
        } finally {
            // Counts only. No user ID, nickname, email, token, SQL or exception body.
            log.info("Account erasure finished: attempted={}, completed={}, failed={}, overdue={}",
                    attempted, completed, failed, overdue.get());
            try {
                if (failed > 0 || infrastructureFailure || overdue.get() > 0)
                    notifier.notifyAccountErasureFailure(failed, overdue.get(), infrastructureFailure);
            } catch (RuntimeException notificationFailure) {
                log.error("Account erasure notification unavailable");
            } finally {
                running.set(false);
            }
        }
    }
}
