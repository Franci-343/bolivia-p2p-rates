package dev.joracle.rates.client;

/**
 * Custom runtime exception thrown when communication with Binance P2P API fails.
 */
public class BinanceApiException extends RuntimeException {

    private final int statusCode;

    public BinanceApiException(String message) {
        super(message);
        this.statusCode = 0;
    }

    public BinanceApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }

    public BinanceApiException(int statusCode, String message) {
        super("Binance API error (HTTP " + statusCode + "): " + message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
