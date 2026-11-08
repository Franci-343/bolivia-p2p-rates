package dev.joracle.rates.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCreateConsistentBackupWithWalCheckpoint() throws Exception {
        Path sourceDb = tempDir.resolve("original.db");
        Path backupDb = tempDir.resolve("backup.db");

        try (DatabaseManager dbManager = new DatabaseManager(sourceDb.toString())) {
            try (Connection conn = dbManager.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE test_data (id INTEGER PRIMARY KEY, val TEXT)");
                stmt.execute("INSERT INTO test_data (val) VALUES ('item1'), ('item2')");
            }

            dbManager.createBackup(backupDb);
        }

        assertTrue(Files.exists(backupDb), "Backup file should exist");
        assertTrue(Files.size(backupDb) > 0, "Backup file should not be empty");

        // Verify backup contents
        try (DatabaseManager backupManager = new DatabaseManager(backupDb.toString());
             Connection conn = backupManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_data")) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
        }
    }

    @Test
    void shouldBackupExistingRatesDbIfPresent() throws Exception {
        Path ratesDb = Path.of("database/rates.db");
        if (Files.exists(ratesDb)) {
            Path backupTarget = Path.of("database/backups/rates_backup_pre_oracle.db");
            try (DatabaseManager dbManager = new DatabaseManager(ratesDb.toString())) {
                dbManager.createBackup(backupTarget);
            }
            assertTrue(Files.exists(backupTarget), "rates_backup_pre_oracle.db should exist");
            assertTrue(Files.size(backupTarget) > 0, "Backup should not be empty");
        }
    }
}
