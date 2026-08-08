package com.nexusagent.security.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "nexus.security.jwt")
public record JwtProperties(
        String issuer,
        String secret,
        Duration accessTokenTtl
) {

    public JwtProperties {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException(
                    "JWT issuer must not be blank"
            );
        }

        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException(
                    "JWT secret must not be blank"
            );
        }

        if (accessTokenTtl == null
                || accessTokenTtl.toSeconds() <= 0) {
            throw new IllegalArgumentException(
                    "JWT access token TTL must be positive"
            );
        }
    }
}