package dev.joracle.rates.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppConfigTest {

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
}
