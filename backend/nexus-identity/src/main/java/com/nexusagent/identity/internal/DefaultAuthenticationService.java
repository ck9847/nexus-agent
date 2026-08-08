package com.nexusagent.identity.internal;

import com.nexusagent.identity.api.AuthenticationService;
import com.nexusagent.identity.api.InvalidCredentialsException;
import com.nexusagent.identity.api.LoginRequest;
import com.nexusagent.identity.api.LoginResponse;
import com.nexusagent.identity.domain.UserStatus;
import com.nexusagent.identity.internal.persistence.LoginUserRow;
import com.nexusagent.identity.internal.persistence.UserMapper;
import com.nexusagent.identity.spi.AccessTokenIssuer;
import com.nexusagent.identity.spi.IssuedAccessToken;
import com.nexusagent.identity.spi.TokenSubject;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Service
public class DefaultAuthenticationService
        implements AuthenticationService {

    /*
     * BCrypt cost 12 dummy hash. It prevents a missing-user request
     * from returning significantly faster than a wrong-password request.
     */
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$12$R5M8ZlYkudC1SnYuZCnUjuVEX6j1GYiQX0r9cpuXhtCU5VR/VWAeK";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer accessTokenIssuer;

    public DefaultAuthenticationService(
            UserMapper userMapper,
            PasswordEncoder passwordEncoder,
            AccessTokenIssuer accessTokenIssuer
    ) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenIssuer = accessTokenIssuer;
    }

    @Override
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        String tenantCode = normalize(request.tenantCode());
        String username = normalize(request.username());

        LoginUserRow user = userMapper.findForAuthentication(
                tenantCode,
                username
        );

        String passwordHash = user == null
                ? DUMMY_PASSWORD_HASH
                : user.passwordHash();

        boolean passwordMatches = safePasswordMatches(
                request.password(),
                passwordHash
        );

        if (user == null
                || !passwordMatches
                || user.status() != UserStatus.ACTIVE) {
            throw new InvalidCredentialsException();
        }

        List<String> roles = parseRoles(user.roleCodes());

        TokenSubject subject = new TokenSubject(
                user.id(),
                user.tenantId(),
                user.username(),
                roles
        );

        IssuedAccessToken token =
                accessTokenIssuer.issue(subject);

        return new LoginResponse(
                "Bearer",
                token.value(),
                token.expiresInSeconds(),
                Long.toString(user.id()),
                Long.toString(user.tenantId()),
                roles
        );
    }

    private boolean safePasswordMatches(
            String rawPassword,
            String encodedPassword
    ) {
        try {
            return passwordEncoder.matches(
                    rawPassword,
                    encodedPassword
            );
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private List<String> parseRoles(String roleCodes) {
        if (roleCodes == null || roleCodes.isBlank()) {
            return List.of();
        }

        return Arrays.stream(roleCodes.split(","))
                .map(String::trim)
                .filter(role -> !role.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}