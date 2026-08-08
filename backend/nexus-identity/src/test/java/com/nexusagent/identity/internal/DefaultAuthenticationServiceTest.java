package com.nexusagent.identity.internal;

import com.nexusagent.identity.api.InvalidCredentialsException;
import com.nexusagent.identity.api.LoginRequest;
import com.nexusagent.identity.api.LoginResponse;
import com.nexusagent.identity.domain.UserStatus;
import com.nexusagent.identity.internal.persistence.LoginUserRow;
import com.nexusagent.identity.internal.persistence.UserMapper;
import com.nexusagent.identity.spi.AccessTokenIssuer;
import com.nexusagent.identity.spi.IssuedAccessToken;
import com.nexusagent.identity.spi.TokenSubject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultAuthenticationServiceTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final PasswordEncoder passwordEncoder =
            mock(PasswordEncoder.class);
    private final AccessTokenIssuer accessTokenIssuer =
            mock(AccessTokenIssuer.class);

    private final DefaultAuthenticationService service =
            new DefaultAuthenticationService(
                    userMapper,
                    passwordEncoder,
                    accessTokenIssuer
            );

    @Test
    void shouldAuthenticateAndIssueToken() {
        LoginUserRow user = new LoginUserRow(
                101L,
                201L,
                "admin",
                "$2a$12$encoded",
                UserStatus.ACTIVE,
                "MEMBER,ADMIN,ADMIN"
        );

        when(userMapper.findForAuthentication(
                "acme-corp",
                "admin"
        )).thenReturn(user);

        when(passwordEncoder.matches(
                "StrongPassword123!",
                "$2a$12$encoded"
        )).thenReturn(true);

        when(accessTokenIssuer.issue(any()))
                .thenReturn(new IssuedAccessToken(
                        "signed.jwt.token",
                        900
                ));

        LoginResponse response = service.login(new LoginRequest(
                " ACME-CORP ",
                " Admin ",
                "StrongPassword123!"
        ));

        assertEquals("Bearer", response.tokenType());
        assertEquals("signed.jwt.token", response.accessToken());
        assertEquals(900, response.expiresInSeconds());
        assertEquals("101", response.userId());
        assertEquals("201", response.tenantId());
        assertEquals(
                java.util.List.of("ADMIN", "MEMBER"),
                response.roles()
        );

        ArgumentCaptor<TokenSubject> subjectCaptor =
                ArgumentCaptor.forClass(TokenSubject.class);

        verify(accessTokenIssuer).issue(subjectCaptor.capture());

        assertEquals(
                java.util.List.of("ADMIN", "MEMBER"),
                subjectCaptor.getValue().roles()
        );
    }

    @Test
    void shouldRejectWrongPassword() {
        when(userMapper.findForAuthentication(
                "acme-corp",
                "admin"
        )).thenReturn(new LoginUserRow(
                101L,
                201L,
                "admin",
                "$2a$12$encoded",
                UserStatus.ACTIVE,
                "ADMIN"
        ));

        when(passwordEncoder.matches(
                "wrong-password",
                "$2a$12$encoded"
        )).thenReturn(false);

        assertThrows(
                InvalidCredentialsException.class,
                () -> service.login(new LoginRequest(
                        "acme-corp",
                        "admin",
                        "wrong-password"
                ))
        );

        verifyNoInteractions(accessTokenIssuer);
    }

    @Test
    void shouldRunDummyHashForMissingUser() {
        when(userMapper.findForAuthentication(
                "acme-corp",
                "missing"
        )).thenReturn(null);

        when(passwordEncoder.matches(
                eq("wrong-password"),
                anyString()
        )).thenReturn(false);

        assertThrows(
                InvalidCredentialsException.class,
                () -> service.login(new LoginRequest(
                        "acme-corp",
                        "missing",
                        "wrong-password"
                ))
        );

        verify(passwordEncoder).matches(
                eq("wrong-password"),
                anyString()
        );

        verifyNoInteractions(accessTokenIssuer);
    }

    @Test
    void shouldRejectDisabledUser() {
        when(userMapper.findForAuthentication(
                "acme-corp",
                "admin"
        )).thenReturn(new LoginUserRow(
                101L,
                201L,
                "admin",
                "$2a$12$encoded",
                UserStatus.DISABLED,
                "ADMIN"
        ));

        when(passwordEncoder.matches(
                "StrongPassword123!",
                "$2a$12$encoded"
        )).thenReturn(true);

        assertThrows(
                InvalidCredentialsException.class,
                () -> service.login(new LoginRequest(
                        "acme-corp",
                        "admin",
                        "StrongPassword123!"
                ))
        );

        verifyNoInteractions(accessTokenIssuer);
    }
}