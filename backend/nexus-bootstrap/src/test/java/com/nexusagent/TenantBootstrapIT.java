package com.nexusagent;

import com.nexusagent.identity.api.BootstrapTenantRequest;
import com.nexusagent.identity.api.BootstrapTenantResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
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
}