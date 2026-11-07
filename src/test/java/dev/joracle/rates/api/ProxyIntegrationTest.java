package dev.joracle.rates.api;

import dev.joracle.rates.config.AppConfig;
import dev.joracle.rates.config.ThymeleafConfig;
import dev.joracle.rates.model.*;
import dev.joracle.rates.repository.ApiClientRepository;
import dev.joracle.rates.repository.InMemoryRateRepository;
import dev.joracle.rates.repository.RateRepository;
import dev.joracle.rates.service.AdminSessionService;
import dev.joracle.rates.service.AdminSessionService.AdminSession;
import dev.joracle.rates.service.ApiKeyService;
import dev.joracle.rates.service.RateAnalysisService;
import dev.joracle.rates.service.RatePollerService;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.HttpStatus;
import io.javalin.http.SameSite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test verifying Wiki, Developer Portal, API Key generation security,
 * Admin Dashboard session security (opaque tokens, cookie attributes, CSRF),
 * and REST API controllers behind a reverse proxy (like Caddy) with PUBLIC_BASE_PATH=/bolivia-p2p-rates.
 */
class ProxyIntegrationTest {

    private AppConfig config;
    private RateRepository rateRepository;
    private ApiKeyService apiKeyService;
    private AdminSessionService sessionService;
    private PortalController portalController;
    private AdminController adminController;
    private RateController rateController;

    private String adminApiKey;
    private String consumerApiKey;

    @BeforeEach
    void setUp() {
        config = new AppConfig(8080, 600, 60, 10, "BOB", "USDT", "database/rates.db", "/bolivia-p2p-rates");

        rateRepository = new InMemoryRateRepository();
        MarketSummary buyMarket = new MarketSummary(
                TradeType.BUY, "BOB", "USDT",
                new BigDecimal("14.85"), new BigDecimal("14.85"), new BigDecimal("14.85"),
                new BigDecimal("14.80"), new BigDecimal("14.90"),
                10, 10, 0,
                List.of(new BankRateSummary("Banco Union", new BigDecimal("14.85"), new BigDecimal("14.80"), new BigDecimal("14.90"), 5)),
                List.of()
        );
        MarketSummary sellMarket = new MarketSummary(
                TradeType.SELL, "BOB", "USDT",
                new BigDecimal("14.92"), new BigDecimal("14.92"), new BigDecimal("14.92"),
                new BigDecimal("14.88"), new BigDecimal("14.95"),
                10, 10, 0,
                List.of(),
                List.of()
        );
        RateSnapshot initialSnapshot = new RateSnapshot(1L, Instant.now(), 250L, false, "OK", buyMarket, sellMarket);
        rateRepository.save(initialSnapshot);

        Map<Long, ApiClient> clientStorage = new HashMap<>();
        ApiClientRepository dummyClientRepo = new ApiClientRepository() {
            private long nextId = 1;
            @Override
            public ApiClient save(String clientId, String clientName, String apiKeyHash, String role, int rateLimitRpm) {
                ApiClient c = new ApiClient(nextId++, clientId, clientName, apiKeyHash, role, rateLimitRpm, true, "2026-11-03 12:00:00");
                clientStorage.put(c.id(), c);
                return c;
            }
            @Override
            public Optional<ApiClient> findByApiKeyHash(String apiKeyHash) {
                return clientStorage.values().stream().filter(c -> c.apiKeyHash().equals(apiKeyHash)).findFirst();
            }
            @Override
            public Optional<ApiClient> findByClientId(String clientId) {
                return clientStorage.values().stream().filter(c -> c.clientId().equals(clientId)).findFirst();
            }
            @Override
            public List<ApiClient> findAll() { return new ArrayList<>(clientStorage.values()); }
            @Override
            public boolean toggleActive(long id) {
                ApiClient c = clientStorage.get(id);
                if (c != null) {
                    clientStorage.put(id, new ApiClient(c.id(), c.clientId(), c.clientName(), c.apiKeyHash(), c.role(), c.rateLimitRpm(), !c.active(), c.createdAt()));
                    return true;
                }
                return false;
            }
            @Override
            public boolean delete(long id) { return clientStorage.remove(id) != null; }
        };

        apiKeyService = new ApiKeyService(dummyClientRepo);
        adminApiKey = apiKeyService.createClient("Admin Test", "ADMIN", 1000).plainApiKey();
        consumerApiKey = apiKeyService.createClient("Consumer Test", "CONSUMER", 60).plainApiKey();

        java.net.http.HttpClient dummyHttpClient = new java.net.http.HttpClient() {
            @Override public Optional<java.net.CookieHandler> cookieHandler() { return Optional.empty(); }
            @Override public Optional<java.time.Duration> connectTimeout() { return Optional.empty(); }
            @Override public Redirect followRedirects() { return Redirect.NEVER; }
            @Override public Optional<java.net.ProxySelector> proxy() { return Optional.empty(); }
            @Override public javax.net.ssl.SSLContext sslContext() { return null; }
            @Override public javax.net.ssl.SSLParameters sslParameters() { return null; }
            @Override public Optional<java.net.Authenticator> authenticator() { return Optional.empty(); }
            @Override public Version version() { return Version.HTTP_1_1; }
            @Override public Optional<java.util.concurrent.Executor> executor() { return Optional.empty(); }
            @Override
            @SuppressWarnings("unchecked")
            public <T> java.net.http.HttpResponse<T> send(java.net.http.HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler) {
                String json = "{\"code\":\"000000\",\"success\":true,\"message\":null,\"data\":[{\"adv\":{\"price\":\"14.85\",\"surplusAmount\":\"500.0\",\"minSingleTransAmount\":\"100\",\"maxSingleTransAmount\":\"5000\"},\"advertiser\":{\"nickName\":\"MockTrader\",\"monthOrderCount\":150,\"monthFinishRate\":0.99}}]}";
                return new java.net.http.HttpResponse<T>() {
                    @Override public int statusCode() { return 200; }
                    @Override public java.net.http.HttpRequest request() { return request; }
                    @Override public Optional<java.net.http.HttpResponse<T>> previousResponse() { return Optional.empty(); }
                    @Override public java.net.http.HttpHeaders headers() { return java.net.http.HttpHeaders.of(Map.of(), (k, v) -> true); }
                    @Override public T body() { return (T) json; }
                    @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
                    @Override public java.net.URI uri() { return request.uri(); }
                    @Override public java.net.http.HttpClient.Version version() { return java.net.http.HttpClient.Version.HTTP_1_1; }
                };
            }
            @Override public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(java.net.http.HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler) { return null; }
            @Override public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(java.net.http.HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler, java.net.http.HttpResponse.PushPromiseHandler<T> pushPromiseHandler) { return null; }
        };

        RatePollerService pollerService = new RatePollerService(
                new dev.joracle.rates.client.BinanceP2PClient(dummyHttpClient, new com.fasterxml.jackson.databind.ObjectMapper(), config),
                new RateAnalysisService(),
                rateRepository,
                config
        );

        sessionService = new AdminSessionService();
        portalController = new PortalController(rateRepository, apiKeyService, config);
        adminController = new AdminController(rateRepository, pollerService, apiKeyService, config, sessionService);
        rateController = new RateController(rateRepository, pollerService, apiKeyService);
    }

    @Test
    void testDeveloperPortalAndWikiBehindProxy() {
        MockContext mock = new MockContext();
        mock.headers.put("Host", "155.181.158.36");
        mock.headers.put("X-Forwarded-Host", "155.181.158.36");
        mock.headers.put("X-Forwarded-Proto", "https");

        invokeMethod(portalController, "renderPortal", mock.createContextProxy());

        assertEquals("portal.html", mock.renderedTemplate);
        assertEquals("https://155.181.158.36/bolivia-p2p-rates", mock.renderedModel.get("baseUrl"));
        assertEquals("/bolivia-p2p-rates", mock.renderedModel.get("publicBasePath"));

        TemplateEngine engine = ThymeleafConfig.createTemplateEngine();
        org.thymeleaf.context.Context thymeleafCtx = new org.thymeleaf.context.Context();
        thymeleafCtx.setVariables(mock.renderedModel);
        String html = engine.process(mock.renderedTemplate, thymeleafCtx);

        assertTrue(html.contains("href=\"/bolivia-p2p-rates/\""));
        assertFalse(html.contains("href=\"/bolivia-p2p-rates/admin\""), "Admin link must be hidden from public portal nav");
        assertTrue(html.contains("hx-post=\"/bolivia-p2p-rates/portal/keys\""));
        assertTrue(html.contains("publicBasePath = \"\\/bolivia-p2p-rates\";"));
        assertTrue(html.contains("<span class=\"base-url-text\">https://155.181.158.36/bolivia-p2p-rates</span>/api/v1/rates/latest"));
        assertTrue(html.contains("PRECIO DE COMPRA"));
        assertTrue(html.contains("PRECIO DE VENTA"));
        assertTrue(html.contains("14.85"));
        assertTrue(html.contains("14.92"));
    }

    @Test
    void testPublicRatesCardHeroFragment() {
        MockContext mock = new MockContext();
        mock.headers.put("Host", "155.181.158.36");
        invokeMethod(portalController, "renderPublicRatesCard", mock.createContextProxy());

        assertEquals("fragments/public-rates-hero.html", mock.renderedTemplate);
        assertEquals("/bolivia-p2p-rates", mock.renderedModel.get("publicBasePath"));
        assertEquals("14.85", mock.renderedModel.get("buyPrice"));
        assertEquals("14.92", mock.renderedModel.get("sellPrice"));

        TemplateEngine engine = ThymeleafConfig.createTemplateEngine();
        org.thymeleaf.context.Context thymeleafCtx = new org.thymeleaf.context.Context();
        thymeleafCtx.setVariables(mock.renderedModel);
        String html = engine.process(mock.renderedTemplate, thymeleafCtx);

        assertTrue(html.contains("PRECIO DE COMPRA"));
        assertTrue(html.contains("PRECIO DE VENTA"));
        assertTrue(html.contains("14.85"));
        assertTrue(html.contains("14.92"));
        assertTrue(html.contains("hx-get=\"/bolivia-p2p-rates/portal/rates-card\""));
    }

    @Test
    void testSelfServiceApiKeyGenerationBehindProxy() {
        MockContext mock = new MockContext();
        mock.headers.put("Host", "155.181.158.36");
        mock.headers.put("X-Forwarded-Host", "155.181.158.36");
        mock.headers.put("X-Forwarded-Proto", "https");
        mock.headers.put("X-Forwarded-For", "200.87.100.55");
        mock.formParams.put("clientName", "TelegramAlertBot");

        invokeMethod(portalController, "handlePublicCreateKey", mock.createContextProxy());

        assertEquals("fragments/public-key-result.html", mock.renderedTemplate);
        assertEquals(true, mock.renderedModel.get("success"));
        assertEquals("https://155.181.158.36/bolivia-p2p-rates", mock.renderedModel.get("baseUrl"));
        assertEquals("/bolivia-p2p-rates", mock.renderedModel.get("publicBasePath"));

        ClientCreationResult creation = (ClientCreationResult) mock.renderedModel.get("creationResult");
        assertNotNull(creation);
        assertEquals("TelegramAlertBot", creation.client().clientName());
        assertTrue(creation.plainApiKey().startsWith("bolivia_live_"));

        TemplateEngine engine = ThymeleafConfig.createTemplateEngine();
        org.thymeleaf.context.Context thymeleafCtx = new org.thymeleaf.context.Context();
        thymeleafCtx.setVariables(mock.renderedModel);
        String fragmentHtml = engine.process(mock.renderedTemplate, thymeleafCtx);

        assertTrue(fragmentHtml.contains("¡API Key generada con éxito!"));
        assertTrue(fragmentHtml.contains("TelegramAlertBot"));
        assertTrue(fragmentHtml.contains("<span class=\"base-url-text\">https://155.181.158.36/bolivia-p2p-rates</span>/api/v1/rates/latest"));
    }

    @Test
    void testPortalSecurityValidationsAndSpoofingDefense() {
        // 1. Invalid name with special characters (XSS/injection attempt) rejected
        MockContext badNameMock = new MockContext();
        badNameMock.headers.put("X-Forwarded-Host", "155.181.158.36");
        badNameMock.headers.put("X-Forwarded-For", "200.87.100.60");
        badNameMock.formParams.put("clientName", "<script>alert(1)</script>");
        invokeMethod(portalController, "handlePublicCreateKey", badNameMock.createContextProxy());
        assertEquals(false, badNameMock.renderedModel.get("success"));
        assertTrue(((String) badNameMock.renderedModel.get("errorMessage")).contains("solo puede contener letras, números"));

        // 2. Name too short rejected
        MockContext shortNameMock = new MockContext();
        shortNameMock.headers.put("X-Forwarded-Host", "155.181.158.36");
        shortNameMock.headers.put("X-Forwarded-For", "200.87.100.61");
        shortNameMock.formParams.put("clientName", "a");
        invokeMethod(portalController, "handlePublicCreateKey", shortNameMock.createContextProxy());
        assertEquals(false, shortNameMock.renderedModel.get("success"));
        assertTrue(((String) shortNameMock.renderedModel.get("errorMessage")).contains("entre 2 y 40 caracteres"));

        // 3. Cross-origin request from malicious site rejected
        MockContext corsMock = new MockContext();
        corsMock.headers.put("Host", "155.181.158.36");
        corsMock.headers.put("Origin", "https://malicious-site.com");
        corsMock.formParams.put("clientName", "ValidName");
        invokeMethod(portalController, "handlePublicCreateKey", corsMock.createContextProxy());
        assertEquals(false, corsMock.renderedModel.get("success"));
        assertTrue(((String) corsMock.renderedModel.get("errorMessage")).contains("no autorizado"));

        // 4. IP Spoofing defense: uses the last IP in X-Forwarded-For (from trusted proxy)
        // If attacker sends X-Forwarded-For: 1.1.1.1, Caddy appends the real remote IP: 1.1.1.1, 200.87.100.70
        MockContext spoofMock1 = new MockContext();
        spoofMock1.headers.put("Host", "155.181.158.36");
        spoofMock1.headers.put("X-Forwarded-For", "1.1.1.1, 200.87.100.70");
        spoofMock1.formParams.put("clientName", "ValidName1");
        invokeMethod(portalController, "handlePublicCreateKey", spoofMock1.createContextProxy());
        assertEquals(true, spoofMock1.renderedModel.get("success"));

        // Second request right away from same real IP 200.87.100.70 (even with different spoofed prefix) is caught by burst cooldown
        MockContext spoofMock2 = new MockContext();
        spoofMock2.headers.put("Host", "155.181.158.36");
        spoofMock2.headers.put("X-Forwarded-For", "2.2.2.2, 200.87.100.70");
        spoofMock2.formParams.put("clientName", "ValidName2");
        invokeMethod(portalController, "handlePublicCreateKey", spoofMock2.createContextProxy());
        assertEquals(false, spoofMock2.renderedModel.get("success"));
        assertTrue(((String) spoofMock2.renderedModel.get("errorMessage")).contains("espera unos segundos"));
    }

    @Test
    void testAdminLoginCookieAttributesInProductionHttps() {
        MockContext loginMock = new MockContext();
        loginMock.headers.put("Host", "155.181.158.36");
        loginMock.headers.put("Origin", "https://155.181.158.36");
        loginMock.headers.put("X-Forwarded-Host", "155.181.158.36");
        loginMock.headers.put("X-Forwarded-Proto", "https");
        loginMock.formParams.put("adminKey", adminApiKey);

        invokeMethod(adminController, "handleLogin", loginMock.createContextProxy());

        assertEquals("/bolivia-p2p-rates/admin", loginMock.redirectedTo);
        assertFalse(loginMock.cookiesSet.isEmpty());

        Cookie sessionCookie = loginMock.cookiesSet.get(0);
        assertEquals(AdminController.COOKIE_ADMIN_SESSION, sessionCookie.getName());

        // 1. Opaque session ID: starts with adm_sess_, DOES NOT contain plain API key
        assertTrue(sessionCookie.getValue().startsWith("adm_sess_"));
        assertNotEquals(adminApiKey, sessionCookie.getValue());

        // 2. Cookie security attributes for HTTPS
        assertTrue(sessionCookie.getSecure(), "Secure must be true under HTTPS");
        assertTrue(sessionCookie.isHttpOnly(), "HttpOnly must be true");
        assertEquals("/bolivia-p2p-rates", sessionCookie.getPath());
        assertEquals(SameSite.LAX, sessionCookie.getSameSite());
        assertEquals(8 * 3600, sessionCookie.getMaxAge());

        // Verify session was registered in sessionService
        Optional<AdminSession> sessionOpt = sessionService.getSession(sessionCookie.getValue());
        assertTrue(sessionOpt.isPresent());
        assertEquals("Admin Test", sessionOpt.get().clientName());
        assertNotNull(sessionOpt.get().csrfToken());
    }

    @Test
    void testAdminLoginCookieAttributesInLocalDevHttp() {
        MockContext localMock = new MockContext();
        localMock.headers.put("Host", "localhost:8080");
        localMock.headers.put("Origin", "http://localhost:8080");
        localMock.scheme = "http";
        localMock.formParams.put("adminKey", adminApiKey);

        invokeMethod(adminController, "handleLogin", localMock.createContextProxy());

        assertEquals("/bolivia-p2p-rates/admin", localMock.redirectedTo);
        assertFalse(localMock.cookiesSet.isEmpty());

        Cookie cookie = localMock.cookiesSet.get(0);
        // Secure should be false in local HTTP development so browser accepts cookie
        assertFalse(cookie.getSecure(), "Secure should be false over local HTTP");
        assertTrue(cookie.isHttpOnly());
        assertEquals(SameSite.LAX, cookie.getSameSite());
    }

    @Test
    void testAdminDashboardRenderingAndHtmxCsrfHeaders() {
        // Create an active session
        ApiClient adminClient = apiKeyService.authenticate(adminApiKey).client();
        AdminSession session = sessionService.createSession(adminClient);

        MockContext dashMock = new MockContext();
        dashMock.headers.put("Host", "155.181.158.36");
        dashMock.cookies.put(AdminController.COOKIE_ADMIN_SESSION, session.sessionId());

        invokeMethod(adminController, "renderDashboard", dashMock.createContextProxy());

        assertEquals("admin.html", dashMock.renderedTemplate);
        assertEquals(true, dashMock.renderedModel.get("isAdminLoggedIn"));
        assertEquals(session.csrfToken(), dashMock.renderedModel.get("csrfToken"));

        TemplateEngine engine = ThymeleafConfig.createTemplateEngine();
        org.thymeleaf.context.Context thymeleafCtx = new org.thymeleaf.context.Context();
        thymeleafCtx.setVariables(dashMock.renderedModel);
        String dashHtml = engine.process("admin.html", thymeleafCtx);

        // Verify HTMX hx-headers contains X-CSRF-Token
        assertTrue(dashHtml.contains("hx-headers="));
        assertTrue(dashHtml.contains("X-CSRF-Token"));
        assertTrue(dashHtml.contains(session.csrfToken()));

        // Verify Logout form contains hidden csrfToken input
        assertTrue(dashHtml.contains("name=\"csrfToken\""));
    }

    @Test
    void testAdminCsrfProtectionAndOriginValidation() {
        ApiClient adminClient = apiKeyService.authenticate(adminApiKey).client();
        AdminSession session = sessionService.createSession(adminClient);

        // 1. Refresh without CSRF Token -> 403 Forbidden
        MockContext noCsrfMock = new MockContext();
        noCsrfMock.headers.put("Host", "155.181.158.36");
        noCsrfMock.cookies.put(AdminController.COOKIE_ADMIN_SESSION, session.sessionId());
        invokeMethod(adminController, "handleRefreshRates", noCsrfMock.createContextProxy());
        assertEquals(HttpStatus.FORBIDDEN, noCsrfMock.status);

        // 2. Refresh with invalid CSRF Token -> 403 Forbidden
        MockContext badCsrfMock = new MockContext();
        badCsrfMock.headers.put("Host", "155.181.158.36");
        badCsrfMock.headers.put("X-CSRF-Token", "invalid_csrf_token");
        badCsrfMock.cookies.put(AdminController.COOKIE_ADMIN_SESSION, session.sessionId());
        invokeMethod(adminController, "handleRefreshRates", badCsrfMock.createContextProxy());
        assertEquals(HttpStatus.FORBIDDEN, badCsrfMock.status);

        // 3. Refresh with foreign Origin -> 403 Forbidden
        MockContext badOriginMock = new MockContext();
        badOriginMock.headers.put("Host", "155.181.158.36");
        badOriginMock.headers.put("Origin", "https://hacker.com");
        badOriginMock.headers.put("X-CSRF-Token", session.csrfToken());
        badOriginMock.cookies.put(AdminController.COOKIE_ADMIN_SESSION, session.sessionId());
        invokeMethod(adminController, "handleRefreshRates", badOriginMock.createContextProxy());
        assertEquals(HttpStatus.FORBIDDEN, badOriginMock.status);

        // 4. Refresh with valid CSRF Token and same origin -> SUCCEEDS (renders fragment)
        MockContext okCsrfMock = new MockContext();
        okCsrfMock.headers.put("Host", "155.181.158.36");
        okCsrfMock.headers.put("Origin", "https://155.181.158.36");
        okCsrfMock.headers.put("X-CSRF-Token", session.csrfToken());
        okCsrfMock.cookies.put(AdminController.COOKIE_ADMIN_SESSION, session.sessionId());
        invokeMethod(adminController, "handleRefreshRates", okCsrfMock.createContextProxy());
        assertEquals("fragments/rates-card.html", okCsrfMock.renderedTemplate);

        // 5. Automated programmatic API call with X-API-Key passes without browser CSRF token
        MockContext apiMock = new MockContext();
        apiMock.headers.put("X-API-Key", adminApiKey);
        invokeMethod(adminController, "handleRefreshRates", apiMock.createContextProxy());
        assertEquals("fragments/rates-card.html", apiMock.renderedTemplate);
    }

    @Test
    void testAdminLogoutSessionRevocation() {
        ApiClient adminClient = apiKeyService.authenticate(adminApiKey).client();
        AdminSession session = sessionService.createSession(adminClient);
        String sessId = session.sessionId();

        assertTrue(sessionService.getSession(sessId).isPresent());

        MockContext logoutMock = new MockContext();
        logoutMock.headers.put("Host", "155.181.158.36");
        logoutMock.cookies.put(AdminController.COOKIE_ADMIN_SESSION, sessId);
        invokeMethod(adminController, "handleLogout", logoutMock.createContextProxy());

        assertEquals("/bolivia-p2p-rates/admin", logoutMock.redirectedTo);
        // Session must be revoked immediately on server
        assertTrue(sessionService.getSession(sessId).isEmpty());

        // Subsequent dashboard access with the old cookie must show logged out view
        MockContext reAccessMock = new MockContext();
        reAccessMock.headers.put("Host", "155.181.158.36");
        reAccessMock.cookies.put(AdminController.COOKIE_ADMIN_SESSION, sessId);
        invokeMethod(adminController, "renderDashboard", reAccessMock.createContextProxy());
        assertEquals(false, reAccessMock.renderedModel.get("isAdminLoggedIn"));
    }

    @Test
    void testInternalRestEndpointsBehindProxy() {
        // 1. Health check
        MockContext healthMock = new MockContext();
        invokeMethod(rateController, "healthCheck", healthMock.createContextProxy());
        assertEquals(HttpStatus.OK, healthMock.status);
        assertNotNull(healthMock.jsonBody);
        Map<?, ?> healthMap = (Map<?, ?>) healthMock.jsonBody;
        assertEquals("UP", healthMap.get("status"));

        // 2. Status
        MockContext statusMock = new MockContext();
        invokeMethod(rateController, "getStatus", statusMock.createContextProxy());
        assertEquals(HttpStatus.OK, statusMock.status);
        assertNotNull(statusMock.jsonBody);
        Map<?, ?> statusMap = (Map<?, ?>) statusMock.jsonBody;
        assertEquals("UP", statusMap.get("status"));
        assertEquals(1L, statusMap.get("cycleId"));

        // 3. Latest rates
        MockContext ratesMock = new MockContext();
        invokeMethod(rateController, "getLatestRates", ratesMock.createContextProxy());
        assertNotNull(ratesMock.jsonBody);
        RateSnapshot snap = (RateSnapshot) ratesMock.jsonBody;
        assertEquals(new BigDecimal("14.85"), snap.buyMarket().referencePrice());
        assertEquals(new BigDecimal("14.92"), snap.sellMarket().referencePrice());

        // 4. Buy market
        MockContext buyMock = new MockContext();
        invokeMethod(rateController, "getBuyRates", buyMock.createContextProxy());
        assertNotNull(buyMock.jsonBody);
        MarketSummary buy = (MarketSummary) buyMock.jsonBody;
        assertEquals(TradeType.BUY, buy.tradeType());
        assertEquals(new BigDecimal("14.85"), buy.referencePrice());

        // 5. Sell market
        MockContext sellMock = new MockContext();
        invokeMethod(rateController, "getSellRates", sellMock.createContextProxy());
        assertNotNull(sellMock.jsonBody);
        MarketSummary sell = (MarketSummary) sellMock.jsonBody;
        assertEquals(TradeType.SELL, sell.tradeType());
        assertEquals(new BigDecimal("14.92"), sell.referencePrice());
    }

    private void invokeMethod(Object target, String methodName, Context ctx) {
        try {
            Method m = target.getClass().getDeclaredMethod(methodName, Context.class);
            m.setAccessible(true);
            m.invoke(target, ctx);
        } catch (Exception e) {
            throw new RuntimeException("Error invoking " + methodName + " on " + target.getClass().getSimpleName(), e);
        }
    }

    private static class MockContext implements InvocationHandler {
        final Map<String, String> headers = new HashMap<>();
        final Map<String, String> formParams = new HashMap<>();
        final Map<String, String> queryParams = new HashMap<>();
        final Map<String, String> pathParams = new HashMap<>();
        final Map<String, String> cookies = new HashMap<>();
        final Map<String, Object> attributes = new HashMap<>();

        String scheme;
        String redirectedTo;
        final List<Cookie> cookiesSet = new ArrayList<>();
        final List<String> cookiesRemoved = new ArrayList<>();
        String renderedTemplate;
        Map<String, Object> renderedModel;
        HttpStatus status;
        Object jsonBody;
        String resultBody;

        Context createContextProxy() {
            return (Context) Proxy.newProxyInstance(
                    Context.class.getClassLoader(),
                    new Class<?>[]{Context.class},
                    this
            );
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("header".equals(name) && args.length == 1) {
                return headers.get(args[0]);
            }
            if ("header".equals(name) && args.length == 2) {
                headers.put((String) args[0], (String) args[1]);
                return proxy;
            }
            if ("formParam".equals(name)) {
                return formParams.get(args[0]);
            }
            if ("queryParam".equals(name)) {
                return queryParams.get(args[0]);
            }
            if ("pathParam".equals(name)) {
                return pathParams.get(args[0]);
            }
            if ("cookie".equals(name) && args.length == 1 && args[0] instanceof String s) {
                return cookies.get(s);
            }
            if ("cookie".equals(name) && args.length == 1 && args[0] instanceof Cookie c) {
                cookiesSet.add(c);
                cookies.put(c.getName(), c.getValue());
                return proxy;
            }
            if ("removeCookie".equals(name)) {
                if (args.length >= 2) {
                    cookiesRemoved.add((String) args[1]);
                }
                return proxy;
            }
            if ("redirect".equals(name)) {
                this.redirectedTo = (String) args[0];
                return proxy;
            }
            if ("render".equals(name) && args.length >= 2) {
                this.renderedTemplate = (String) args[0];
                this.renderedModel = (Map<String, Object>) args[1];
                return proxy;
            }
            if ("status".equals(name) && args != null && args.length == 1) {
                this.status = (HttpStatus) args[0];
                return proxy;
            }
            if ("status".equals(name) && (args == null || args.length == 0)) {
                return this.status;
            }
            if ("json".equals(name)) {
                this.jsonBody = args[0];
                return proxy;
            }
            if ("result".equals(name) && args != null && args.length == 1) {
                this.resultBody = (String) args[0];
                return proxy;
            }
            if ("result".equals(name) && (args == null || args.length == 0)) {
                return this.resultBody;
            }
            if ("attribute".equals(name) && args.length == 1) {
                return attributes.get(args[0]);
            }
            if ("attribute".equals(name) && args.length == 2) {
                attributes.put((String) args[0], args[1]);
                return proxy;
            }
            if ("ip".equals(name)) {
                return "127.0.0.1";
            }
            if ("scheme".equals(name)) {
                return scheme != null ? scheme : "http";
            }
            if ("skipRemainingHandlers".equals(name)) {
                return proxy;
            }
            if ("toString".equals(name)) {
                return "MockContextProxy";
            }
            return null;
        }
    }
}
