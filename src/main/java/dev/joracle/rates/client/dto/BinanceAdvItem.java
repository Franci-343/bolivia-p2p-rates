package dev.joracle.rates.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Single advertisement item in Binance search response containing adv and advertiser info.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BinanceAdvItem(
        BinanceAdv adv,
        BinanceAdvertiser advertiser
) {
}
