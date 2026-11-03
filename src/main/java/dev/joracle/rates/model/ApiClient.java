package dev.joracle.rates.model;

/**
 * Represents a registered API client/consumer authorized to access rate endpoints.
 */
public record ApiClient(
        long id,
        String clientId,
        String clientName,
        String apiKeyHash,
        String role,
        int rateLimitRpm,
        boolean active,
        String createdAt
) {
    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role);
    }
}
