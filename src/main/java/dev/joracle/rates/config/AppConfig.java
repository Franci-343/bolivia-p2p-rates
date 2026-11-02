package dev.joracle.rates.config;

/**
 * Application configuration loaded from environment variables with sensible defaults.
 */
public record AppConfig(
        int serverPort,
        int pollIntervalSeconds,
        int binanceRows,
        String fiatCurrency,
        String assetCurrency
) {
    public static AppConfig fromEnv() {
        int serverPort = getEnvAsInt("PORT", 8080);
        int pollIntervalSeconds = getEnvAsInt("POLL_INTERVAL_SECONDS", 60);
        int binanceRows = getEnvAsInt("BINANCE_FETCH_ROWS", 10);
        String fiat = getEnvOrDefault("FIAT_CURRENCY", "BOB");
        String asset = getEnvOrDefault("ASSET_CURRENCY", "USDT");

        return new AppConfig(serverPort, pollIntervalSeconds, binanceRows, fiat, asset);
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
