package dev.joracle.rates.repository;

import dev.joracle.rates.model.ApiClient;

import java.util.List;
import java.util.Optional;

/**
 * Storage contract for managing API clients and keys.
 */
public interface ApiClientRepository {

    Optional<ApiClient> findByApiKeyHash(String apiKeyHash);

    Optional<ApiClient> findByClientId(String clientId);

    List<ApiClient> findAll();

    ApiClient save(String clientId, String clientName, String apiKeyHash, String role, int rateLimitRpm);

    boolean toggleActive(long id);

    boolean delete(long id);
}
