package dev.joracle.rates.api;

import dev.joracle.rates.config.AppConfig;
import dev.joracle.rates.config.ThymeleafConfig;
import dev.joracle.rates.model.*;
import dev.joracle.rates.repository.ApiClientRepository;
import dev.joracle.rates.repository.InMemoryRateRepository;
import dev.joracle.rates.repository.RateRepository;
import dev.joracle.rates.service.ApiKeyService;
import dev.joracle.rates.service.RateAnalysisService;
import dev.joracle.rates.service.RatePollerService;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.HttpStatus;
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
 * End-to-end integration test verifying Wiki, Developer Portal, API Key generation,
 * Admin Dashboard, and REST API controllers behind a reverse proxy (like Caddy)
 * configured with PUBLIC_BASE_PATH=/bolivia-p2p-rates.
 */
class ProxyIntegrationTest {

    private AppConfig config;
    private RateRepository rateRepository;
    private ApiKeyService apiKeyService;
    private PortalController portalController;
    private AdminController adminController;
    private RateController rateController;

    private String adminApiKey;
    private String consumerApiKey;

    @BeforeEach
    void setUp() {
        // App configured with public base path /bolivia-p2p-rates
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
            @Override public <T> java.net.http.HttpResponse<T> send(java.net.http.HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler) { return null; }
            @Override public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(java.net.http.HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler) { return null; }
            @Override public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(java.net.http.HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler, java.net.http.HttpResponse.PushPromiseHandler<T> pushPromiseHandler) { return null; }
        };

        RatePollerService pollerService = new RatePollerService(
                new dev.joracle.rates.client.BinanceP2PClient(dummyHttpClient, new com.fasterxml.jackson.databind.ObjectMapper(), config),
                new RateAnalysisService(),
                rateRepository,
                config
        );

        portalController = new PortalController(rateRepository, apiKeyService, config);
        adminController = new AdminController(rateRepository, pollerService, apiKeyService, config);
        rateController = new RateController(rateRepository, pollerService, apiKeyService);
    }

    @Test
    void testDeveloperPortalAndWikiBehindProxy() {
        MockContext mock = new MockContext();
        // Caddy strips /bolivia-p2p-rates and forwards to Javalin with proxy headers
        mock.headers.put("Host", "155.181.158.36");
        mock.headers.put("X-Forwarded-Host", "155.181.158.36");
        mock.headers.put("X-Forwarded-Proto", "https");

        invokeMethod(portalController, "renderPortal", mock.createContextProxy());

        assertEquals("portal.html", mock.renderedTemplate);
        assertEquals("https://155.181.158.36/bolivia-p2p-rates", mock.renderedModel.get("baseUrl"));
        assertEquals("/bolivia-p2p-rates", mock.renderedModel.get("publicBasePath"));

        // Render template with Thymeleaf using the model produced by PortalController
        TemplateEngine engine = ThymeleafConfig.createTemplateEngine();
        org.thymeleaf.context.Context thymeleafCtx = new org.thymeleaf.context.Context();
        thymeleafCtx.setVariables(mock.renderedModel);
        String html = engine.process(mock.renderedTemplate, thymeleafCtx);

        // Verification of public URLs and scripts in the rendered HTML
        assertTrue(html.contains("href=\"/bolivia-p2p-rates/\""));
        assertTrue(html.contains("href=\"/bolivia-p2p-rates/admin\""));
        assertTrue(html.contains("hx-post=\"/bolivia-p2p-rates/portal/keys\""));
        assertTrue(html.contains("publicBasePath = \"\\/bolivia-p2p-rates\";"));
        // 4. Base URL rendered in examples includes the publicBasePath and https
        assertTrue(html.contains("<span class=\"base-url-text\">https://155.181.158.36/bolivia-p2p-rates</span>/api/v1/rates/latest"));
    }

    @Test
    void testSelfServiceApiKeyGenerationBehindProxy() {
        MockContext mock = new MockContext();
        mock.headers.put("X-Forwarded-Host", "155.181.158.36");
        mock.headers.put("X-Forwarded-Proto", "https");
        mock.headers.put("X-Forwarded-For", "200.87.100.55");
        mock.formParams.put("clientName", "MiBotDeTasas");

        invokeMethod(portalController, "handlePublicCreateKey", mock.createContextProxy());

        assertEquals("fragments/public-key-result.html", mock.renderedTemplate);
        assertEquals(true, mock.renderedModel.get("success"));
        assertEquals("https://155.181.158.36/bolivia-p2p-rates", mock.renderedModel.get("baseUrl"));
        assertEquals("/bolivia-p2p-rates", mock.renderedModel.get("publicBasePath"));

        ClientCreationResult creation = (ClientCreationResult) mock.renderedModel.get("creationResult");
        assertNotNull(creation);
        assertEquals("MiBotDeTasas", creation.client().clientName());

        // Render fragment
        TemplateEngine engine = ThymeleafConfig.createTemplateEngine();
        org.thymeleaf.context.Context thymeleafCtx = new org.thymeleaf.context.Context();
        thymeleafCtx.setVariables(mock.renderedModel);
        String fragmentHtml = engine.process(mock.renderedTemplate, thymeleafCtx);

        assertTrue(fragmentHtml.contains("¡API Key generada con éxito!"));
        assertTrue(fragmentHtml.contains("MiBotDeTasas"));
        assertTrue(fragmentHtml.contains("<span class=\"base-url-text\">https://155.181.158.36/bolivia-p2p-rates</span>/api/v1/rates/latest"));
    }

    @Test
    void testAdminLoginRedirectAndCookiePathBehindProxy() {
        // 1. GET /admin without session renders login form
        MockContext getAdminMock = new MockContext();
        getAdminMock.headers.put("X-Forwarded-Host", "155.181.158.36");
        getAdminMock.headers.put("X-Forwarded-Proto", "https");

        invokeMethod(adminController, "renderDashboard", getAdminMock.createContextProxy());
        assertEquals("admin.html", getAdminMock.renderedTemplate);
        assertEquals("/bolivia-p2p-rates", getAdminMock.renderedModel.get("publicBasePath"));

        TemplateEngine engine = ThymeleafConfig.createTemplateEngine();
        org.thymeleaf.context.Context thymeleafCtx = new org.thymeleaf.context.Context();
        thymeleafCtx.setVariables(getAdminMock.renderedModel);
        String loginHtml = engine.process("admin.html", thymeleafCtx);
        assertTrue(loginHtml.contains("action=\"/bolivia-p2p-rates/admin/login\""));

        // 2. POST /admin/login with valid key
        MockContext loginMock = new MockContext();
        loginMock.formParams.put("adminKey", adminApiKey);

        invokeMethod(adminController, "handleLogin", loginMock.createContextProxy());

        // Location must be prefixed with /bolivia-p2p-rates
        assertEquals("/bolivia-p2p-rates/admin", loginMock.redirectedTo);

        // Cookie path must be scoped to /bolivia-p2p-rates
        assertFalse(loginMock.cookiesSet.isEmpty());
        Cookie setCookie = loginMock.cookiesSet.get(0);
        assertEquals("admin_key", setCookie.getName());
        assertEquals("/bolivia-p2p-rates", setCookie.getPath());

        // 3. Render dashboard with session active
        MockContext dashMock = new MockContext();
        dashMock.cookies.put("admin_key", adminApiKey);
        invokeMethod(adminController, "renderDashboard", dashMock.createContextProxy());

        assertEquals("admin.html", dashMock.renderedTemplate);
        assertEquals(true, dashMock.renderedModel.get("isAdminLoggedIn"));
        assertEquals("/bolivia-p2p-rates", dashMock.renderedModel.get("publicBasePath"));

        org.thymeleaf.context.Context dashThymeleafCtx = new org.thymeleaf.context.Context();
        dashThymeleafCtx.setVariables(dashMock.renderedModel);
        String dashHtml = engine.process("admin.html", dashThymeleafCtx);

        assertTrue(dashHtml.contains("action=\"/bolivia-p2p-rates/admin/logout\""));
        assertTrue(dashHtml.contains("href=\"/bolivia-p2p-rates/api/v1/rates/latest\""));
        assertTrue(dashHtml.contains("hx-get=\"/bolivia-p2p-rates/admin/partials/rates\""));
        assertTrue(dashHtml.contains("hx-post=\"/bolivia-p2p-rates/admin/refresh\""));
        assertTrue(dashHtml.contains("hx-post=\"/bolivia-p2p-rates/admin/clients\""));

        // 4. POST /admin/logout removes scoped cookie and redirects
        MockContext logoutMock = new MockContext();
        invokeMethod(adminController, "handleLogout", logoutMock.createContextProxy());
        assertEquals("/bolivia-p2p-rates/admin", logoutMock.redirectedTo);
        assertTrue(logoutMock.cookiesRemoved.contains("/bolivia-p2p-rates"));
    }

    @Test
    void testRestApiBehindProxy() {
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
        final Map<String, String> cookies = new HashMap<>();
        final Map<String, Object> attributes = new HashMap<>();

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
            if ("status".equals(name)) {
                this.status = (HttpStatus) args[0];
                return proxy;
            }
            if ("json".equals(name)) {
                this.jsonBody = args[0];
                return proxy;
            }
            if ("result".equals(name)) {
                this.resultBody = (String) args[0];
                return proxy;
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
                return "http";
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
