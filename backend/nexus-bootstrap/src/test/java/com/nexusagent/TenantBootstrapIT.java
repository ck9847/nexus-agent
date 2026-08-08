package com.nexusagent;

import com.nexusagent.identity.api.BootstrapTenantRequest;
import com.nexusagent.identity.api.BootstrapTenantResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.nexusagent.identity.api.LoginRequest;
import com.nexusagent.identity.api.LoginResponse;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Instant;
import java.util.List;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = """
                nexus.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=
                """
)
@Import(TenantBootstrapIT.ProtectedProbeConfiguration.class)
class TenantBootstrapIT {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4.11")
                    .withDatabaseName("nexus_agent")
                    .withUsername("nexus_app")
                    .withPassword("integration-test-password");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    void shouldBootstrapTenantTransactionallyAndRejectDuplicate() {
        BootstrapTenantRequest request =
                new BootstrapTenantRequest(
                        "integration-acme",
                        "Integration Acme",
                        "admin",
                        "admin@integration.example",
                        "StrongPassword123!"
                );

        ResponseEntity<BootstrapTenantResponse> created =
                restTemplate.postForEntity(
                        "/api/v1/tenants/bootstrap",
                        request,
                        BootstrapTenantResponse.class
                );

        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertNotNull(created.getBody());

        long tenantId =
                Long.parseLong(created.getBody().tenantId());
        long adminUserId =
                Long.parseLong(created.getBody().adminUserId());
        long adminRoleId =
                Long.parseLong(created.getBody().adminRoleId());

        String passwordHash = jdbcTemplate.queryForObject(
                """
                SELECT password_hash
                FROM users
                WHERE id = ?
                """,
                String.class,
                adminUserId
        );

        String roleCode = jdbcTemplate.queryForObject(
                """
                SELECT code
                FROM roles
                WHERE id = ?
                """,
                String.class,
                adminRoleId
        );

        Long assignedBy = jdbcTemplate.queryForObject(
                """
                SELECT assigned_by
                FROM user_roles
                WHERE tenant_id = ?
                  AND user_id = ?
                  AND role_id = ?
                """,
                Long.class,
                tenantId,
                adminUserId,
                adminRoleId
        );

        String auditAction = jdbcTemplate.queryForObject(
                """
                SELECT action
                FROM audit_logs
                WHERE tenant_id = ?
                """,
                String.class,
                tenantId
        );

        assertAll(
                () -> assertEquals(
                        1L,
                        countRows("tenants", tenantId)
                ),
                () -> assertEquals(
                        1L,
                        countRows("users", tenantId)
                ),
                () -> assertEquals(
                        1L,
                        countRows("roles", tenantId)
                ),
                () -> assertEquals(
                        1L,
                        countRows("user_roles", tenantId)
                ),
                () -> assertEquals(
                        1L,
                        countRows("audit_logs", tenantId)
                ),
                () -> assertTrue(
                        passwordEncoder.matches(
                                request.adminPassword(),
                                passwordHash
                        )
                ),
                () -> assertEquals("ADMIN", roleCode),
                () -> assertEquals(adminUserId, assignedBy),
                () -> assertEquals(
                        "TENANT_BOOTSTRAPPED",
                        auditAction
                )
        );

        ResponseEntity<String> duplicate =
                restTemplate.postForEntity(
                        "/api/v1/tenants/bootstrap",
                        request,
                        String.class
                );

        assertEquals(
                HttpStatus.CONFLICT,
                duplicate.getStatusCode()
        );

        assertNotNull(duplicate.getBody());
        assertTrue(
                duplicate.getBody().contains(
                        "TENANT_CODE_ALREADY_EXISTS"
                )
        );

        assertAll(
                () -> assertEquals(
                        1L,
                        countRows("tenants", tenantId)
                ),
                () -> assertEquals(
                        1L,
                        countRows("users", tenantId)
                ),
                () -> assertEquals(
                        1L,
                        countRows("roles", tenantId)
                ),
                () -> assertEquals(
                        1L,
                        countRows("user_roles", tenantId)
                ),
                () -> assertEquals(
                        1L,
                        countRows("audit_logs", tenantId)
                )
        );
    }

    @Test
    void shouldAuthenticateAdminAndProtectApiWithBearerToken() {
        String tenantCode = "authentication-acme";
        String username = "admin";
        String password = "StrongPassword123!";

        BootstrapTenantRequest bootstrapRequest =
                new BootstrapTenantRequest(
                        tenantCode,
                        "Authentication Acme",
                        username,
                        "auth-admin@integration.example",
                        password
                );

        ResponseEntity<BootstrapTenantResponse> bootstrap =
                restTemplate.postForEntity(
                        "/api/v1/tenants/bootstrap",
                        bootstrapRequest,
                        BootstrapTenantResponse.class
                );

        assertEquals(
                HttpStatus.CREATED,
                bootstrap.getStatusCode()
        );
        assertNotNull(bootstrap.getBody());

        LoginRequest loginRequest = new LoginRequest(
                tenantCode,
                username,
                password
        );

        ResponseEntity<LoginResponse> login =
                restTemplate.postForEntity(
                        "/api/v1/auth/login",
                        loginRequest,
                        LoginResponse.class
                );

        assertEquals(HttpStatus.OK, login.getStatusCode());
        assertNotNull(login.getBody());

        LoginResponse loginBody = login.getBody();
        BootstrapTenantResponse bootstrapBody =
                bootstrap.getBody();

        assertAll(
                () -> assertEquals(
                        "Bearer",
                        loginBody.tokenType()
                ),
                () -> assertEquals(
                        bootstrapBody.adminUserId(),
                        loginBody.userId()
                ),
                () -> assertEquals(
                        bootstrapBody.tenantId(),
                        loginBody.tenantId()
                ),
                () -> assertEquals(
                        900,
                        loginBody.expiresInSeconds()
                ),
                () -> assertEquals(
                        List.of("ADMIN"),
                        loginBody.roles()
                ),
                () -> assertNotNull(
                        loginBody.accessToken()
                )
        );

        Jwt jwt = jwtDecoder.decode(
                loginBody.accessToken()
        );

        assertAll(
                () -> assertEquals(
                        "https://auth.nexus-agent.local",
                        jwt.getIssuer().toString()
                ),
                () -> assertEquals(
                        bootstrapBody.adminUserId(),
                        jwt.getSubject()
                ),
                () -> assertEquals(
                        bootstrapBody.tenantId(),
                        jwt.getClaimAsString("tenant_id")
                ),
                () -> assertEquals(
                        username,
                        jwt.getClaimAsString("username")
                ),
                () -> assertEquals(
                        List.of("ADMIN"),
                        jwt.getClaimAsStringList("roles")
                ),
                () -> assertNotNull(jwt.getId()),
                () -> assertNotNull(jwt.getIssuedAt()),
                () -> assertNotNull(jwt.getExpiresAt()),
                () -> assertTrue(
                        jwt.getExpiresAt().isAfter(
                                Instant.now()
                        )
                )
        );

        ResponseEntity<String> wrongPassword =
                restTemplate.postForEntity(
                        "/api/v1/auth/login",
                        new LoginRequest(
                                tenantCode,
                                username,
                                "WrongPassword123!"
                        ),
                        String.class
                );

        assertEquals(
                HttpStatus.UNAUTHORIZED,
                wrongPassword.getStatusCode()
        );
        assertNotNull(wrongPassword.getBody());
        assertTrue(
                wrongPassword.getBody().contains(
                        "INVALID_CREDENTIALS"
                )
        );

        ResponseEntity<String> withoutToken =
                restTemplate.getForEntity(
                        "/api/v1/test/authentication",
                        String.class
                );

        assertEquals(
                HttpStatus.UNAUTHORIZED,
                withoutToken.getStatusCode()
        );

        HttpHeaders bearerHeaders = new HttpHeaders();
        bearerHeaders.setBearerAuth(
                loginBody.accessToken()
        );

        ResponseEntity<AuthenticationProbeResponse> authenticated =
                restTemplate.exchange(
                        "/api/v1/test/authentication",
                        HttpMethod.GET,
                        new HttpEntity<>(bearerHeaders),
                        AuthenticationProbeResponse.class
                );

        assertEquals(
                HttpStatus.OK,
                authenticated.getStatusCode()
        );
        assertNotNull(authenticated.getBody());

        assertAll(
                () -> assertEquals(
                        username,
                        authenticated.getBody().username()
                ),
                () -> assertTrue(
                        authenticated.getBody()
                                .authorities()
                                .contains("ROLE_ADMIN")
                )
        );

        String tamperedToken = tamperSignature(
                loginBody.accessToken()
        );

        HttpHeaders tamperedHeaders = new HttpHeaders();
        tamperedHeaders.setBearerAuth(tamperedToken);

        ResponseEntity<String> tampered =
                restTemplate.exchange(
                        "/api/v1/test/authentication",
                        HttpMethod.GET,
                        new HttpEntity<>(tamperedHeaders),
                        String.class
                );

        assertEquals(
                HttpStatus.UNAUTHORIZED,
                tampered.getStatusCode()
        );
    }

    private long countRows(String table, long tenantId) {
        String sql = switch (table) {
            case "tenants" -> """
                SELECT COUNT(*)
                FROM tenants
                WHERE id = ?
                """;

            case "users" -> """
                SELECT COUNT(*)
                FROM users
                WHERE tenant_id = ?
                """;

            case "roles" -> """
                SELECT COUNT(*)
                FROM roles
                WHERE tenant_id = ?
                """;

            case "user_roles" -> """
                SELECT COUNT(*)
                FROM user_roles
                WHERE tenant_id = ?
                """;

            case "audit_logs" -> """
                SELECT COUNT(*)
                FROM audit_logs
                WHERE tenant_id = ?
                """;

            default -> throw new IllegalArgumentException(
                    "Unexpected table: " + table
            );
        };

        Long count = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                tenantId
        );

        return count == null ? 0L : count;
    }

    private String tamperSignature(String token) {
        int signatureStart = token.lastIndexOf('.') + 1;

        if (signatureStart <= 0
                || signatureStart >= token.length()) {
            throw new IllegalArgumentException(
                    "Unexpected JWT format"
            );
        }

        char original = token.charAt(signatureStart);
        char replacement = original == 'A' ? 'B' : 'A';

        return token.substring(0, signatureStart)
                + replacement
                + token.substring(signatureStart + 1);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProtectedProbeConfiguration {

        @Bean
        ProtectedProbeController protectedProbeController() {
            return new ProtectedProbeController();
        }
    }

    @RestController
    static class ProtectedProbeController {

        @GetMapping("/api/v1/test/authentication")
        AuthenticationProbeResponse authentication(
                Authentication authentication
        ) {
            List<String> authorities =
                    authentication.getAuthorities()
                            .stream()
                            .map(authority ->
                                    authority.getAuthority()
                            )
                            .sorted()
                            .toList();

            return new AuthenticationProbeResponse(
                    authentication.getName(),
                    authorities
            );
        }
    }

    record AuthenticationProbeResponse(
            String username,
            List<String> authorities
    ) {
    }
}