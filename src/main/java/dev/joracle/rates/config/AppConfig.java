package dev.joracle.rates.config;

/**
 * Application configuration loaded from environment variables with sensible defaults.
 * Follows the recommendations in docs/PLAN_BOLIVIA_P2P_RATES.md.
 */
public record AppConfig(
        int serverPort,
        int pollIntervalSeconds,
        int minRefreshCooldownSeconds,
        int binanceRows,
        String fiatCurrency,
        String assetCurrency
) {
    public static AppConfig fromEnv() {
        int serverPort = getEnvAsInt("PORT", 8080);
        // Default to 600s (10 min) as recommended in PLAN_BOLIVIA_P2P_RATES.md to protect against WAF blocks
        int pollIntervalSeconds = getEnvAsInt("POLL_INTERVAL_SECONDS", 600);
        int cooldownSeconds = getEnvAsInt("REFRESH_COOLDOWN_SECONDS", 60);
        int binanceRows = getEnvAsInt("BINANCE_FETCH_ROWS", 10);
        String fiat = getEnvOrDefault("FIAT_CURRENCY", "BOB");
        String asset = getEnvOrDefault("ASSET_CURRENCY", "USDT");

        return new AppConfig(serverPort, pollIntervalSeconds, cooldownSeconds, binanceRows, fiat, asset);
    }

    private static int getEnvAsInt(String key, int defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String getEnvOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isBlank()) ? value.trim() : defaultValue;
    }
}
