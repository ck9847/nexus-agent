package com.nexusagent.security;

import com.nexusagent.common.security.CurrentActor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpringSecurityCurrentActorProviderTest {

    private final SpringSecurityCurrentActorProvider provider =
            new SpringSecurityCurrentActorProvider();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldCreateCurrentActorFromJwt() {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(900);

        Jwt jwt = Jwt.withTokenValue("signed-token")
                .header("alg", "HS256")
                .subject("101")
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("tenant_id", "202")
                .claim("username", "admin")
                .claim(
                        "roles",
                        List.of("ADMIN")
                )
                .build();

        SecurityContext context =
                SecurityContextHolder.createEmptyContext();

        context.setAuthentication(
                new JwtAuthenticationToken(
                        jwt,
                        List.of()
                )
        );

        SecurityContextHolder.setContext(context);

        CurrentActor actor =
                provider.requireCurrentActor();

        assertAll(
                () -> assertEquals(
                        101L,
                        actor.userId()
                ),
                () -> assertEquals(
                        202L,
                        actor.tenantId()
                ),
                () -> assertEquals(
                        "admin",
                        actor.username()
                ),
                () -> assertEquals(
                        Set.of("ADMIN"),
                        actor.roles()
                )
        );
    }

    @Test
    void shouldRejectMissingAuthentication() {
        SecurityContextHolder.clearContext();

        assertThrows(
                AuthenticationCredentialsNotFoundException.class,
                provider::requireCurrentActor
        );
    }

    @Test
    void shouldRejectInvalidIdentityClaims() {
        Jwt jwt = Jwt.withTokenValue("signed-token")
                .header("alg", "HS256")
                .subject("invalid-user-id")
                .claim("tenant_id", "202")
                .claim("username", "admin")
                .claim(
                        "roles",
                        List.of("ADMIN")
                )
                .build();

        SecurityContext context =
                SecurityContextHolder.createEmptyContext();

        context.setAuthentication(
                new JwtAuthenticationToken(
                        jwt,
                        List.of()
                )
        );

        SecurityContextHolder.setContext(context);

        assertThrows(
                AuthenticationCredentialsNotFoundException.class,
                provider::requireCurrentActor
        );
    }
}