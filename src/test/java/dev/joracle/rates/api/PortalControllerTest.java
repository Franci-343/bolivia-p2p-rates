package dev.joracle.rates.api;

import dev.joracle.rates.config.AppConfig;
import dev.joracle.rates.model.ApiClient;
import dev.joracle.rates.repository.ApiClientRepository;
import dev.joracle.rates.repository.InMemoryRateRepository;
import dev.joracle.rates.repository.RateRepository;
import dev.joracle.rates.service.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class PortalControllerTest {

    private PortalController controller;

    @BeforeEach
    void setUp() {
        RateRepository rateRepository = new InMemoryRateRepository();
        ApiClientRepository dummyRepo = new ApiClientRepository() {
            @Override
            public ApiClient save(String clientId, String clientName, String apiKeyHash, String role, int rateLimitRpm) {
                return new ApiClient(1L, clientId, clientName, apiKeyHash, role, rateLimitRpm, true, "2026-11-03 12:00:00");
            }
            @Override
            public Optional<ApiClient> findByApiKeyHash(String apiKeyHash) { return Optional.empty(); }
            @Override
            public Optional<ApiClient> findByClientId(String clientId) { return Optional.empty(); }
            @Override
            public List<ApiClient> findAll() { return List.of(); }
            @Override
            public boolean toggleActive(long id) { return true; }
            @Override
            public boolean delete(long id) { return true; }
        };

        ApiKeyService apiKeyService = new ApiKeyService(dummyRepo);
        AppConfig config = AppConfig.fromEnv();
        controller = new PortalController(rateRepository, apiKeyService, config);
    }

    @Test
    void shouldAllowUpToFiveKeysPerHourPerIp() {
        String testIp = "200.87.100.50";

        // First 5 attempts from this IP should succeed
        for (int i = 1; i <= 5; i++) {
            assertTrue(controller.tryAcquireIpSlot(testIp), "Attempt " + i + " should succeed");
        }

        // 6th attempt must be rejected
        assertFalse(controller.tryAcquireIpSlot(testIp), "Attempt 6 must be blocked by IP rate limit");
    }

    @Test
    void shouldTrackDifferentIpsSeparately() {
        String ip1 = "10.0.0.1";
        String ip2 = "10.0.0.2";

        for (int i = 1; i <= 5; i++) {
            assertTrue(controller.tryAcquireIpSlot(ip1));
        }

        // ip1 is exhausted
        assertFalse(controller.tryAcquireIpSlot(ip1));

        // ip2 should still be completely available
        assertTrue(controller.tryAcquireIpSlot(ip2));
    }
}
