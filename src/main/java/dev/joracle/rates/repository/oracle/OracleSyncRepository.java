package dev.joracle.rates.repository.oracle;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.joracle.rates.db.OracleDatabaseManager;
import dev.joracle.rates.model.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Remote persistence repository for synchronizing outbox events into Oracle Autonomous Database.
 * <p>
 * Implements strict idempotency using PROCESSED_EVENTS and deterministic ANSI SQL operations.
 */
public class OracleSyncRepository {

    private static final Logger log = LoggerFactory.getLogger(OracleSyncRepository.class);

    private final OracleDatabaseManager oracleDatabaseManager;
    private final ObjectMapper objectMapper;

    public OracleSyncRepository(OracleDatabaseManager oracleDatabaseManager, ObjectMapper objectMapper) {
        this.oracleDatabaseManager = Objects.requireNonNull(oracleDatabaseManager, "oracleDatabaseManager cannot be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null");
    }

    /**
     * Synchronizes a batch of outbox events to Oracle in a single transactional unit of work.
     *
     * @param events List of pending outbox events ordered by seq_id ASC
     * @return Result containing successfully confirmed seqIds and snapshot cycle IDs
     */
    public SyncBatchResult applyBatch(List<OutboxEvent> events) throws SQLException {
        if (events == null || events.isEmpty()) {
            return new SyncBatchResult(List.of(), List.of(), 0);
        }

        List<Long> confirmedSeqIds = new ArrayList<>();
        List<String> confirmedSnapshotCycleIds = new ArrayList<>();

        try (Connection conn = oracleDatabaseManager.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);

                for (OutboxEvent event : events) {
                    // 1. Idempotency check via PROCESSED_EVENTS
                    boolean alreadyProcessed = false;
                    String processedSql = "INSERT INTO PROCESSED_EVENTS (EVENT_ID, EVENT_TYPE, APPLIED_AT) VALUES (?, ?, CURRENT_TIMESTAMP)";
                    try (PreparedStatement pstmt = conn.prepareStatement(processedSql)) {
                        pstmt.setString(1, event.eventId());
                        pstmt.setString(2, event.eventType());
                        pstmt.executeUpdate();
                    } catch (SQLException e) {
                        // Check unique constraint violation (ORA-00001 or standard SQLState 23xxx)
                        if (e.getErrorCode() == 1 || (e.getSQLState() != null && e.getSQLState().startsWith("23"))
                                || (e.getMessage() != null && e.getMessage().toLowerCase().contains("unique"))) {
                            log.info("Event {} was already processed in a previous attempt. Skipping payload insertion.", event.eventId());
                            alreadyProcessed = true;
                        } else {
                            throw e;
                        }
                    }

                    // 2. Process payload if not already recorded
                    if (!alreadyProcessed) {
                        switch (event.eventType()) {
                            case OutboxEvent.TYPE_RATE_SNAPSHOT -> applyRateSnapshot(conn, event);
                            case OutboxEvent.TYPE_API_CLIENT_UPSERT -> applyApiClientUpsert(conn, event);
                            case OutboxEvent.TYPE_API_CLIENT_DELETE -> applyApiClientDelete(conn, event);
                            default -> log.warn("Unknown outbox event type: {}", event.eventType());
                        }
                    }

                    confirmedSeqIds.add(event.seqId());
                    if (OutboxEvent.TYPE_RATE_SNAPSHOT.equals(event.eventType())) {
                        confirmedSnapshotCycleIds.add(event.aggregateId());
                    }
                }

                conn.commit();
                log.info("Successfully synced batch of {} event(s) to Oracle Autonomous Database.", confirmedSeqIds.size());
                return new SyncBatchResult(confirmedSeqIds, confirmedSnapshotCycleIds, confirmedSeqIds.size());

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        }
    }

    private void applyRateSnapshot(Connection conn, OutboxEvent event) throws SQLException {
        Map<String, Object> data = parsePayload(event.payloadJson());

        String insertSql = """
                INSERT INTO RATE_SNAPSHOTS (
                    SOURCE_INSTANCE_ID, LOCAL_SNAPSHOT_ID, CYCLE_ID, CAPTURED_AT, DURATION_MS,
                    IS_STALE, STATUS, FIAT, ASSET,
                    BUY_REFERENCE_PRICE, BUY_AVERAGE_PRICE, BUY_MEDIAN_PRICE, BUY_MIN_PRICE, BUY_MAX_PRICE,
                    BUY_VALID_COUNT, BUY_DISCARDED_COUNT, BUY_TOTAL_RECEIVED, BUY_DETAILS_JSON,
                    SELL_REFERENCE_PRICE, SELL_AVERAGE_PRICE, SELL_MEDIAN_PRICE, SELL_MIN_PRICE, SELL_MAX_PRICE,
                    SELL_VALID_COUNT, SELL_DISCARDED_COUNT, SELL_TOTAL_RECEIVED, SELL_DETAILS_JSON,
                    SYNCED_AT
                ) VALUES (
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, ?,
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, ?,
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, ?,
                    CURRENT_TIMESTAMP
                )
                """;

        try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
            // Extract sourceInstanceId from eventId prefix: <instanceId>:<type>:<aggregateId>:<localId>
            String sourceInstanceId = extractSourceInstanceId(event.eventId());
            long localId = getLong(data, "local_id", 0L);
            long cycleId = getLong(data, "cycle_id", 0L);
            String capturedAtStr = getString(data, "captured_at", Instant.now().toString());
            Instant capturedInstant = Instant.parse(capturedAtStr);
            long durationMs = getLong(data, "duration_ms", 0L);
            int isStale = getInt(data, "is_stale", 0);
            String status = getString(data, "status", "OK");
            String fiat = getString(data, "fiat", "BOB");
            String asset = getString(data, "asset", "USDT");

            int idx = 1;
            pstmt.setString(idx++, sourceInstanceId);
            pstmt.setLong(idx++, localId);
            pstmt.setLong(idx++, cycleId);
            pstmt.setTimestamp(idx++, Timestamp.from(capturedInstant));
            pstmt.setLong(idx++, durationMs);
            pstmt.setInt(idx++, isStale);
            pstmt.setString(idx++, status);
            pstmt.setString(idx++, fiat);
            pstmt.setString(idx++, asset);

            // Buy numbers with exact BigDecimal mapping
            pstmt.setBigDecimal(idx++, new BigDecimal(getString(data, "buy_reference_price", "0.00")));
            pstmt.setBigDecimal(idx++, new BigDecimal(getString(data, "buy_average_price", "0.00")));
            pstmt.setBigDecimal(idx++, new BigDecimal(getString(data, "buy_median_price", "0.00")));
            pstmt.setBigDecimal(idx++, new BigDecimal(getString(data, "buy_min_price", "0.00")));
            pstmt.setBigDecimal(idx++, new BigDecimal(getString(data, "buy_max_price", "0.00")));
            pstmt.setInt(idx++, getInt(data, "buy_valid_count", 0));
            pstmt.setInt(idx++, getInt(data, "buy_discarded_count", 0));
            pstmt.setInt(idx++, getInt(data, "buy_total_received", 0));
            pstmt.setString(idx++, getString(data, "buy_details_json", "{}"));

            // Sell numbers with exact BigDecimal mapping
            pstmt.setBigDecimal(idx++, new BigDecimal(getString(data, "sell_reference_price", "0.00")));
            pstmt.setBigDecimal(idx++, new BigDecimal(getString(data, "sell_average_price", "0.00")));
            pstmt.setBigDecimal(idx++, new BigDecimal(getString(data, "sell_median_price", "0.00")));
            pstmt.setBigDecimal(idx++, new BigDecimal(getString(data, "sell_min_price", "0.00")));
            pstmt.setBigDecimal(idx++, new BigDecimal(getString(data, "sell_max_price", "0.00")));
            pstmt.setInt(idx++, getInt(data, "sell_valid_count", 0));
            pstmt.setInt(idx++, getInt(data, "sell_discarded_count", 0));
            pstmt.setInt(idx++, getInt(data, "sell_total_received", 0));
            pstmt.setString(idx++, getString(data, "sell_details_json", "{}"));

            pstmt.executeUpdate();
        }
    }

    private void applyApiClientUpsert(Connection conn, OutboxEvent event) throws SQLException {
        Map<String, Object> data = parsePayload(event.payloadJson());

        String clientId = getString(data, "client_id", event.aggregateId());
        String clientName = getString(data, "client_name", "Cliente");
        String apiKeyHash = getString(data, "api_key_hash", "");
        String role = getString(data, "role", "CONSUMER");
        int rpm = getInt(data, "rate_limit_rpm", 60);
        int isActive = getInt(data, "is_active", 1);

        String updateSql = """
                UPDATE API_CLIENTS
                SET CLIENT_NAME = ?,
                    API_KEY_HASH = ?,
                    ROLE = ?,
                    RATE_LIMIT_RPM = ?,
                    IS_ACTIVE = ?,
                    UPDATED_AT = CURRENT_TIMESTAMP,
                    DELETED_AT = NULL
                WHERE CLIENT_ID = ?
                """;

        boolean updated = false;
        try (PreparedStatement pstmt = conn.prepareStatement(updateSql)) {
            pstmt.setString(1, clientName);
            pstmt.setString(2, apiKeyHash);
            pstmt.setString(3, role);
            pstmt.setInt(4, rpm);
            pstmt.setInt(5, isActive);
            pstmt.setString(6, clientId);
            updated = pstmt.executeUpdate() > 0;
        }

        if (!updated) {
            String insertSql = """
                    INSERT INTO API_CLIENTS (CLIENT_ID, CLIENT_NAME, API_KEY_HASH, ROLE, RATE_LIMIT_RPM, IS_ACTIVE, CREATED_AT, UPDATED_AT)
                    VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """;
            try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                pstmt.setString(1, clientId);
                pstmt.setString(2, clientName);
                pstmt.setString(3, apiKeyHash);
                pstmt.setString(4, role);
                pstmt.setInt(5, rpm);
                pstmt.setInt(6, isActive);
                pstmt.executeUpdate();
            }
        }
    }

    private void applyApiClientDelete(Connection conn, OutboxEvent event) throws SQLException {
        Map<String, Object> data = parsePayload(event.payloadJson());
        String clientId = getString(data, "client_id", event.aggregateId());

        String sql = """
                UPDATE API_CLIENTS
                SET DELETED_AT = CURRENT_TIMESTAMP,
                    IS_ACTIVE = 0
                WHERE CLIENT_ID = ?
                """;

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, clientId);
            pstmt.executeUpdate();
        }
    }

    private Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to parse event JSON payload: {}", e.getMessage());
            return Map.of();
        }
    }

    private String extractSourceInstanceId(String eventId) {
        if (eventId == null) return "unknown";
        int colonIdx = eventId.indexOf(':');
        return colonIdx > 0 ? eventId.substring(0, colonIdx) : "default";
    }

    private String getString(Map<String, Object> map, String key, String def) {
        Object val = map.get(key);
        return val != null ? val.toString() : def;
    }

    private long getLong(Map<String, Object> map, String key, long def) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.longValue();
        if (val != null) {
            try { return Long.parseLong(val.toString()); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private int getInt(Map<String, Object> map, String key, int def) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val != null) {
            try { return Integer.parseInt(val.toString()); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    public record SyncBatchResult(
            List<Long> confirmedSeqIds,
            List<String> confirmedSnapshotCycleIds,
            int appliedCount
    ) {}
}
