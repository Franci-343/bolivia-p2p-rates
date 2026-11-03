package dev.joracle.rates.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Lightweight, zero-dependency database migration engine.
 * Automatically discovers, tracks, and applies numbered SQL migration files
 * from the database/ directory in strict sequential order.
 */
public class DatabaseMigrator {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrator.class);
    private static final Pattern MIGRATION_PATTERN = Pattern.compile("^(\\d+)_.*\\.sql$");

    private final DatabaseManager databaseManager;
    private final Path migrationsDirectory;

    public DatabaseMigrator(DatabaseManager databaseManager, Path migrationsDirectory) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager cannot be null");
        this.migrationsDirectory = Objects.requireNonNull(migrationsDirectory, "migrationsDirectory cannot be null");
    }

    /**
     * Executes pending migrations.
     *
     * @return count of applied migrations
     */
    public int migrate() {
        if (!Files.exists(migrationsDirectory) || !Files.isDirectory(migrationsDirectory)) {
            log.warn("Migrations directory '{}' does not exist. Skipping migrations.", migrationsDirectory);
            return 0;
        }

        try (Connection conn = databaseManager.getConnection()) {
            ensureSchemaVersionTable(conn);
            Set<Integer> appliedVersions = getAppliedVersions(conn);

            List<MigrationFile> pendingMigrations = discoverPendingMigrations(appliedVersions);

            if (pendingMigrations.isEmpty()) {
                log.info("Database schema is up to date. No pending migrations.");
                return 0;
            }

            log.info("Found {} pending migration(s) to apply.", pendingMigrations.size());

            int appliedCount = 0;
            for (MigrationFile migration : pendingMigrations) {
                applyMigration(conn, migration);
                appliedCount++;
            }

            log.info("Successfully applied {} migration(s).", appliedCount);
            return appliedCount;

        } catch (SQLException | IOException e) {
            throw new RuntimeException("Failed to run database migrations: " + e.getMessage(), e);
        }
    }

    private void ensureSchemaVersionTable(Connection conn) throws SQLException {
        String sql = """
                CREATE TABLE IF NOT EXISTS schema_version (
                    version INTEGER PRIMARY KEY,
                    script_name TEXT NOT NULL,
                    applied_at TEXT NOT NULL DEFAULT (datetime('now', 'utc'))
                );
                """;
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private Set<Integer> getAppliedVersions(Connection conn) throws SQLException {
        Set<Integer> versions = new HashSet<>();
        String sql = "SELECT version FROM schema_version";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                versions.add(rs.getInt("version"));
            }
        }
        return versions;
    }

    private List<MigrationFile> discoverPendingMigrations(Set<Integer> appliedVersions) throws IOException {
        List<MigrationFile> files = new ArrayList<>();

        try (Stream<Path> stream = Files.list(migrationsDirectory)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(path)) continue;

                String fileName = path.getFileName().toString();
                Matcher matcher = MIGRATION_PATTERN.matcher(fileName);
                if (matcher.matches()) {
                    int version = Integer.parseInt(matcher.group(1));
                    if (!appliedVersions.contains(version)) {
                        files.add(new MigrationFile(version, fileName, path));
                    }
                }
            }
        }

        files.sort(Comparator.comparingInt(MigrationFile::version));
        return files;
    }

    private void applyMigration(Connection conn, MigrationFile migration) throws IOException, SQLException {
        log.info("Applying migration {}: {}", migration.version(), migration.fileName());

        String sqlContent = Files.readString(migration.filePath());
        boolean autoCommit = conn.getAutoCommit();

        try {
            conn.setAutoCommit(false);

            // Execute script statements
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(sqlContent);
            }

            // Record version
            String recordSql = "INSERT INTO schema_version (version, script_name) VALUES (?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(recordSql)) {
                pstmt.setInt(1, migration.version());
                pstmt.setString(2, migration.fileName());
                pstmt.executeUpdate();
            }

            conn.commit();
            log.info("Migration {} ({}) successfully applied.", migration.version(), migration.fileName());

        } catch (SQLException e) {
            conn.rollback();
            throw new SQLException("Error applying migration " + migration.fileName() + ": " + e.getMessage(), e);
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    private record MigrationFile(int version, String fileName, Path filePath) {}
}
