package dev.joracle.rates.config;

import dev.joracle.rates.model.ApiClient;
import dev.joracle.rates.model.BankRateSummary;
import dev.joracle.rates.model.ClientCreationResult;
import dev.joracle.rates.model.MarketSummary;
import dev.joracle.rates.model.RateSnapshot;
import dev.joracle.rates.model.TradeType;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ThymeleafConfigTest {

    @Test
    void testRenderAdminTemplateWithoutSuffix() {
        TemplateEngine engine = ThymeleafConfig.createTemplateEngine();
        Context context = new Context();
        Map<String, Object> vars = new HashMap<>();
        vars.put("isAdminLoggedIn", false);
        context.setVariables(vars);

        assertDoesNotThrow(() -> {
            String rendered = engine.process("admin", context);
            assertNotNull(rendered);
            assertTrue(rendered.contains("Bolivia P2P"));
        });
    }

    @Test
    void testRenderAdminTemplateWithHtmlExtension() {
        TemplateEngine engine = ThymeleafConfig.createTemplateEngine();
        Context context = new Context();
        Map<String, Object> vars = new HashMap<>();
        vars.put("isAdminLoggedIn", true);
        vars.put("clients", List.of());
        vars.put("snapshot", null);
        vars.put("refreshMessage", "Prueba");
        context.setVariables(vars);

        assertDoesNotThrow(() -> {
            String rendered = engine.process("admin.html", context);
            assertNotNull(rendered);
            assertTrue(rendered.contains("Bolivia P2P"));
        });
    }

    @Test
    void testRenderRatesCardFragment() {
        TemplateEngine engine = ThymeleafConfig.createTemplateEngine();
        Context context = new Context();
        Map<String, Object> vars = new HashMap<>();
        vars.put("snapshot", null);
        vars.put("refreshMessage", "Sin datos disponibles todavía");
        context.setVariables(vars);

        assertDoesNotThrow(() -> {
            String rendered = engine.process("fragments/rates-card.html", context);
            assertNotNull(rendered);
            assertTrue(rendered.contains("Sin datos disponibles todavía"));
        });
    }

    @Test
    void testRenderClientsSectionFragment() {
        TemplateEngine engine = ThymeleafConfig.createTemplateEngine();
        Context context = new Context();
        ApiClient client = new ApiClient(1L, "client_test", "Test Client", "hash", "CONSUMER", 60, true, "2026-11-03 14:00:00");
        ClientCreationResult creation = new ClientCreationResult(client, "bolivia_live_secret123");

        Map<String, Object> vars = new HashMap<>();
        vars.put("clients", List.of(client));
        vars.put("newClientResult", creation);
        context.setVariables(vars);

        assertDoesNotThrow(() -> {
            String rendered = engine.process("fragments/clients-section.html", context);
            assertNotNull(rendered);
            assertTrue(rendered.contains("bolivia_live_secret123"));
            assertTrue(rendered.contains("Test Client"));
        });
    }

    @Test
    void testRenderPortalTemplate() {
        TemplateEngine engine = ThymeleafConfig.createTemplateEngine();
        Context context = new Context();

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
        RateSnapshot mockSnapshot = new RateSnapshot(1L, Instant.now(), 1200L, false, "OK", buyMarket, sellMarket);

        Map<String, Object> vars = new HashMap<>();
        vars.put("snapshot", mockSnapshot);
        vars.put("defaultDemoKey", "bolivia_live_demo12345");
        vars.put("baseUrl", "http://155.181.158.36:8080");
        vars.put("serverPort", 8080);
        context.setVariables(vars);

        assertDoesNotThrow(() -> {
            String rendered = engine.process("portal.html", context);
            assertNotNull(rendered);
            assertTrue(rendered.contains("Portal de Desarrolladores"));
            assertTrue(rendered.contains("14.85"));
            assertTrue(rendered.contains("bolivia_live_demo12345"));
        });
    }

    @Test
    void testRenderPublicKeyResultFragmentSuccess() {
        TemplateEngine engine = ThymeleafConfig.createTemplateEngine();
        Context context = new Context();
        ApiClient client = new ApiClient(2L, "client_mybot", "My Telegram Bot", "hash", "CONSUMER", 10, true, "2026-11-03 14:00:00");
        ClientCreationResult creation = new ClientCreationResult(client, "bolivia_live_test_pub_999");

        Map<String, Object> vars = new HashMap<>();
        vars.put("success", true);
        vars.put("errorMessage", null);
        vars.put("creationResult", creation);
        vars.put("baseUrl", "http://155.181.158.36:8080");
        vars.put("serverPort", 8080);
        context.setVariables(vars);

        assertDoesNotThrow(() -> {
            String rendered = engine.process("fragments/public-key-result.html", context);
            assertNotNull(rendered);
            assertTrue(rendered.contains("bolivia_live_test_pub_999"));
            assertTrue(rendered.contains("My Telegram Bot"));
            assertTrue(rendered.contains("10 peticiones / minuto"));
        });
    }

    @Test
    void testRenderPublicKeyResultFragmentError() {
        TemplateEngine engine = ThymeleafConfig.createTemplateEngine();
        Context context = new Context();

        Map<String, Object> vars = new HashMap<>();
        vars.put("success", false);
        vars.put("errorMessage", "Límite de peticiones por IP excedido.");
        vars.put("creationResult", null);
        vars.put("serverPort", 8080);
        context.setVariables(vars);

        assertDoesNotThrow(() -> {
            String rendered = engine.process("fragments/public-key-result.html", context);
            assertNotNull(rendered);
            assertTrue(rendered.contains("Límite de peticiones por IP excedido."));
        });
    }
}
