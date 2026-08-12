package com.nexusagent.model.api;

import java.math.BigDecimal;

public record ChatModelOptions(
        BigDecimal temperature,
        BigDecimal topP,
        Integer maxOutputTokens
) {

    private static final BigDecimal MIN_TEMPERATURE =
            new BigDecimal("0.0");

    private static final BigDecimal MAX_TEMPERATURE =
            new BigDecimal("2.0");

    private static final BigDecimal MIN_TOP_P =
            new BigDecimal("0.0");

    private static final BigDecimal MAX_TOP_P =
            new BigDecimal("1.0");

    private static final int MAX_OUTPUT_TOKENS = 131_072;

    public ChatModelOptions {
        if (temperature != null
                && (temperature.compareTo(MIN_TEMPERATURE) < 0
                || temperature.compareTo(MAX_TEMPERATURE) > 0)) {
            throw new IllegalArgumentException(
                    "temperature must be between 0.0 and 2.0"
            );
        }

        if (topP != null
                && (topP.compareTo(MIN_TOP_P) < 0
                || topP.compareTo(MAX_TOP_P) > 0)) {
            throw new IllegalArgumentException(
                    "topP must be between 0.0 and 1.0"
            );
        }

        if (maxOutputTokens != null
                && (maxOutputTokens < 1
                || maxOutputTokens > MAX_OUTPUT_TOKENS)) {
            throw new IllegalArgumentException(
                    "maxOutputTokens must be between 1 and 131072"
            );
        }
    }

    public static ChatModelOptions defaults() {
        return new ChatModelOptions(null, null, null);
    }
}