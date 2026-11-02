package dev.joracle.rates.model;

/**
 * Trade direction for Binance P2P.
 * <ul>
 *   <li>BUY: User buys crypto (USDT) paying in local fiat (BOB).</li>
 *   <li>SELL: User sells crypto (USDT) receiving local fiat (BOB).</li>
 * </ul>
 */
public enum TradeType {
    BUY,
    SELL
}
