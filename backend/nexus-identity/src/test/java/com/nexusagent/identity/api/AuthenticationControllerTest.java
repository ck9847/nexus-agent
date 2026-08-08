package com.nexusagent.identity.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthenticationController.class)
@Import(IdentityExceptionHandler.class)
class AuthenticationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthenticationService authenticationService;

    @Test
    void shouldReturnAccessToken() throws Exception {
        when(authenticationService.login(any()))
                .thenReturn(new LoginResponse(
                        "Bearer",
                        "signed.jwt.token",
                        900,
                        "101",
                        "202",
                        List.of("ADMIN")
                ));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("""
                                {
                                  "tenantCode": "acme-corp",
                                  "username": "admin",
                                  "password": "StrongPassword123!"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType")
                        .value("Bearer"))
                .andExpect(jsonPath("$.accessToken")
                        .value("signed.jwt.token"))
                .andExpect(jsonPath("$.expiresInSeconds")
                        .value(900))
                .andExpect(jsonPath("$.userId")
                        .value("101"))
                .andExpect(jsonPath("$.tenantId")
                        .value("202"))
                .andExpect(jsonPath("$.roles[0]")
                        .value("ADMIN"));
    }

    @Test
    void shouldReturnUnauthorizedForInvalidCredentials()
            throws Exception {
        when(authenticationService.login(any()))
                .thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("""
                                {
                                  "tenantCode": "acme-corp",
                                  "username": "admin",
                                  "password": "WrongPassword"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode")
                        .value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.detail")
                        .value(
                                "Invalid tenant code, username or password"
                        ));
    }

    @Test
    void shouldReturnBadRequestForInvalidLoginRequest()
            throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("""
                                {
                                  "tenantCode": "INVALID CODE",
                                  "username": "a",
                                  "password": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode")
                        .value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.tenantCode").exists())
                .andExpect(jsonPath("$.errors.username").exists())
                .andExpect(jsonPath("$.errors.password").exists());
    }
}