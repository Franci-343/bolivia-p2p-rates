package dev.joracle.rates.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.joracle.rates.client.dto.BinanceAdv;
import dev.joracle.rates.client.dto.BinanceAdvItem;
import dev.joracle.rates.client.dto.BinanceAdvertiser;
import dev.joracle.rates.client.dto.BinanceSearchRequest;
import dev.joracle.rates.client.dto.BinanceSearchResponse;
import dev.joracle.rates.client.dto.BinanceTradeMethod;
import dev.joracle.rates.config.AppConfig;
import dev.joracle.rates.model.P2POffer;
import dev.joracle.rates.model.TradeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Native HTTP client for interacting with Binance P2P Friendly Search API.
 * Uses {@link java.net.http.HttpClient} with Virtual Threads / non-blocking design.
 */
public class BinanceP2PClient {

    private static final Logger log = LoggerFactory.getLogger(BinanceP2PClient.class);
    private static final String BINANCE_P2P_URL = "https://p2p.binance.com/bapi/c2c/v2/friendly/c2c/adv/search";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AppConfig config;

    public BinanceP2PClient(ObjectMapper objectMapper, AppConfig config) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                objectMapper,
                config
        );
    }

    public BinanceP2PClient(HttpClient httpClient, ObjectMapper objectMapper, AppConfig config) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient cannot be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
    }

    /**
     * Fetches current P2P offers for the specified trade type.
     *
     * @param tradeType BUY (user buying crypto) or SELL (user selling crypto)
     * @return List of normalized {@link P2POffer}
     */
    public List<P2POffer> fetchOffers(TradeType tradeType) {
        return fetchOffers(tradeType, config.binanceRows());
    }

    /**
     * Fetches current P2P offers with custom rows count.
     *
     * @param tradeType BUY or SELL
     * @param rows      Number of rows to fetch (1..20)
     * @return List of normalized {@link P2POffer}
     */
    public List<P2POffer> fetchOffers(TradeType tradeType, int rows) {
        BinanceSearchRequest requestPayload = BinanceSearchRequest.of(
                config.assetCurrency(),
                config.fiatCurrency(),
                tradeType.name(),
                rows
        );

        try {
            String requestBody = objectMapper.writeValueAsString(requestPayload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BINANCE_P2P_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "*/*")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            log.debug("Sending request to Binance P2P: {} ({}/{})", tradeType, config.fiatCurrency(), config.assetCurrency());
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Binance P2P HTTP Error: Status {}", response.statusCode());
                throw new BinanceApiException(response.statusCode(), response.body());
            }

            BinanceSearchResponse searchResponse = objectMapper.readValue(response.body(), BinanceSearchResponse.class);
            if (!searchResponse.isSuccessful()) {
                String errMsg = searchResponse.message() != null ? searchResponse.message() : "Unknown API response error";
                log.error("Binance P2P Business Error: code={}, message={}", searchResponse.code(), errMsg);
                throw new BinanceApiException("Binance API error: " + errMsg);
            }

            List<P2POffer> offers = new ArrayList<>();
            for (BinanceAdvItem item : searchResponse.data()) {
                if (item.adv() != null) {
                    offers.add(mapToOffer(item, tradeType));
                }
            }

            log.debug("Successfully retrieved {} {} offers from Binance P2P", offers.size(), tradeType);
            return offers;

        } catch (IOException e) {
            log.error("Network I/O error communicating with Binance P2P: {}", e.getMessage());
            throw new BinanceApiException("Network I/O failure querying Binance: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BinanceApiException("Thread interrupted while awaiting Binance response", e);
        }
    }

    private P2POffer mapToOffer(BinanceAdvItem item, TradeType tradeType) {
        BinanceAdv adv = item.adv();
        BinanceAdvertiser advertiser = item.advertiser();

        BigDecimal price = parseBigDecimalSafe(adv.price(), BigDecimal.ZERO);
        BigDecimal minAmount = parseBigDecimalSafe(adv.minSingleTransAmount(), BigDecimal.ZERO);
        BigDecimal maxAmount = parseBigDecimalSafe(adv.dynamicMaxSingleTransAmount(), BigDecimal.ZERO);

        List<String> methods = new ArrayList<>();
        if (adv.tradeMethods() != null) {
            for (BinanceTradeMethod method : adv.tradeMethods()) {
                String name = method.tradeMethodName();
                if (name != null && !name.isBlank()) {
                    methods.add(name.trim());
                } else if (method.identifier() != null && !method.identifier().isBlank()) {
                    methods.add(method.identifier().trim());
                }
            }
        }

        String advertiserNick = advertiser != null && advertiser.nickName() != null ? advertiser.nickName() : "Unknown";
        int orderCount = advertiser != null && advertiser.monthOrderCount() != null ? advertiser.monthOrderCount() : 0;
        double finishRate = advertiser != null && advertiser.monthFinishRate() != null ? advertiser.monthFinishRate() : 0.0;

        return new P2POffer(
                adv.advNo(),
                tradeType,
                advertiserNick,
                price,
                minAmount,
                maxAmount,
                orderCount,
                finishRate,
                methods
        );
    }

    private BigDecimal parseBigDecimalSafe(String val, BigDecimal fallback) {
        if (val == null || val.isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(val.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
