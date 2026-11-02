package dev.joracle.rates.service;

import dev.joracle.rates.model.BankRateSummary;
import dev.joracle.rates.model.MarketSummary;
import dev.joracle.rates.model.P2POffer;
import dev.joracle.rates.model.TradeType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service responsible for statistical calculation, aggregation, and market insights.
 * Follows the rigorous methodology established in docs/PLAN_BOLIVIA_P2P_RATES.md:
 * - Deduplication of offers by advNo
 * - Filtering of abnormal/invalid offers
 * - Robust median as primary reference price
 * - Sample mean as secondary indicator
 * - Bank-level aggregation
 */
public class RateAnalysisService {

    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /**
     * Analyzes raw P2P offers and produces an aggregated {@link MarketSummary}.
     */
    public MarketSummary analyzeMarket(List<P2POffer> rawOffers, TradeType tradeType, String fiat, String asset) {
        if (rawOffers == null || rawOffers.isEmpty()) {
            return new MarketSummary(
                    tradeType,
                    fiat,
                    asset,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    0,
                    0,
                    0,
                    List.of(),
                    List.of()
            );
        }

        int totalReceived = rawOffers.size();

        // 1. Validation & Deduplication (by advNo)
        Set<String> seenAdvNos = new HashSet<>();
        List<P2POffer> validOffers = new ArrayList<>();

        for (P2POffer offer : rawOffers) {
            if (offer == null) continue;

            // Check duplicate
            if (offer.advNo() != null && !offer.advNo().isBlank()) {
                if (!seenAdvNos.add(offer.advNo().trim())) {
                    continue; // Skip duplicate
                }
            }

            // Check valid positive price
            if (offer.price() == null || offer.price().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            // Check limits sanity (if max is specified and > 0, min cannot exceed max)
            if (offer.maxAmount() != null && offer.maxAmount().compareTo(BigDecimal.ZERO) > 0) {
                if (offer.minAmount() != null && offer.minAmount().compareTo(offer.maxAmount()) > 0) {
                    continue; // Inconsistent limits
                }
            }

            validOffers.add(offer);
        }

        int validCount = validOffers.size();
        int discardedCount = totalReceived - validCount;

        if (validOffers.isEmpty()) {
            return new MarketSummary(
                    tradeType,
                    fiat,
                    asset,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    totalReceived,
                    0,
                    discardedCount,
                    List.of(),
                    List.of()
            );
        }

        // 2. Sort offers
        // BUY: ascending (cheapest USDT price first)
        // SELL: descending (highest BOB received first)
        Comparator<P2POffer> priceComparator = Comparator.comparing(P2POffer::price);
        List<P2POffer> sortedOffers = validOffers.stream()
                .sorted(tradeType == TradeType.BUY ? priceComparator : priceComparator.reversed())
                .toList();

        // 3. Statistical metrics
        List<BigDecimal> sortedPrices = sortedOffers.stream()
                .map(P2POffer::price)
                .sorted()
                .toList();

        BigDecimal minPrice = sortedPrices.getFirst();
        BigDecimal maxPrice = sortedPrices.getLast();

        // Calculate Mean (average)
        BigDecimal sum = sortedPrices.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal averagePrice = sum.divide(BigDecimal.valueOf(sortedPrices.size()), SCALE, ROUNDING);

        // Calculate Median (primary reference price)
        BigDecimal medianPrice = calculateMedian(sortedPrices);
        BigDecimal referencePrice = medianPrice;

        // 4. Group by bank / payment method
        List<BankRateSummary> bankSummaries = analyzeBanks(sortedOffers);

        return new MarketSummary(
                tradeType,
                fiat,
                asset,
                referencePrice,
                averagePrice,
                medianPrice,
                minPrice,
                maxPrice,
                totalReceived,
                validCount,
                discardedCount,
                bankSummaries,
                sortedOffers
        );
    }

    private BigDecimal calculateMedian(List<BigDecimal> sortedPrices) {
        int size = sortedPrices.size();
        if (size == 0) return BigDecimal.ZERO;
        if (size % 2 == 1) {
            return sortedPrices.get(size / 2).setScale(SCALE, ROUNDING);
        } else {
            BigDecimal mid1 = sortedPrices.get((size / 2) - 1);
            BigDecimal mid2 = sortedPrices.get(size / 2);
            return mid1.add(mid2).divide(BigDecimal.valueOf(2), SCALE, ROUNDING);
        }
    }

    private List<BankRateSummary> analyzeBanks(List<P2POffer> offers) {
        Map<String, List<BigDecimal>> bankPrices = new HashMap<>();

        for (P2POffer offer : offers) {
            if (offer.paymentMethods() == null) continue;
            for (String method : offer.paymentMethods()) {
                String normalizedMethod = normalizeBankName(method);
                bankPrices.computeIfAbsent(normalizedMethod, k -> new ArrayList<>()).add(offer.price());
            }
        }

        return bankPrices.entrySet().stream()
                .map(entry -> {
                    String bankName = entry.getKey();
                    List<BigDecimal> prices = entry.getValue().stream().sorted().toList();
                    BigDecimal sum = prices.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal avg = sum.divide(BigDecimal.valueOf(prices.size()), SCALE, ROUNDING);
                    BigDecimal min = prices.getFirst();
                    BigDecimal max = prices.getLast();
                    return new BankRateSummary(bankName, avg, min, max, prices.size());
                })
                .sorted(Comparator.comparingInt(BankRateSummary::offerCount).reversed())
                .toList();
    }

    private String normalizeBankName(String raw) {
        if (raw == null) return "Otro";
        String lower = raw.toLowerCase().trim();
        if (lower.contains("union") || lower.contains("unión")) return "Banco Unión";
        if (lower.contains("bcp") || lower.contains("crédito") || lower.contains("credito")) return "Banco de Crédito (BCP)";
        if (lower.contains("bnb") || lower.contains("nacional de bolivia")) return "Banco BNB";
        if (lower.contains("mercantil") || lower.contains("bmsc")) return "Banco Mercantil Santa Cruz";
        if (lower.contains("fie")) return "Banco FIE";
        if (lower.contains("ganadero")) return "Banco Ganadero";
        if (lower.contains("economico") || lower.contains("económico")) return "Banco Económico";
        if (lower.contains("soli") || lower.contains("solipagos")) return "SoliPagos";
        if (lower.contains("transfer") || lower.contains("bank")) return "Transferencia Bancaria";
        if (lower.contains("qr")) return "Pago QR";
        return raw;
    }
}
