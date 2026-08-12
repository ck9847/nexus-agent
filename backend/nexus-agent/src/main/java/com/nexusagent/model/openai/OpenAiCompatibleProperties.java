package com.nexusagent.model.openai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

@ConfigurationProperties(prefix = "nexus.model.openai")
public final class OpenAiCompatibleProperties {

    private static final int MIN_ERROR_BODY_BYTES = 1_024;
    private static final int MAX_ERROR_BODY_BYTES = 65_536;

    private final boolean enabled;
    private final URI baseUrl;
    private final String apiKey;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final int maxErrorBodyBytes;

    public OpenAiCompatibleProperties(
            @DefaultValue("false")
            boolean enabled,

            @DefaultValue("https://api.openai.com/v1")
            URI baseUrl,

            String apiKey,

            @DefaultValue("10s")
            Duration connectTimeout,

            @DefaultValue("2m")
            Duration readTimeout,

            @DefaultValue("8192")
            int maxErrorBodyBytes
    ) {
        this.enabled = enabled;
        this.baseUrl = validateBaseUrl(baseUrl);
        this.apiKey = normalizeApiKey(apiKey);
        this.connectTimeout = requirePositive(
                connectTimeout,
                "connectTimeout"
        );
        this.readTimeout = requirePositive(
                readTimeout,
                "readTimeout"
        );

        if (maxErrorBodyBytes < MIN_ERROR_BODY_BYTES
                || maxErrorBodyBytes > MAX_ERROR_BODY_BYTES) {
            throw new IllegalArgumentException(
                    "maxErrorBodyBytes must be between "
                            + MIN_ERROR_BODY_BYTES
                            + " and "
                            + MAX_ERROR_BODY_BYTES
            );
        }

        this.maxErrorBodyBytes = maxErrorBodyBytes;

        if (enabled && this.apiKey == null) {
            throw new IllegalArgumentException(
                    "OpenAI API key must be configured "
                            + "when the provider is enabled"
            );
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public URI baseUrl() {
        return baseUrl;
    }

    public String apiKey() {
        return apiKey;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration readTimeout() {
        return readTimeout;
    }

    public int maxErrorBodyBytes() {
        return maxErrorBodyBytes;
    }

    public URI chatCompletionsUri() {
        String normalized = baseUrl.toASCIIString();

        while (normalized.endsWith("/")) {
            normalized = normalized.substring(
                    0,
                    normalized.length() - 1
            );
        }

        return URI.create(
                normalized + "/chat/completions"
        );
    }

    @Override
    public String toString() {
        return "OpenAiCompatibleProperties["
                + "enabled=" + enabled
                + ", baseUrl=" + baseUrl
                + ", apiKey="
                + (apiKey == null
                ? "<not-configured>"
                : "<redacted>")
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout
                + ", maxErrorBodyBytes="
                + maxErrorBodyBytes
                + ']';
    }

    private static URI validateBaseUrl(URI value) {
        if (value == null || !value.isAbsolute()) {
            throw new IllegalArgumentException(
                    "OpenAI base URL must be absolute"
            );
        }

        String scheme = value.getScheme()
                .toLowerCase(Locale.ROOT);

        if (!scheme.equals("http")
                && !scheme.equals("https")) {
            throw new IllegalArgumentException(
                    "OpenAI base URL must use HTTP or HTTPS"
            );
        }

        if (value.getHost() == null
                || value.getHost().isBlank()) {
            throw new IllegalArgumentException(
                    "OpenAI base URL must contain a host"
            );
        }

        if (value.getUserInfo() != null
                || value.getQuery() != null
                || value.getFragment() != null) {
            throw new IllegalArgumentException(
                    "OpenAI base URL must not contain "
                            + "credentials, query, or fragment"
            );
        }

        return value;
    }

    private static String normalizeApiKey(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();

        return normalized.isEmpty() ? null : normalized;
    }

    private static Duration requirePositive(
            Duration value,
            String field
    ) {
        if (value == null
                || value.isZero()
                || value.isNegative()) {
            throw new IllegalArgumentException(
                    field + " must be positive"
            );
        }

        return value;
    }
}