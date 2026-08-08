package com.nexusagent.security;

import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class SpringSecurityCurrentActorProvider
        implements CurrentActorProvider {

    @Override
    public CurrentActor requireCurrentActor() {
        Authentication authentication =
                SecurityContextHolder.getContext()
                        .getAuthentication();

        if (!(authentication
                instanceof JwtAuthenticationToken jwtAuthentication)
                || !authentication.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException(
                    "Authenticated JWT user is required"
            );
        }

        String subject =
                jwtAuthentication.getToken().getSubject();

        String tenantIdClaim =
                jwtAuthentication.getToken()
                        .getClaimAsString("tenant_id");

        String username =
                jwtAuthentication.getToken()
                        .getClaimAsString("username");

        List<String> roleClaims =
                jwtAuthentication.getToken()
                        .getClaimAsStringList("roles");

        if (username == null || username.isBlank()) {
            throw invalidClaim("username");
        }

        long userId = parsePositiveLongClaim(
                subject,
                "sub"
        );

        long tenantId = parsePositiveLongClaim(
                tenantIdClaim,
                "tenant_id"
        );

        Set<String> roles = roleClaims == null
                ? Set.of()
                : Set.copyOf(roleClaims);

        return new CurrentActor(
                userId,
                tenantId,
                username,
                roles
        );
    }

    private static long parsePositiveLongClaim(
            String value,
            String claimName
    ) {
        if (value == null || value.isBlank()) {
            throw invalidClaim(claimName);
        }

        try {
            long parsed = Long.parseLong(value);

            if (parsed <= 0) {
                throw invalidClaim(claimName);
            }

            return parsed;
        } catch (NumberFormatException exception) {
            throw invalidClaim(claimName);
        }
    }

    private static AuthenticationCredentialsNotFoundException
    invalidClaim(String claimName) {
        return new AuthenticationCredentialsNotFoundException(
                "JWT claim " + claimName + " is invalid"
        );
    }
}