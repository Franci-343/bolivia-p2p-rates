package dev.joracle.rates.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void testNormalizeBasePath() {
        assertEquals("", AppConfig.normalizeBasePath(null));
        assertEquals("", AppConfig.normalizeBasePath(""));
        assertEquals("", AppConfig.normalizeBasePath("   "));
        assertEquals("", AppConfig.normalizeBasePath("/"));
        assertEquals("", AppConfig.normalizeBasePath("///"));
        assertEquals("/bolivia-p2p-rates", AppConfig.normalizeBasePath("bolivia-p2p-rates"));
        assertEquals("/bolivia-p2p-rates", AppConfig.normalizeBasePath("/bolivia-p2p-rates"));
        assertEquals("/bolivia-p2p-rates", AppConfig.normalizeBasePath("/bolivia-p2p-rates/"));
        assertEquals("/bolivia-p2p-rates", AppConfig.normalizeBasePath("bolivia-p2p-rates///"));
        assertEquals("/service/v2", AppConfig.normalizeBasePath("/service/v2/"));
    }

    @Test
    void testPublicUrlWithEmptyBasePath() {
        AppConfig config = new AppConfig(8080, 600, 60, 10, "BOB", "USDT", "database/rates.db", "");

        assertEquals("/", config.publicUrl(null));
        assertEquals("/", config.publicUrl(""));
        assertEquals("/", config.publicUrl("/"));
        assertEquals("/admin", config.publicUrl("/admin"));
        assertEquals("/admin", config.publicUrl("admin"));
        assertEquals("/portal/keys", config.publicUrl("/portal/keys"));
        assertEquals("/api/v1/rates/latest", config.publicUrl("/api/v1/rates/latest"));
    }

    @Test
    void testPublicUrlWithCustomBasePath() {
        AppConfig config = new AppConfig(8080, 600, 60, 10, "BOB", "USDT", "database/rates.db", "/bolivia-p2p-rates");

        assertEquals("/bolivia-p2p-rates/", config.publicUrl(null));
        assertEquals("/bolivia-p2p-rates/", config.publicUrl(""));
        assertEquals("/bolivia-p2p-rates/", config.publicUrl("/"));
        assertEquals("/bolivia-p2p-rates/admin", config.publicUrl("/admin"));
        assertEquals("/bolivia-p2p-rates/admin", config.publicUrl("admin"));
        assertEquals("/bolivia-p2p-rates/portal/keys", config.publicUrl("/portal/keys"));
        assertEquals("/bolivia-p2p-rates/api/v1/rates/latest", config.publicUrl("/api/v1/rates/latest"));
    }

    @Test
    void testFromPropertiesWithDottedKeys() {
        Properties props = new Properties();
        props.setProperty("server.port", "9090");
        props.setProperty("oracle.sync.enabled", "true");
        props.setProperty("oracle.db.user", "TEST_USER");
        props.setProperty("oracle.db.password", "secret123");
        props.setProperty("oracle.jdbc.url", "jdbc:oracle:thin:@custom_tns");
        props.setProperty("oracle.tns.admin", "/custom/wallet");
        props.setProperty("sqlite.recent.snapshots", "150");

        AppConfig config = AppConfig.fromProperties(props);

        assertEquals(9090, config.serverPort());
        assertTrue(config.oracleSyncEnabled());
        assertEquals("TEST_USER", config.oracleDbUser());
        assertEquals("secret123", config.oracleDbPassword());
        assertEquals("jdbc:oracle:thin:@custom_tns", config.oracleJdbcUrl());
        assertEquals("/custom/wallet", config.oracleTnsAdmin());
        assertEquals(150, config.sqliteRecentSnapshots());
    }

    @Test
    void testFromPropertiesWithUppercaseKeys() {
        Properties props = new Properties();
        props.setProperty("PORT", "7070");
        props.setProperty("ORACLE_SYNC_ENABLED", "1");
        props.setProperty("ORACLE_DB_USER", "BOLIVIA_P2P");
        props.setProperty("ORACLE_DB_PASSWORD", "Pass_Oracle_2026");

        AppConfig config = AppConfig.fromProperties(props);

        assertEquals(7070, config.serverPort());
        assertTrue(config.oracleSyncEnabled());
        assertEquals("BOLIVIA_P2P", config.oracleDbUser());
        assertEquals("Pass_Oracle_2026", config.oracleDbPassword());
    }

    @Test
    void testLoadPropertiesFromCustomFile() throws Exception {
        Path propFile = tempDir.resolve("custom-rates.properties");
        try (BufferedWriter writer = Files.newBufferedWriter(propFile)) {
            writer.write("oracle.sync.enabled=true\n");
            writer.write("oracle.db.password=FromFilePassword456\n");
            writer.write("server.port=9999\n");
        }

        System.setProperty("config.file", propFile.toAbsolutePath().toString());
        try {
            AppConfig config = AppConfig.fromEnv();
            assertTrue(config.oracleSyncEnabled());
            assertEquals("FromFilePassword456", config.oracleDbPassword());
            assertEquals(9999, config.serverPort());
        } finally {
            System.clearProperty("config.file");
        }
    }
}
