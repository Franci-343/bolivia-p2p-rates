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
import java.util.List;
import java.util.Map;

/**
 * Service responsible for statistical calculation, aggregation, and market insights.
 * Pure logic, highly testable.
 */
public class RateAnalysisService {

    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /**
     * Analyzes a list of P2P offers and produces a {@link MarketSummary}.
     */
    public MarketSummary analyzeMarket(List<P2POffer> offers, TradeType tradeType, String fiat, String asset) {
        if (offers == null || offers.isEmpty()) {
            return new MarketSummary(
                    tradeType,
                    fiat,
                    asset,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    0,
                    List.of(),
                    List.of()
            );
        }

        // Sort offers by price
        // For BUY (user buying USDT), lowest price is best for the buyer
        // For SELL (user selling USDT), highest price is best for the seller
        Comparator<P2POffer> priceComparator = Comparator.comparing(P2POffer::price);
        List<P2POffer> sortedOffers = offers.stream()
                .filter(o -> o.price().compareTo(BigDecimal.ZERO) > 0)
                .sorted(tradeType == TradeType.BUY ? priceComparator : priceComparator.reversed())
                .toList();

        if (sortedOffers.isEmpty()) {
            return new MarketSummary(tradeType, fiat, asset, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, List.of(), List.of());
        }

        List<BigDecimal> prices = sortedOffers.stream().map(P2POffer::price).sorted().toList();
        BigDecimal minPrice = prices.getFirst();
        BigDecimal maxPrice = prices.getLast();

        // Calculate average
        BigDecimal sum = prices.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal averagePrice = sum.divide(BigDecimal.valueOf(prices.size()), SCALE, ROUNDING);

        // Calculate median
        BigDecimal medianPrice = calculateMedian(prices);

        // Group by bank/payment method
        List<BankRateSummary> bankSummaries = analyzeBanks(sortedOffers);

        return new MarketSummary(
                tradeType,
                fiat,
                asset,
                averagePrice,
                medianPrice,
                minPrice,
                maxPrice,
                sortedOffers.size(),
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
        if (raw == null) return "Other";
        String lower = raw.toLowerCase().trim();
        if (lower.contains("union") || lower.contains("unión")) return "Banco Unión";
        if (lower.contains("bcp") || lower.contains("crédito") || lower.contains("credito")) return "Banco de Crédito (BCP)";
        if (lower.contains("bnb") || lower.contains("nacional de bolivia")) return "Banco BNB";
        if (lower.contains("mercantil") || lower.contains("bmsc")) return "Banco Mercantil Santa Cruz";
        if (lower.contains("fie")) return "Banco FIE";
        if (lower.contains("ganadero")) return "Banco Ganadero";
        if (lower.contains("soli") || lower.contains("solipagos")) return "SoliPagos";
        if (lower.contains("transfer") || lower.contains("bank")) return "Transferencia Bancaria";
        if (lower.contains("qr")) return "Pago QR";
        return raw;
    }
}
