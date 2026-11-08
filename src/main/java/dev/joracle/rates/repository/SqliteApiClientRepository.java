package dev.joracle.rates.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.joracle.rates.config.JsonConfig;
import dev.joracle.rates.db.DatabaseManager;
import dev.joracle.rates.model.ApiClient;
import dev.joracle.rates.model.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * SQLite-backed implementation of {@link ApiClientRepository}.
 * Atomically enqueues client events into {@link OutboxRepository} for remote replication.
 */
public class SqliteApiClientRepository implements ApiClientRepository {

    private static final Logger log = LoggerFactory.getLogger(SqliteApiClientRepository.class);

    private final DatabaseManager databaseManager;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public SqliteApiClientRepository(DatabaseManager databaseManager) {
        this(databaseManager, new SqliteOutboxRepository(databaseManager), JsonConfig.createObjectMapper());
    }

    public SqliteApiClientRepository(DatabaseManager databaseManager, OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager cannot be null");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository cannot be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null");
    }

    @Override
    public Optional<ApiClient> findByApiKeyHash(String apiKeyHash) {
        if (apiKeyHash == null || apiKeyHash.isBlank()) return Optional.empty();

        String sql = "SELECT id, client_id, client_name, api_key_hash, role, rate_limit_rpm, is_active, created_at FROM api_clients WHERE api_key_hash = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, apiKeyHash.trim());
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to query API client by hash: {}", e.getMessage(), e);
        }
        return Optional.empty();
    }

    @Override
    public Optional<ApiClient> findByClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) return Optional.empty();

        String sql = "SELECT id, client_id, client_name, api_key_hash, role, rate_limit_rpm, is_active, created_at FROM api_clients WHERE client_id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, clientId.trim());
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to query API client by clientId: {}", e.getMessage(), e);
        }
        return Optional.empty();
    }

    @Override
    public List<ApiClient> findAll() {
        String sql = "SELECT id, client_id, client_name, api_key_hash, role, rate_limit_rpm, is_active, created_at FROM api_clients ORDER BY id DESC";
        List<ApiClient> list = new ArrayList<>();

        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                list.add(mapRow(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to list API clients: {}", e.getMessage(), e);
        }
        return Collections.unmodifiableList(list);
    }

    @Override
    public ApiClient save(String clientId, String clientName, String apiKeyHash, String role, int rateLimitRpm) {
        String sql = """
                INSERT INTO api_clients (client_id, client_name, api_key_hash, role, rate_limit_rpm, is_active)
                VALUES (?, ?, ?, ?, ?, 1)
                """;

        try (Connection conn = databaseManager.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);

                long id = 0;
                String assignedRole = role != null ? role.toUpperCase() : "CONSUMER";
                int rpm = Math.max(1, rateLimitRpm);

                try (PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    pstmt.setString(1, clientId);
                    pstmt.setString(2, clientName);
                    pstmt.setString(3, apiKeyHash);
                    pstmt.setString(4, assignedRole);
                    pstmt.setInt(5, rpm);
                    pstmt.executeUpdate();

                    try (ResultSet keys = pstmt.getGeneratedKeys()) {
                        if (keys.next()) {
                            id = keys.getLong(1);
                        }
                    }
                }

                // Enqueue API_CLIENT_UPSERT into Outbox
                String opId = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
                String eventId = outboxRepository.getSourceInstanceId() + ":API_CLIENT_UPSERT:" + clientId + ":" + opId;
                Map<String, Object> payloadMap = Map.of(
                        "client_id", clientId,
                        "client_name", clientName,
                        "api_key_hash", apiKeyHash,
                        "role", assignedRole,
                        "rate_limit_rpm", rpm,
                        "is_active", 1,
                        "created_at", Instant.now().toString()
                );
                String payloadJson = objectMapper.writeValueAsString(payloadMap);
                outboxRepository.enqueueEvent(conn, eventId, OutboxEvent.TYPE_API_CLIENT_UPSERT, clientId, payloadJson);

                conn.commit();
                return new ApiClient(id, clientId, clientName, apiKeyHash, assignedRole, rpm, true, "Just now");

            } catch (Exception e) {
                conn.rollback();
                throw new RuntimeException("Failed to save API client: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to obtain database connection: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean toggleActive(long id) {
        String updateSql = "UPDATE api_clients SET is_active = CASE WHEN is_active = 1 THEN 0 ELSE 1 END WHERE id = ?";
        String selectSql = "SELECT id, client_id, client_name, api_key_hash, role, rate_limit_rpm, is_active, created_at FROM api_clients WHERE id = ?";

        try (Connection conn = databaseManager.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);

                boolean updated;
                try (PreparedStatement pstmt = conn.prepareStatement(updateSql)) {
                    pstmt.setLong(1, id);
                    updated = pstmt.executeUpdate() > 0;
                }

                if (updated) {
                    try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
                        pstmt.setLong(1, id);
                        try (ResultSet rs = pstmt.executeQuery()) {
                            if (rs.next()) {
                                ApiClient client = mapRow(rs);
                                String opId = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
                                String eventId = outboxRepository.getSourceInstanceId() + ":API_CLIENT_UPSERT:" + client.clientId() + ":" + opId;
                                Map<String, Object> payloadMap = Map.of(
                                        "client_id", client.clientId(),
                                        "client_name", client.clientName(),
                                        "api_key_hash", client.apiKeyHash(),
                                        "role", client.role(),
                                        "rate_limit_rpm", client.rateLimitRpm(),
                                        "is_active", client.active() ? 1 : 0,
                                        "updated_at", Instant.now().toString()
                                );
                                String payloadJson = objectMapper.writeValueAsString(payloadMap);
                                outboxRepository.enqueueEvent(conn, eventId, OutboxEvent.TYPE_API_CLIENT_UPSERT, client.clientId(), payloadJson);
                            }
                        }
                    }
                }

                conn.commit();
                return updated;

            } catch (Exception e) {
                conn.rollback();
                log.error("Failed to toggle status for client id {}: {}", id, e.getMessage(), e);
                return false;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            log.error("Failed to obtain connection for toggle status on client id {}: {}", id, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean delete(long id) {
        String selectSql = "SELECT client_id FROM api_clients WHERE id = ?";
        String deleteSql = "DELETE FROM api_clients WHERE id = ?";

        try (Connection conn = databaseManager.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);

                String clientId = null;
                try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
                    pstmt.setLong(1, id);
                    try (ResultSet rs = pstmt.executeQuery()) {
                        if (rs.next()) {
                            clientId = rs.getString("client_id");
                        }
                    }
                }

                if (clientId == null) {
                    conn.rollback();
                    return false;
                }

                boolean deleted;
                try (PreparedStatement pstmt = conn.prepareStatement(deleteSql)) {
                    pstmt.setLong(1, id);
                    deleted = pstmt.executeUpdate() > 0;
                }

                if (deleted) {
                    String opId = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
                    String eventId = outboxRepository.getSourceInstanceId() + ":API_CLIENT_DELETE:" + clientId + ":" + opId;
                    Map<String, Object> payloadMap = Map.of(
                            "client_id", clientId,
                            "deleted_at", Instant.now().toString()
                    );
                    String payloadJson = objectMapper.writeValueAsString(payloadMap);
                    outboxRepository.enqueueEvent(conn, eventId, OutboxEvent.TYPE_API_CLIENT_DELETE, clientId, payloadJson);
                }

                conn.commit();
                return deleted;

            } catch (Exception e) {
                conn.rollback();
                log.error("Failed to delete client id {}: {}", id, e.getMessage(), e);
                return false;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            log.error("Failed to obtain connection to delete client id {}: {}", id, e.getMessage(), e);
            return false;
        }
    }

    private ApiClient mapRow(ResultSet rs) throws SQLException {
        return new ApiClient(
                rs.getLong("id"),
                rs.getString("client_id"),
                rs.getString("client_name"),
                rs.getString("api_key_hash"),
                rs.getString("role"),
                rs.getInt("rate_limit_rpm"),
                rs.getInt("is_active") == 1,
                rs.getString("created_at")
        );
    }
}
