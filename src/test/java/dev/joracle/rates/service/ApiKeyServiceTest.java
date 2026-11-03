package dev.joracle.rates.service;

import dev.joracle.rates.db.DatabaseManager;
import dev.joracle.rates.db.DatabaseMigrator;
import dev.joracle.rates.model.ApiClient;
import dev.joracle.rates.model.ClientCreationResult;
import dev.joracle.rates.repository.ApiClientRepository;
import dev.joracle.rates.repository.SqliteApiClientRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ApiKeyServiceTest {

    @TempDir
    Path tempDir;

    private DatabaseManager databaseManager;
    private ApiKeyService apiKeyService;

    @BeforeEach
    void setUp() {
        Path dbPath = tempDir.resolve("test_api_keys.db");
        databaseManager = new DatabaseManager(dbPath.toString());

        DatabaseMigrator migrator = new DatabaseMigrator(databaseManager, Path.of("database"));
        migrator.migrate();

        ApiClientRepository clientRepository = new SqliteApiClientRepository(databaseManager);
        apiKeyService = new ApiKeyService(clientRepository);
    }

    @AfterEach
    void tearDown() {
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    @Test
    void shouldAuthenticateSeedClients() {
        // Consumer demo key seeded in 002_create_api_clients.sql
        ApiKeyService.AuthResult consumerAuth = apiKeyService.authenticate("bolivia_live_demo12345");
        assertTrue(consumerAuth.authorized());
        assertFalse(consumerAuth.rateLimited());
        assertEquals("CONSUMER", consumerAuth.client().role());
        assertFalse(consumerAuth.client().isAdmin());

        // Admin key seeded in 002_create_api_clients.sql
        ApiKeyService.AuthResult adminAuth = apiKeyService.authenticate("bolivia_admin_master999");
        assertTrue(adminAuth.authorized());
        assertTrue(adminAuth.client().isAdmin());
    }

    @Test
    void shouldCreateNewClientAndAuthenticateImmediately() {
        ClientCreationResult result = apiKeyService.createClient("Carlos Amigo", "CONSUMER", 30);
        assertNotNull(result);
        assertNotNull(result.client());
        assertTrue(result.plainApiKey().startsWith("bolivia_live_"));

        // Authenticate with the newly generated plain key
        ApiKeyService.AuthResult auth = apiKeyService.authenticate(result.plainApiKey());
        assertTrue(auth.authorized());
        assertEquals("Carlos Amigo", auth.client().clientName());
        assertEquals(30, auth.client().rateLimitRpm());
    }

    @Test
    void shouldRejectInvalidOrDeactivatedKeys() {
        // Unknown key
        ApiKeyService.AuthResult unknown = apiKeyService.authenticate("bolivia_live_fake999");
        assertFalse(unknown.authorized());
        assertFalse(unknown.rateLimited());

        // Create and toggle off
        ClientCreationResult result = apiKeyService.createClient("Temp User", "CONSUMER", 60);
        boolean toggled = apiKeyService.toggleClientStatus(result.client().id());
        assertTrue(toggled);

        ApiKeyService.AuthResult disabledAuth = apiKeyService.authenticate(result.plainApiKey());
        assertFalse(disabledAuth.authorized());
        assertTrue(disabledAuth.errorMessage().contains("desactivada"));
    }

    @Test
    void shouldEnforceRateLimitPerMinute() {
        // Client with 2 RPM limit
        ClientCreationResult result = apiKeyService.createClient("Strict Client", "CONSUMER", 2);
        String key = result.plainApiKey();

        // 1st and 2nd requests pass
        assertTrue(apiKeyService.authenticate(key).authorized());
        assertTrue(apiKeyService.authenticate(key).authorized());

        // 3rd request fails rate limit
        ApiKeyService.AuthResult third = apiKeyService.authenticate(key);
        assertFalse(third.authorized());
        assertTrue(third.rateLimited());
    }

    @Test
    void shouldListAndDeleteClients() {
        List<ApiClient> initial = apiKeyService.listAllClients();
        int initialSize = initial.size();

        ClientCreationResult created = apiKeyService.createClient("Delete Me", "CONSUMER", 60);
        assertEquals(initialSize + 1, apiKeyService.listAllClients().size());

        boolean deleted = apiKeyService.deleteClient(created.client().id());
        assertTrue(deleted);
        assertEquals(initialSize, apiKeyService.listAllClients().size());
    }
}
