package dev.joracle.rates.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.joracle.rates.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Manages JDBC connections to Oracle Autonomous Database via HikariCP.
 * <p>
 * CRITICAL INVARIANT:
 * Strictly enforces maximumPoolSize = 1 and minimumIdle = 0.
 * In a single process instance, at most ONE physical connection to Oracle exists.
 * When the worker is idle, HikariCP retires the connection (after idleTimeout),
 * releasing the session back to the 30-connection Always Free limit so other
 * projects are never starved.
 */
public class OracleDatabaseManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OracleDatabaseManager.class);

    private final AppConfig config;
    private final HikariDataSource dataSource;

    public OracleDatabaseManager(AppConfig config) {
        this.config = Objects.requireNonNull(config, "config cannot be null");

        if (!config.oracleSyncEnabled()) {
            log.info("Oracle synchronization is DISABLED by configuration (ORACLE_SYNC_ENABLED=false). Pool will not be initialized.");
            this.dataSource = null;
            return;
        }

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("OracleSyncPool");
        hikariConfig.setDriverClassName("oracle.jdbc.OracleDriver");
        hikariConfig.setUsername(config.oracleDbUser());
        hikariConfig.setPassword(config.oracleDbPassword());

        // Invariants: At most 1 connection, 0 in idle
        hikariConfig.setMaximumPoolSize(1);
        hikariConfig.setMinimumIdle(0);
        hikariConfig.setIdleTimeout(60_000); // 60s idle timeout
        hikariConfig.setConnectionTimeout(5_000); // 5s timeout waiting for connection
        hikariConfig.setMaxLifetime(300_000); // 5m max lifetime

        // Critical: Do not block or fail application bootstrap if Oracle is offline
        hikariConfig.setInitializationFailTimeout(-1);

        // Wallet & mTLS configuration
        String jdbcUrl = config.oracleJdbcUrl();
        if (config.oracleTnsAdmin() != null && !config.oracleTnsAdmin().isBlank()) {
            String tnsAdminPath = config.oracleTnsAdmin().trim().replace('\\', '/');
            hikariConfig.addDataSourceProperty("oracle.net.tns_admin", tnsAdminPath);
            hikariConfig.addDataSourceProperty("oracle.net.wallet_location", tnsAdminPath);
            System.setProperty("oracle.net.tns_admin", tnsAdminPath);
            System.setProperty("TNS_ADMIN", tnsAdminPath);
            System.setProperty("oracle.net.wallet_location", tnsAdminPath);

            // Also append ?TNS_ADMIN= to the JDBC URL if thin driver format and not yet present
            if (jdbcUrl.startsWith("jdbc:oracle:") && !jdbcUrl.contains("TNS_ADMIN=")) {
                String separator = jdbcUrl.contains("?") ? "&" : "?";
                jdbcUrl = jdbcUrl + separator + "TNS_ADMIN=" + tnsAdminPath;
            }
            log.info("Configured Oracle TNS_ADMIN and wallet location: {}", tnsAdminPath);
        }
        hikariConfig.setJdbcUrl(jdbcUrl);

        log.info("Initializing Oracle HikariCP pool. Target: {} (User: {}, MaxPool: 1, MinIdle: 0)",
                jdbcUrl, config.oracleDbUser());

        this.dataSource = new HikariDataSource(hikariConfig);
    }

    /**
     * For testing purposes, allows creating manager with a custom datasource.
     */
    public OracleDatabaseManager(AppConfig config, HikariDataSource customDataSource) {
        this.config = Objects.requireNonNull(config);
        this.dataSource = customDataSource;
    }

    public boolean isEnabled() {
        return config.oracleSyncEnabled() && dataSource != null;
    }

    public Connection getConnection() throws SQLException {
        if (!isEnabled()) {
            throw new SQLException("Oracle database synchronization is disabled or pool not initialized");
        }
        return dataSource.getConnection();
    }

    public int getActiveConnections() {
        if (dataSource == null || dataSource.isClosed() || dataSource.getHikariPoolMXBean() == null) {
            return 0;
        }
        try {
            return dataSource.getHikariPoolMXBean().getActiveConnections();
        } catch (Exception e) {
            return 0;
        }
    }

    public int getIdleConnections() {
        if (dataSource == null || dataSource.isClosed() || dataSource.getHikariPoolMXBean() == null) {
            return 0;
        }
        try {
            return dataSource.getHikariPoolMXBean().getIdleConnections();
        } catch (Exception e) {
            return 0;
        }
    }

    public int getTotalConnections() {
        if (dataSource == null || dataSource.isClosed() || dataSource.getHikariPoolMXBean() == null) {
            return 0;
        }
        try {
            return dataSource.getHikariPoolMXBean().getTotalConnections();
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            log.info("Closing Oracle HikariCP pool...");
            dataSource.close();
            log.info("Oracle HikariCP pool closed.");
        }
    }
}
