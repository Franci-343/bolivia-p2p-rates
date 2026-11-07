package dev.joracle.rates.service;

import dev.joracle.rates.model.ApiClient;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages opaque, revocable in-memory sessions and CSRF tokens for administrative access.
 * Replaces the storage of raw API keys in browser cookies.
 */
public class AdminSessionService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    public static final Duration SESSION_DURATION = Duration.ofHours(8);
    public static final Duration IDLE_TIMEOUT = Duration.ofHours(2);
    private static final int MAX_ACTIVE_SESSIONS = 100;

    private final ConcurrentHashMap<String, AdminSession> sessions = new ConcurrentHashMap<>();

    public record AdminSession(
            String sessionId,
            long clientId,
            String clientName,
            String csrfToken,
            Instant createdAt,
            Instant expiresAt,
            Instant lastAccessedAt
    ) {
        public boolean isExpired() {
            Instant now = Instant.now();
            return now.isAfter(expiresAt) || now.isAfter(lastAccessedAt.plus(IDLE_TIMEOUT));
        }

        public AdminSession touch() {
            return new AdminSession(sessionId, clientId, clientName, csrfToken, createdAt, expiresAt, Instant.now());
        }
    }

    public AdminSession createSession(ApiClient client) {
        Objects.requireNonNull(client, "client cannot be null");
        if (!client.isAdmin()) {
            throw new IllegalArgumentException("Cannot create admin session for non-admin client");
        }

        cleanupExpiredSessions();

        byte[] sessionBytes = new byte[32];
        SECURE_RANDOM.nextBytes(sessionBytes);
        String sessionId = "adm_sess_" + HexFormat.of().formatHex(sessionBytes);

        byte[] csrfBytes = new byte[24];
        SECURE_RANDOM.nextBytes(csrfBytes);
        String csrfToken = "csrf_" + HexFormat.of().formatHex(csrfBytes);

        Instant now = Instant.now();
        Instant expiresAt = now.plus(SESSION_DURATION);

        AdminSession session = new AdminSession(sessionId, client.id(), client.clientName(), csrfToken, now, expiresAt, now);
        sessions.put(sessionId, session);
        return session;
    }

    public Optional<AdminSession> getSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }

        AdminSession session = sessions.get(sessionId);
        if (session == null) {
            return Optional.empty();
        }

        if (session.isExpired()) {
            sessions.remove(sessionId);
            return Optional.empty();
        }

        AdminSession touched = session.touch();
        sessions.put(sessionId, touched);
        return Optional.of(touched);
    }

    public void revokeSession(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    public void revokeAllSessions() {
        sessions.clear();
    }

    public int activeSessionCount() {
        cleanupExpiredSessions();
        return sessions.size();
    }

    private void cleanupExpiredSessions() {
        sessions.entrySet().removeIf(entry -> entry.getValue().isExpired());

        if (sessions.size() > MAX_ACTIVE_SESSIONS) {
            sessions.entrySet().stream()
                    .sorted((e1, e2) -> e1.getValue().createdAt().compareTo(e2.getValue().createdAt()))
                    .limit(sessions.size() - MAX_ACTIVE_SESSIONS)
                    .forEach(e -> sessions.remove(e.getKey()));
        }
    }
}
