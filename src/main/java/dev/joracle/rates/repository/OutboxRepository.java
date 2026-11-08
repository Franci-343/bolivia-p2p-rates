package dev.joracle.rates.repository;

import dev.joracle.rates.model.OutboxEvent;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Contract for managing transactional outbox events and safe local SQLite cleanup.
 */
public interface OutboxRepository {

    /**
     * Retrieves the persistent unique instance identifier for this deployment node.
     */
    String getSourceInstanceId();

    /**
     * Enqueues a new outbox event using the provided transaction connection.
     */
    void enqueueEvent(Connection conn, String eventId, String eventType, String aggregateId, String payloadJson) throws SQLException;

    /**
     * Retrieves pending or retryable failed events ordered strictly by sequence ID ascending.
     */
    List<OutboxEvent> findPendingEvents(int limit);

    /**
     * Confirms successful remote commit in Oracle:
     * 1. Marks rate_snapshots with synced_at timestamp.
     * 2. Purges confirmed outbox events from SQLite so disk does not grow indefinitely.
     */
    void markBatchConfirmed(List<Long> seqIds, List<String> snapshotCycleIds);

    /**
     * Records a failure for a batch, incrementing retryCount and saving the error message.
     * Does NOT permanently lock events; they remain retryable on future cycles.
     */
    void markBatchFailed(List<Long> seqIds, String errorMessage);

    /**
     * Conservatively purges confirmed historical snapshots from SQLite:
     * Deletes only records where synced_at IS NOT NULL, preserving at least keepRecentCount snapshots.
     *
     * @param keepRecentCount Minimum number of latest snapshots to retain (e.g. 100)
     * @return Number of purged snapshot rows
     */
    int purgeOldSyncedSnapshots(int keepRecentCount);

    /**
     * Returns the count of pending or failed outbox events awaiting synchronization.
     */
    long countPendingEvents();
}
