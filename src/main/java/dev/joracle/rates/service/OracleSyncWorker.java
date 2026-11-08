package dev.joracle.rates.service;

import dev.joracle.rates.config.AppConfig;
import dev.joracle.rates.db.OracleDatabaseManager;
import dev.joracle.rates.model.OutboxEvent;
import dev.joracle.rates.repository.OutboxRepository;
import dev.joracle.rates.repository.oracle.OracleSyncRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background worker service coordinating asynchronous eventual consistency to Oracle Autonomous Database.
 * <p>
 * Key Invariants:
 * 1. Only component interacting with Oracle JDBC pool (which is strictly capped at maximumPoolSize = 1, minimumIdle = 0).
 * 2. Processes events sequentially (seq_id ASC) and idempotently.
 * 3. Never purges SQLite data unless remote COMMIT succeeded and local ACK is persisted.
 * 4. Preserves at least recentSnapshotsToKeep (100) snapshots in SQLite.
 * 5. Failed events remain retryable and are never abandoned.
 */
public class OracleSyncWorker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OracleSyncWorker.class);

    private final OracleDatabaseManager oracleDatabaseManager;
    private final OracleSyncRepository oracleSyncRepository;
    private final OutboxRepository outboxRepository;
    private final AppConfig config;
    private final ScheduledExecutorService scheduler;

    private final AtomicBoolean isSyncing = new AtomicBoolean(false);
    private volatile Instant lastSuccessInstant = null;
    private volatile String lastError = null;
    private volatile long lastDurationMs = 0;
    private volatile int consecutiveFailures = 0;
    private volatile long totalEventsSynced = 0;

    public OracleSyncWorker(
            OracleDatabaseManager oracleDatabaseManager,
            OracleSyncRepository oracleSyncRepository,
            OutboxRepository outboxRepository,
            AppConfig config
    ) {
        this.oracleDatabaseManager = Objects.requireNonNull(oracleDatabaseManager, "oracleDatabaseManager cannot be null");
        this.oracleSyncRepository = Objects.requireNonNull(oracleSyncRepository, "oracleSyncRepository cannot be null");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "oracle-sync-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        if (!config.oracleSyncEnabled()) {
            log.info("OracleSyncWorker is DISABLED (ORACLE_SYNC_ENABLED=false). Background sync will not run.");
            return;
        }

        log.info("Starting OracleSyncWorker. Interval: {}s. Batch size: {}. Retaining {} recent SQLite snapshots.",
                config.oracleSyncIntervalSeconds(), config.oracleSyncBatchSize(), config.sqliteRecentSnapshots());

        // Initial delay 10s so application warms up, then fixed delay
        scheduler.scheduleWithFixedDelay(
                this::syncSafe,
                10,
                config.oracleSyncIntervalSeconds(),
                TimeUnit.SECONDS
        );
    }

    /**
     * Executes a single synchronization cycle synchronously.
     * Prevents overlapping cycles.
     *
     * @return count of synced events, or -1 if skipped / failed
     */
    public int syncOnce() {
        if (!oracleDatabaseManager.isEnabled()) {
            return 0;
        }

        if (!isSyncing.compareAndSet(false, true)) {
            log.debug("Oracle sync cycle already in progress. Skipping.");
            return 0;
        }

        long startTime = System.currentTimeMillis();
        try {
            List<OutboxEvent> pending = outboxRepository.findPendingEvents(config.oracleSyncBatchSize());

            if (pending.isEmpty()) {
                // When backlog is clear, perform periodic cleanup of confirmed snapshots
                outboxRepository.purgeOldSyncedSnapshots(config.sqliteRecentSnapshots());
                return 0;
            }

            log.info("OracleSyncWorker: Found {} pending event(s) to synchronize to Oracle.", pending.size());

            // 1. Apply batch to Oracle (using maximum 1 connection from pool)
            OracleSyncRepository.SyncBatchResult result = oracleSyncRepository.applyBatch(pending);

            // 2. Mark confirmed in SQLite and purge confirmed events from sync_outbox
            outboxRepository.markBatchConfirmed(result.confirmedSeqIds(), result.confirmedSnapshotCycleIds());

            // 3. Conservative SQLite cleanup: purge confirmed snapshots older than 100 recent
            outboxRepository.purgeOldSyncedSnapshots(config.sqliteRecentSnapshots());

            long duration = System.currentTimeMillis() - startTime;
            lastSuccessInstant = Instant.now();
            lastError = null;
            consecutiveFailures = 0;
            lastDurationMs = duration;
            totalEventsSynced += result.appliedCount();

            log.info("OracleSyncWorker: Successfully synchronized {} event(s) in {} ms.", result.appliedCount(), duration);
            return result.appliedCount();

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            consecutiveFailures++;
            lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            lastDurationMs = duration;

            log.warn("OracleSyncWorker: Synchronization failed after {} ms (failures: {}): {}. Events remain in SQLite and will be retried.",
                    duration, consecutiveFailures, lastError);

            // Mark batch failed so retryCount and lastError are updated, but remain retryable
            try {
                List<OutboxEvent> failedEvents = outboxRepository.findPendingEvents(config.oracleSyncBatchSize());
                if (!failedEvents.isEmpty()) {
                    List<Long> seqIds = failedEvents.stream().map(OutboxEvent::seqId).toList();
                    outboxRepository.markBatchFailed(seqIds, lastError);
                }
            } catch (Exception markEx) {
                log.error("Failed to mark outbox events with failure status: {}", markEx.getMessage());
            }

            return -1;

        } finally {
            isSyncing.set(false);
        }
    }

    private void syncSafe() {
        try {
            syncOnce();
        } catch (Exception e) {
            log.error("Unexpected error in OracleSyncWorker loop: {}", e.getMessage(), e);
        }
    }

    // Health and Observability Accessors
    public boolean isSyncing() {
        return isSyncing.get();
    }

    public Instant getLastSuccessInstant() {
        return lastSuccessInstant;
    }

    public String getLastError() {
        return lastError;
    }

    public long getLastDurationMs() {
        return lastDurationMs;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public long getTotalEventsSynced() {
        return totalEventsSynced;
    }

    public long getLagSeconds() {
        if (lastSuccessInstant == null) {
            return -1;
        }
        return Duration.between(lastSuccessInstant, Instant.now()).getSeconds();
    }

    @Override
    public void close() {
        log.info("Shutting down OracleSyncWorker scheduler...");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
