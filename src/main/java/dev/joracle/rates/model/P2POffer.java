package dev.joracle.rates.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Normalized representation of a single P2P offer/advertisement.
 */
public record P2POffer(
        String advNo,
        TradeType tradeType,
        String advertiserNick,
        BigDecimal price,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        int monthOrderCount,
        double monthFinishRate,
        List<String> paymentMethods
) {
}
