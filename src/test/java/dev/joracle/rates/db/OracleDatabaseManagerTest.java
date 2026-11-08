package dev.joracle.rates.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.joracle.rates.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleDatabaseManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldNotInitializeDataSourceWhenSyncDisabled() {
        AppConfig config = new AppConfig(
                8080, 600, 60, 10, "BOB", "USDT", "database/rates.db", "",
                false, "jdbc:oracle:thin:@francodb_low", "BOLIVIA_P2P", "", "", 1, 60, 25, 100
        );

        try (OracleDatabaseManager manager = new OracleDatabaseManager(config)) {
            assertFalse(manager.isEnabled());
            assertEquals(0, manager.getActiveConnections());
            assertEquals(0, manager.getIdleConnections());
            assertEquals(0, manager.getTotalConnections());
            assertThrows(Exception.class, manager::getConnection);
        }
    }

    @Test
    void shouldEnforceMaxOneConnectionAndMinZeroOnDataSource() throws Exception {
        Path testDb = tempDir.resolve("test_pool.db");
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:sqlite:" + testDb.toString().replace('\\', '/'));
        hikariConfig.setMaximumPoolSize(1);
        hikariConfig.setMinimumIdle(0);
        hikariConfig.setIdleTimeout(10_000);

        try (HikariDataSource ds = new HikariDataSource(hikariConfig)) {
            AppConfig config = new AppConfig(
                    8080, 600, 60, 10, "BOB", "USDT", "database/rates.db", "",
                    true, "jdbc:sqlite:" + testDb.toString().replace('\\', '/'), "BOLIVIA_P2P", "", "", 1, 60, 25, 100
            );

            try (OracleDatabaseManager manager = new OracleDatabaseManager(config, ds)) {
                assertTrue(manager.isEnabled());
                assertEquals(0, manager.getActiveConnections());

                try (Connection conn = manager.getConnection()) {
                    assertEquals(1, manager.getActiveConnections());
                    assertEquals(1, manager.getTotalConnections());
                }

                // After connection closes, active count returns to 0
                assertEquals(0, manager.getActiveConnections());
            }
        }
    }

    @Test
    void shouldInitializeOraclePoolWithoutFailingImmediately() {
        AppConfig config = new AppConfig(
                8080, 600, 60, 10, "BOB", "USDT", "database/rates.db", "",
                true, "jdbc:oracle:thin:@francodb_low", "BOLIVIA_P2P", "secret", "/tmp/fake_wallet", 1, 60, 25, 100
        );

        try (OracleDatabaseManager manager = new OracleDatabaseManager(config)) {
            assertTrue(manager.isEnabled());
            assertEquals(0, manager.getActiveConnections());
            assertEquals(0, manager.getIdleConnections());
        }
    }
}
