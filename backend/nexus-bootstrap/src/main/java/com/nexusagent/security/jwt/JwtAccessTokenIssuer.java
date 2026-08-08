package com.nexusagent.security.jwt;

import com.nexusagent.identity.spi.AccessTokenIssuer;
import com.nexusagent.identity.spi.IssuedAccessToken;
import com.nexusagent.identity.spi.TokenSubject;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Component
public class JwtAccessTokenIssuer implements AccessTokenIssuer {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final Clock clock;

    public JwtAccessTokenIssuer(
            JwtEncoder jwtEncoder,
            JwtProperties properties,
            Clock clock
    ) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public IssuedAccessToken issue(TokenSubject subject) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(
                properties.accessTokenTtl()
        );

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(Long.toString(subject.userId()))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim(
                        "tenant_id",
                        Long.toString(subject.tenantId())
                )
                .claim("username", subject.username())
                .claim("roles", subject.roles())
                .build();

        JwsHeader header = JwsHeader
                .with(MacAlgorithm.HS256)
                .type("JWT")
                .build();

        Jwt jwt = jwtEncoder.encode(
                JwtEncoderParameters.from(header, claims)
        );

        return new IssuedAccessToken(
                jwt.getTokenValue(),
                properties.accessTokenTtl().toSeconds()
        );
    }
}