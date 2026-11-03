package dev.joracle.rates.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Properties;

/**
 * Manages JDBC connections to the embedded SQLite database.
 * Applies optimal PRAGMA settings (WAL mode, busy timeout, foreign keys)
 * for concurrent read/write workloads with ultra-low RAM footprint.
 */
public class DatabaseManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    private final String jdbcUrl;
    private final Properties connectionProperties;

    public DatabaseManager(String dbPath) {
        Objects.requireNonNull(dbPath, "dbPath cannot be null");
        Path path = Path.of(dbPath).toAbsolutePath().normalize();

        // Ensure parent directories exist
        File parentDir = path.getParent() != null ? path.getParent().toFile() : null;
        if (parentDir != null && !parentDir.exists()) {
            boolean created = parentDir.mkdirs();
            if (created) {
                log.info("Created database directory: {}", parentDir.getAbsolutePath());
            }
        }

        this.jdbcUrl = "jdbc:sqlite:" + path.toString().replace('\\', '/');

        // Configure performance and concurrency pragmas
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5000); // 5s timeout waiting for locks
        this.connectionProperties = config.toProperties();

        log.info("Initialized DatabaseManager with URL: {}", this.jdbcUrl);
    }

    /**
     * Obtains a fresh connection with WAL and concurrency configurations applied.
     */
    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, connectionProperties);
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    @Override
    public void close() {
        log.info("Closing DatabaseManager resources.");
    }
}
