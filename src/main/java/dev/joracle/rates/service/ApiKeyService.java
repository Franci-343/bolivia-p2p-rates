package dev.joracle.rates.service;

import dev.joracle.rates.model.ApiClient;
import dev.joracle.rates.model.ClientCreationResult;
import dev.joracle.rates.repository.ApiClientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service orchestrating API key generation, hashing (SHA-256),
 * authentication, and in-memory rate limiting per client.
 */
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ApiClientRepository clientRepository;
    private final ConcurrentHashMap<Long, ClientRateTracker> rateTrackers = new ConcurrentHashMap<>();

    public ApiKeyService(ApiClientRepository clientRepository) {
        this.clientRepository = Objects.requireNonNull(clientRepository, "clientRepository cannot be null");
    }

    /**
     * Hashes an incoming raw API Key using SHA-256.
     */
    public String hashApiKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawKey.trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Generates a new secure random API Key and registers the client in database.
     */
    public ClientCreationResult createClient(String clientName, String role, int rateLimitRpm) {
        String cleanName = (clientName != null && !clientName.isBlank()) ? clientName.trim() : "Cliente";
        String slug = toSlug(cleanName);
        String clientId = "client_" + slug + "_" + (SECURE_RANDOM.nextInt(9000) + 1000);

        byte[] randomBytes = new byte[16];
        SECURE_RANDOM.nextBytes(randomBytes);
        String randomHex = HexFormat.of().formatHex(randomBytes);
        String plainApiKey = "bolivia_live_" + randomHex;

        String keyHash = hashApiKey(plainApiKey);
        String assignedRole = (role != null && role.equalsIgnoreCase("ADMIN")) ? "ADMIN" : "CONSUMER";
        int rpm = Math.max(1, rateLimitRpm);

        ApiClient client = clientRepository.save(clientId, cleanName, keyHash, assignedRole, rpm);
        log.info("Created new API Client '{}' (ID: {}, Role: {}, RPM: {})", cleanName, clientId, assignedRole, rpm);

        return new ClientCreationResult(client, plainApiKey);
    }

    /**
     * Ensures an administrative master key matches an environment-provided secret,
     * overriding or initializing the default seed account securely.
     */
    public void ensureAdminKey(String rawAdminKey) {
        if (rawAdminKey == null || rawAdminKey.isBlank()) return;
        String hash = hashApiKey(rawAdminKey);
        Optional<ApiClient> existing = clientRepository.findByClientId("admin_local_system");
        if (existing.isPresent()) {
            if (!existing.get().apiKeyHash().equals(hash)) {
                clientRepository.delete(existing.get().id());
                clientRepository.save("admin_local_system", "Administrador del Sistema", hash, "ADMIN", 1000);
                log.info("Master administrator credentials synchronized with ADMIN_API_KEY environment variable.");
            }
        } else {
            clientRepository.save("admin_local_system", "Administrador del Sistema", hash, "ADMIN", 1000);
            log.info("Master administrator account initialized from ADMIN_API_KEY environment variable.");
        }
    }

    /**
     * Authenticates an incoming request key and verifies per-minute rate limits.
     */
    public AuthResult authenticate(String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isBlank()) {
            return new AuthResult(false, false, null, "API Key ausente. Envíe la cabecera 'X-API-Key'.");
        }

        String hash = hashApiKey(rawApiKey);
        Optional<ApiClient> clientOpt = clientRepository.findByApiKeyHash(hash);

        if (clientOpt.isEmpty()) {
            return new AuthResult(false, false, null, "API Key no válida o no registrada.");
        }

        ApiClient client = clientOpt.get();
        if (!client.active()) {
            return new AuthResult(false, false, client, "Esta API Key ha sido desactivada temporal o permanentemente.");
        }

        // Verify per-client rate limit
        ClientRateTracker tracker = rateTrackers.computeIfAbsent(client.id(), id -> new ClientRateTracker());
        if (!tracker.tryAcquire(client.rateLimitRpm())) {
            log.warn("Rate limit exceeded for client '{}' ({} RPM max)", client.clientName(), client.rateLimitRpm());
            return new AuthResult(false, true, client, "Cuota de peticiones por minuto superada (" + client.rateLimitRpm() + " RPM max).");
        }

        return new AuthResult(true, false, client, null);
    }

    public List<ApiClient> listAllClients() {
        return clientRepository.findAll();
    }

    public boolean toggleClientStatus(long id) {
        return clientRepository.toggleActive(id);
    }

    public boolean deleteClient(long id) {
        rateTrackers.remove(id);
        return clientRepository.delete(id);
    }

    private String toSlug(String input) {
        String nowhitespace = input.trim().replaceAll("\\s+", "_");
        String normalized = Normalizer.normalize(nowhitespace, Normalizer.Form.NFD);
        return normalized.replaceAll("[^\\p{ASCII}]", "").replaceAll("[^a-zA-Z0-9_]", "").toLowerCase(Locale.ROOT);
    }

    public record AuthResult(
            boolean authorized,
            boolean rateLimited,
            ApiClient client,
            String errorMessage
    ) {}

    /**
     * Simple sliding minute rate limiter for low memory footprint.
     */
    private static class ClientRateTracker {
        private volatile long currentMinute = 0;
        private final AtomicInteger requestCount = new AtomicInteger(0);

        public synchronized boolean tryAcquire(int maxPerMinute) {
            long minuteNow = Instant.now().getEpochSecond() / 60;
            if (minuteNow != currentMinute) {
                currentMinute = minuteNow;
                requestCount.set(0);
            }
            return requestCount.incrementAndGet() <= maxPerMinute;
        }
    }
}
