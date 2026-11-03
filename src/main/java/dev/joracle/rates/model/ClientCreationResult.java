package dev.joracle.rates.model;

/**
 * Result returned when creating an API client, including the plaintext key (displayed only once).
 */
public record ClientCreationResult(
        ApiClient client,
        String plainApiKey
) {}
