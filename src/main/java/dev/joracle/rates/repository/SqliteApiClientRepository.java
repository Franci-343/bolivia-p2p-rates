package dev.joracle.rates.repository;

import dev.joracle.rates.db.DatabaseManager;
import dev.joracle.rates.model.ApiClient;
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
import java.util.Optional;

/**
 * SQLite-backed implementation of {@link ApiClientRepository}.
 */
public class SqliteApiClientRepository implements ApiClientRepository {

    private static final Logger log = LoggerFactory.getLogger(SqliteApiClientRepository.class);
    private final DatabaseManager databaseManager;

    public SqliteApiClientRepository(DatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager cannot be null");
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

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            pstmt.setString(1, clientId);
            pstmt.setString(2, clientName);
            pstmt.setString(3, apiKeyHash);
            pstmt.setString(4, role != null ? role.toUpperCase() : "CONSUMER");
            pstmt.setInt(5, Math.max(1, rateLimitRpm));

            pstmt.executeUpdate();

            long id = 0;
            try (ResultSet keys = pstmt.getGeneratedKeys()) {
                if (keys.next()) {
                    id = keys.getLong(1);
                }
            }

            return new ApiClient(id, clientId, clientName, apiKeyHash, role, rateLimitRpm, true, "Just now");

        } catch (SQLException e) {
            throw new RuntimeException("Failed to save API client: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean toggleActive(long id) {
        String sql = "UPDATE api_clients SET is_active = CASE WHEN is_active = 1 THEN 0 ELSE 1 END WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, id);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to toggle status for client id {}: {}", id, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean delete(long id) {
        String sql = "DELETE FROM api_clients WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, id);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete client id {}: {}", id, e.getMessage(), e);
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
