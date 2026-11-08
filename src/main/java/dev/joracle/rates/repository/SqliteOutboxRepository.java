package dev.joracle.rates.repository;

import dev.joracle.rates.db.DatabaseManager;
import dev.joracle.rates.model.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * SQLite-backed implementation of {@link OutboxRepository}.
 * Enforces transactional safety, retryability for failed events,
 * and conservative cleanup preserving the most recent 100 snapshots.
 */
public class SqliteOutboxRepository implements OutboxRepository {

    private static final Logger log = LoggerFactory.getLogger(SqliteOutboxRepository.class);

    private final DatabaseManager databaseManager;
    private volatile String cachedSourceInstanceId;

    public SqliteOutboxRepository(DatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager cannot be null");
    }

    @Override
    public String getSourceInstanceId() {
        if (cachedSourceInstanceId != null) {
            return cachedSourceInstanceId;
        }

        String selectSql = "SELECT value FROM sync_metadata WHERE key = 'source_instance_id'";
        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(selectSql)) {

            if (rs.next()) {
                cachedSourceInstanceId = rs.getString("value");
                return cachedSourceInstanceId;
            }
        } catch (SQLException e) {
            log.error("Failed to query source_instance_id: {}", e.getMessage(), e);
        }

        // Fallback: generate and persist if missing
        String generated = "inst_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String insertSql = "INSERT OR REPLACE INTO sync_metadata (key, value) VALUES ('source_instance_id', ?)";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
            pstmt.setString(1, generated);
            pstmt.executeUpdate();
            cachedSourceInstanceId = generated;
            return cachedSourceInstanceId;
        } catch (SQLException e) {
            log.warn("Could not persist generated source_instance_id: {}", e.getMessage());
            return generated;
        }
    }

    @Override
    public void enqueueEvent(Connection conn, String eventId, String eventType, String aggregateId, String payloadJson) throws SQLException {
        String sql = """
                INSERT INTO sync_outbox (event_id, event_type, aggregate_id, payload_json, status, created_at)
                VALUES (?, ?, ?, ?, 'PENDING', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
                """;
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, eventId);
            pstmt.setString(2, eventType);
            pstmt.setString(3, aggregateId);
            pstmt.setString(4, payloadJson);
            pstmt.executeUpdate();
        }
    }

    @Override
    public List<OutboxEvent> findPendingEvents(int limit) {
        int targetLimit = Math.max(1, limit);
        // Requirement: Failed events MUST be retried and not permanently locked
        String sql = """
                SELECT seq_id, event_id, event_type, aggregate_id, payload_json, status,
                       retry_count, last_error, created_at, processed_at
                FROM sync_outbox
                WHERE status IN ('PENDING', 'FAILED')
                ORDER BY seq_id ASC
                LIMIT ?
                """;

        List<OutboxEvent> events = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, targetLimit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    events.add(new OutboxEvent(
                            rs.getLong("seq_id"),
                            rs.getString("event_id"),
                            rs.getString("event_type"),
                            rs.getString("aggregate_id"),
                            rs.getString("payload_json"),
                            rs.getString("status"),
                            rs.getInt("retry_count"),
                            rs.getString("last_error"),
                            rs.getString("created_at"),
                            rs.getString("processed_at")
                    ));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to query pending outbox events: {}", e.getMessage(), e);
        }
        return Collections.unmodifiableList(events);
    }

    @Override
    public void markBatchConfirmed(List<Long> seqIds, List<String> snapshotCycleIds) {
        if ((seqIds == null || seqIds.isEmpty()) && (snapshotCycleIds == null || snapshotCycleIds.isEmpty())) {
            return;
        }

        try (Connection conn = databaseManager.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);

                // 1. Mark snapshots as synced in SQLite
                if (snapshotCycleIds != null && !snapshotCycleIds.isEmpty()) {
                    String updateSnapshotSql = """
                            UPDATE rate_snapshots
                            SET synced_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
                            WHERE cycle_id = ? AND synced_at IS NULL
                            """;
                    try (PreparedStatement pstmt = conn.prepareStatement(updateSnapshotSql)) {
                        for (String cycleIdStr : snapshotCycleIds) {
                            try {
                                pstmt.setLong(1, Long.parseLong(cycleIdStr));
                                pstmt.addBatch();
                            } catch (NumberFormatException ignored) {}
                        }
                        pstmt.executeBatch();
                    }
                }

                // 2. Purge confirmed events from outbox to prevent DB growth
                if (seqIds != null && !seqIds.isEmpty()) {
                    String deleteOutboxSql = "DELETE FROM sync_outbox WHERE seq_id = ?";
                    try (PreparedStatement pstmt = conn.prepareStatement(deleteOutboxSql)) {
                        for (Long seqId : seqIds) {
                            pstmt.setLong(1, seqId);
                            pstmt.addBatch();
                        }
                        pstmt.executeBatch();
                    }
                }

                conn.commit();
                log.info("Outbox batch confirmed: {} event(s) purged, {} snapshot cycle(s) marked synced.",
                        seqIds != null ? seqIds.size() : 0,
                        snapshotCycleIds != null ? snapshotCycleIds.size() : 0);

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            log.error("Failed to mark outbox batch confirmed: {}", e.getMessage(), e);
        }
    }

    @Override
    public void markBatchFailed(List<Long> seqIds, String errorMessage) {
        if (seqIds == null || seqIds.isEmpty()) return;

        String sql = """
                UPDATE sync_outbox
                SET status = 'FAILED',
                    retry_count = retry_count + 1,
                    last_error = ?,
                    processed_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
                WHERE seq_id = ?
                """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            String trimmedError = errorMessage != null && errorMessage.length() > 500
                    ? errorMessage.substring(0, 500)
                    : errorMessage;

            for (Long seqId : seqIds) {
                pstmt.setString(1, trimmedError);
                pstmt.setLong(2, seqId);
                pstmt.addBatch();
            }
            pstmt.executeBatch();
            log.warn("Marked {} outbox event(s) as FAILED (will be retried on next cycle): {}",
                    seqIds.size(), trimmedError);

        } catch (SQLException e) {
            log.error("Failed to mark outbox events as failed: {}", e.getMessage(), e);
        }
    }

    @Override
    public int purgeOldSyncedSnapshots(int keepRecentCount) {
        int safeKeep = Math.max(10, keepRecentCount);
        // Requirement: NEVER delete unsynced data, ALWAYS keep at least keepRecentCount snapshots
        String sql = """
                DELETE FROM rate_snapshots
                WHERE synced_at IS NOT NULL
                  AND id NOT IN (
                      SELECT id FROM rate_snapshots ORDER BY id DESC LIMIT ?
                  )
                """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, safeKeep);
            int purged = pstmt.executeUpdate();
            if (purged > 0) {
                log.info("Cleaned up {} old confirmed snapshot(s) from SQLite (retaining last {}).", purged, safeKeep);
            }
            return purged;

        } catch (SQLException e) {
            log.error("Failed to purge old synced snapshots: {}", e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public long countPendingEvents() {
        String sql = "SELECT COUNT(*) FROM sync_outbox WHERE status IN ('PENDING', 'FAILED')";
        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            log.error("Failed to count pending outbox events: {}", e.getMessage(), e);
        }
        return 0;
    }
}
