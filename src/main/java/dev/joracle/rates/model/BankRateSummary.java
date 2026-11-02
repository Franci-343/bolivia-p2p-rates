package dev.joracle.rates.model;

import java.math.BigDecimal;

/**
 * Statistical summary for a specific payment method or bank.
 */
public record BankRateSummary(
        String bankName,
        BigDecimal averagePrice,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        int offerCount
) {
}
