package dev.joracle.rates.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Advertiser / merchant details from Binance P2P.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BinanceAdvertiser(
        String userNo,
        String nickName,
        Integer monthOrderCount,
        Double monthFinishRate,
        Double positiveRate,
        String userType
) {
}
