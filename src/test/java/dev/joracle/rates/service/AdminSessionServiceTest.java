package dev.joracle.rates.service;

import dev.joracle.rates.model.ApiClient;
import dev.joracle.rates.service.AdminSessionService.AdminSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AdminSessionServiceTest {

    private AdminSessionService sessionService;
    private ApiClient adminClient;

    @BeforeEach
    void setUp() {
        sessionService = new AdminSessionService();
        adminClient = new ApiClient(1L, "admin_test", "Administrador Test", "hash123", "ADMIN", 600, true, "2026-11-03");
    }

    @Test
    void testCreateSessionGeneratesOpaqueIdAndCsrfToken() {
        AdminSession session = sessionService.createSession(adminClient);

        assertNotNull(session);
        assertTrue(session.sessionId().startsWith("adm_sess_"));
        assertTrue(session.csrfToken().startsWith("csrf_"));
        assertEquals("Administrador Test", session.clientName());
        assertEquals(1L, session.clientId());
        assertFalse(session.isExpired());

        // Session ID and CSRF token must have high entropy
        assertTrue(session.sessionId().length() >= 64);
        assertTrue(session.csrfToken().length() >= 48);

        // Verify retrieval
        Optional<AdminSession> retrieved = sessionService.getSession(session.sessionId());
        assertTrue(retrieved.isPresent());
        assertEquals(session.sessionId(), retrieved.get().sessionId());
        assertEquals(session.csrfToken(), retrieved.get().csrfToken());
    }

    @Test
    void testRejectNonAdminClientSession() {
        ApiClient consumer = new ApiClient(2L, "consumer", "Consumer App", "hash", "CONSUMER", 10, true, "2026-11-03");
        assertThrows(IllegalArgumentException.class, () -> sessionService.createSession(consumer));
    }

    @Test
    void testRevokeSession() {
        AdminSession session = sessionService.createSession(adminClient);
        assertTrue(sessionService.getSession(session.sessionId()).isPresent());

        sessionService.revokeSession(session.sessionId());
        assertTrue(sessionService.getSession(session.sessionId()).isEmpty());
    }

    @Test
    void testRevokeAllSessions() {
        AdminSession s1 = sessionService.createSession(adminClient);
        AdminSession s2 = sessionService.createSession(adminClient);
        assertEquals(2, sessionService.activeSessionCount());

        sessionService.revokeAllSessions();
        assertEquals(0, sessionService.activeSessionCount());
        assertTrue(sessionService.getSession(s1.sessionId()).isEmpty());
        assertTrue(sessionService.getSession(s2.sessionId()).isEmpty());
    }

    @Test
    void testNullOrBlankSessionReturnsEmpty() {
        assertTrue(sessionService.getSession(null).isEmpty());
        assertTrue(sessionService.getSession("").isEmpty());
        assertTrue(sessionService.getSession("   ").isEmpty());
        assertTrue(sessionService.getSession("non_existent").isEmpty());
    }
}
