package com.nexusagent;

import com.nexusagent.identity.api.BootstrapTenantRequest;
import com.nexusagent.identity.api.BootstrapTenantResponse;
import com.nexusagent.identity.api.LoginRequest;
import com.nexusagent.identity.api.LoginResponse;
import com.nexusagent.ticket.api.CreateTicketRequest;
import com.nexusagent.ticket.api.CreateTicketResponse;
import com.nexusagent.ticket.domain.TicketPriority;
import com.nexusagent.ticket.domain.TicketStatus;
import com.nexusagent.ticket.api.TicketDetailResponse;
import com.nexusagent.ticket.domain.TicketSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.nexusagent.audit.api.AuditLogWriter;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;


import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = """
                nexus.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=
                """
)
class TicketCreationIT {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4.11")
                    .withDatabaseName("nexus_agent")
                    .withUsername("nexus_app")
                    .withPassword(
                            "integration-test-password"
                    );

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private AuditLogWriter auditLogWriter;

    @Test
    void shouldCreateTenantScopedTicketWithAuditLog() {
        String tenantCode = "ticket-creation-acme";
        String username = "admin";
        String password = "StrongPassword123!";

        BootstrapTenantRequest bootstrapRequest =
                new BootstrapTenantRequest(
                        tenantCode,
                        "Ticket Creation Acme",
                        username,
                        "ticket-admin@integration.example",
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

        BootstrapTenantResponse bootstrapBody =
                bootstrap.getBody();

        ResponseEntity<LoginResponse> login =
                restTemplate.postForEntity(
                        "/api/v1/auth/login",
                        new LoginRequest(
                                tenantCode,
                                username,
                                password
                        ),
                        LoginResponse.class
                );

        assertEquals(HttpStatus.OK, login.getStatusCode());
        assertNotNull(login.getBody());

        LoginResponse loginBody = login.getBody();

        CreateTicketRequest ticketRequest =
                new CreateTicketRequest(
                        "Production server unavailable",
                        "The production API cannot be reached.",
                        TicketPriority.HIGH
                );

        // 没有 Token 必须在到达 Controller 前被拦截。
        ResponseEntity<String> unauthorized =
                restTemplate.postForEntity(
                        "/api/v1/tickets",
                        ticketRequest,
                        String.class
                );

        assertEquals(
                HttpStatus.UNAUTHORIZED,
                unauthorized.getStatusCode()
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(
                loginBody.accessToken()
        );

        // 有效 Token + 非法参数必须返回 400，不能写数据库。
        ResponseEntity<String> invalid =
                restTemplate.exchange(
                        "/api/v1/tickets",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                new CreateTicketRequest(
                                        "",
                                        "",
                                        null
                                ),
                                headers
                        ),
                        String.class
                );

        assertEquals(
                HttpStatus.BAD_REQUEST,
                invalid.getStatusCode()
        );
        assertNotNull(invalid.getBody());
        assertTrue(
                invalid.getBody().contains(
                        "VALIDATION_FAILED"
                )
        );

        ResponseEntity<CreateTicketResponse> created =
                restTemplate.exchange(
                        "/api/v1/tickets",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                ticketRequest,
                                headers
                        ),
                        CreateTicketResponse.class
                );

        assertEquals(
                HttpStatus.CREATED,
                created.getStatusCode()
        );
        assertNotNull(created.getBody());

        CreateTicketResponse createdBody =
                created.getBody();

        assertAll(
                () -> assertNotNull(
                        createdBody.ticketId()
                ),
                () -> assertTrue(
                        createdBody.ticketNo()
                                .startsWith("TKT-")
                ),
                () -> assertEquals(
                        TicketStatus.OPEN,
                        createdBody.status()
                )
        );

        long ticketId =
                Long.parseLong(createdBody.ticketId());

        long tenantId =
                Long.parseLong(bootstrapBody.tenantId());

        long adminUserId =
                Long.parseLong(
                        bootstrapBody.adminUserId()
                );

        ResponseEntity<TicketDetailResponse> queried =
                restTemplate.exchange(
                        "/api/v1/tickets/{ticketNo}",
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        TicketDetailResponse.class,
                        createdBody.ticketNo()
                );

        assertEquals(
                HttpStatus.OK,
                queried.getStatusCode()
        );
        assertNotNull(queried.getBody());

        TicketDetailResponse queriedBody =
                queried.getBody();

        assertAll(
                () -> assertEquals(
                        createdBody.ticketId(),
                        queriedBody.ticketId()
                ),
                () -> assertEquals(
                        createdBody.ticketNo(),
                        queriedBody.ticketNo()
                ),
                () -> assertEquals(
                        ticketRequest.title(),
                        queriedBody.title()
                ),
                () -> assertEquals(
                        ticketRequest.description(),
                        queriedBody.description()
                ),
                () -> assertEquals(
                        TicketPriority.HIGH,
                        queriedBody.priority()
                ),
                () -> assertEquals(
                        TicketStatus.OPEN,
                        queriedBody.status()
                ),
                () -> assertEquals(
                        TicketSource.USER,
                        queriedBody.source()
                ),
                () -> assertEquals(
                        Long.toString(adminUserId),
                        queriedBody.requesterUserId()
                ),
                () -> assertNull(
                        queriedBody.assigneeUserId()
                ),
                () -> assertNull(
                        queriedBody.createdByAgentId()
                ),
                () -> assertEquals(
                        0,
                        queriedBody.version()
                ),
                () -> assertNotNull(
                        queriedBody.createdAt()
                ),
                () -> assertNotNull(
                        queriedBody.updatedAt()
                ),
                () -> assertNull(
                        queriedBody.closedAt()
                )
        );

        Map<String, Object> ticket =
                jdbcTemplate.queryForMap(
                        """
                        SELECT
                            tenant_id,
                            ticket_no,
                            title,
                            description,
                            priority,
                            status,
                            source,
                            requester_user_id,
                            assignee_user_id,
                            created_by_agent_id,
                            version
                        FROM tickets
                        WHERE id = ?
                        """,
                        ticketId
                );

        assertAll(
                () -> assertEquals(
                        tenantId,
                        number(ticket.get("tenant_id"))
                ),
                () -> assertEquals(
                        createdBody.ticketNo(),
                        ticket.get("ticket_no")
                ),
                () -> assertEquals(
                        ticketRequest.title(),
                        ticket.get("title")
                ),
                () -> assertEquals(
                        ticketRequest.description(),
                        ticket.get("description")
                ),
                () -> assertEquals(
                        "HIGH",
                        ticket.get("priority")
                ),
                () -> assertEquals(
                        "OPEN",
                        ticket.get("status")
                ),
                () -> assertEquals(
                        "USER",
                        ticket.get("source")
                ),
                () -> assertEquals(
                        adminUserId,
                        number(
                                ticket.get(
                                        "requester_user_id"
                                )
                        )
                ),
                () -> assertNull(
                        ticket.get("assignee_user_id")
                ),
                () -> assertNull(
                        ticket.get("created_by_agent_id")
                ),
                () -> assertEquals(
                        0,
                        ((Number) ticket.get("version"))
                                .intValue()
                )
        );

        Map<String, Object> audit =
                jdbcTemplate.queryForMap(
                        """
                        SELECT
                            tenant_id,
                            actor_type,
                            actor_id,
                            action,
                            resource_type,
                            resource_id,
                            result
                        FROM audit_logs
                        WHERE tenant_id = ?
                          AND action = 'TICKET_CREATED'
                          AND resource_id = ?
                        """,
                        tenantId,
                        ticketId
                );

        assertAll(
                () -> assertEquals(
                        tenantId,
                        number(audit.get("tenant_id"))
                ),
                () -> assertEquals(
                        "USER",
                        audit.get("actor_type")
                ),
                () -> assertEquals(
                        adminUserId,
                        number(audit.get("actor_id"))
                ),
                () -> assertEquals(
                        "TICKET_CREATED",
                        audit.get("action")
                ),
                () -> assertEquals(
                        "TICKET",
                        audit.get("resource_type")
                ),
                () -> assertEquals(
                        ticketId,
                        number(audit.get("resource_id"))
                ),
                () -> assertEquals(
                        "SUCCESS",
                        audit.get("result")
                )
        );

        Long ticketCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM tickets
                WHERE tenant_id = ?
                """,
                Long.class,
                tenantId
        );

        Long ticketAuditCount =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM audit_logs
                        WHERE tenant_id = ?
                          AND action = 'TICKET_CREATED'
                        """,
                        Long.class,
                        tenantId
                );

        // 无 Token 和非法请求都没有插入工单。
        assertAll(
                () -> assertEquals(1L, ticketCount),
                () -> assertEquals(
                        1L,
                        ticketAuditCount
                )
        );
    }

    @Test
    void shouldRollbackTicketWhenAuditWriteFails() {
        String tenantCode = "ticket-rollback-acme";
        String username = "admin";
        String password = "StrongPassword123!";

        ResponseEntity<BootstrapTenantResponse> bootstrap =
                restTemplate.postForEntity(
                        "/api/v1/tenants/bootstrap",
                        new BootstrapTenantRequest(
                                tenantCode,
                                "Ticket Rollback Acme",
                                username,
                                "rollback-admin@integration.example",
                                password
                        ),
                        BootstrapTenantResponse.class
                );

        assertEquals(
                HttpStatus.CREATED,
                bootstrap.getStatusCode()
        );
        assertNotNull(bootstrap.getBody());

        long tenantId = Long.parseLong(
                bootstrap.getBody().tenantId()
        );

        ResponseEntity<LoginResponse> login =
                restTemplate.postForEntity(
                        "/api/v1/auth/login",
                        new LoginRequest(
                                tenantCode,
                                username,
                                password
                        ),
                        LoginResponse.class
                );

        assertEquals(HttpStatus.OK, login.getStatusCode());
        assertNotNull(login.getBody());

        /*
         * Tenant bootstrap audit has already succeeded.
         * Only the next TICKET_CREATED audit is forced to fail.
         */
        AuditLogWriter auditLogWriterTarget =
                AopTestUtils.getUltimateTargetObject(
                        auditLogWriter
                );

        doThrow(new IllegalStateException(
                "Simulated audit write failure"
        ))
                .when(auditLogWriterTarget)
                .write(argThat(command ->
                        "TICKET_CREATED".equals(
                                command.action()
                        )
                ));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(
                login.getBody().accessToken()
        );

        ResponseEntity<String> response =
                restTemplate.exchange(
                        "/api/v1/tickets",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                new CreateTicketRequest(
                                        "Must be rolled back",
                                        "Audit persistence failed.",
                                        TicketPriority.URGENT
                                ),
                                headers
                        ),
                        String.class
                );

        assertTrue(
                response.getStatusCode()
                        .is5xxServerError()
        );

        Long ticketCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM tickets
                WHERE tenant_id = ?
                  AND title = 'Must be rolled back'
                """,
                Long.class,
                tenantId
        );

        Long auditCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_logs
                WHERE tenant_id = ?
                  AND action = 'TICKET_CREATED'
                """,
                Long.class,
                tenantId
        );

        assertAll(
                () -> assertEquals(
                        0L,
                        ticketCount,
                        "ticket insert must be rolled back"
                ),
                () -> assertEquals(
                        0L,
                        auditCount,
                        "failed audit must not be persisted"
                )
        );
    }

    @Test
    void shouldHideTicketFromDifferentTenant() {
        String password = "StrongPassword123!";

        ResponseEntity<BootstrapTenantResponse> ownerBootstrap =
                restTemplate.postForEntity(
                        "/api/v1/tenants/bootstrap",
                        new BootstrapTenantRequest(
                                "ticket-query-owner",
                                "Ticket Query Owner",
                                "admin",
                                "owner-admin@integration.example",
                                password
                        ),
                        BootstrapTenantResponse.class
                );

        ResponseEntity<BootstrapTenantResponse> outsiderBootstrap =
                restTemplate.postForEntity(
                        "/api/v1/tenants/bootstrap",
                        new BootstrapTenantRequest(
                                "ticket-query-outsider",
                                "Ticket Query Outsider",
                                "admin",
                                "outsider-admin@integration.example",
                                password
                        ),
                        BootstrapTenantResponse.class
                );

        assertAll(
                () -> assertEquals(
                        HttpStatus.CREATED,
                        ownerBootstrap.getStatusCode()
                ),
                () -> assertEquals(
                        HttpStatus.CREATED,
                        outsiderBootstrap.getStatusCode()
                )
        );

        ResponseEntity<LoginResponse> ownerLogin =
                restTemplate.postForEntity(
                        "/api/v1/auth/login",
                        new LoginRequest(
                                "ticket-query-owner",
                                "admin",
                                password
                        ),
                        LoginResponse.class
                );

        ResponseEntity<LoginResponse> outsiderLogin =
                restTemplate.postForEntity(
                        "/api/v1/auth/login",
                        new LoginRequest(
                                "ticket-query-outsider",
                                "admin",
                                password
                        ),
                        LoginResponse.class
                );

        assertAll(
                () -> assertEquals(
                        HttpStatus.OK,
                        ownerLogin.getStatusCode()
                ),
                () -> assertNotNull(ownerLogin.getBody()),
                () -> assertEquals(
                        HttpStatus.OK,
                        outsiderLogin.getStatusCode()
                ),
                () -> assertNotNull(outsiderLogin.getBody())
        );

        HttpHeaders ownerHeaders = new HttpHeaders();
        ownerHeaders.setBearerAuth(
                ownerLogin.getBody().accessToken()
        );

        HttpHeaders outsiderHeaders = new HttpHeaders();
        outsiderHeaders.setBearerAuth(
                outsiderLogin.getBody().accessToken()
        );

        ResponseEntity<CreateTicketResponse> created =
                restTemplate.exchange(
                        "/api/v1/tickets",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                new CreateTicketRequest(
                                        "Owner-only incident",
                                        "This ticket belongs to the owner tenant.",
                                        TicketPriority.HIGH
                                ),
                                ownerHeaders
                        ),
                        CreateTicketResponse.class
                );

        assertEquals(
                HttpStatus.CREATED,
                created.getStatusCode()
        );
        assertNotNull(created.getBody());

        String ticketNo = created.getBody().ticketNo();

        ResponseEntity<String> crossTenantQuery =
                restTemplate.exchange(
                        "/api/v1/tickets/{ticketNo}",
                        HttpMethod.GET,
                        new HttpEntity<>(outsiderHeaders),
                        String.class,
                        ticketNo
                );

        ResponseEntity<String> missingTicketQuery =
                restTemplate.exchange(
                        "/api/v1/tickets/{ticketNo}",
                        HttpMethod.GET,
                        new HttpEntity<>(ownerHeaders),
                        String.class,
                        "TKT-DOES-NOT-EXIST"
                );

        ResponseEntity<String> unauthorizedQuery =
                restTemplate.getForEntity(
                        "/api/v1/tickets/{ticketNo}",
                        String.class,
                        ticketNo
                );

        assertAll(
                () -> assertEquals(
                        HttpStatus.NOT_FOUND,
                        crossTenantQuery.getStatusCode()
                ),
                () -> assertNotNull(
                        crossTenantQuery.getBody()
                ),
                () -> assertTrue(
                        crossTenantQuery.getBody().contains(
                                "TICKET_NOT_FOUND"
                        )
                ),
                () -> assertEquals(
                        HttpStatus.NOT_FOUND,
                        missingTicketQuery.getStatusCode()
                ),
                () -> assertNotNull(
                        missingTicketQuery.getBody()
                ),
                () -> assertTrue(
                        missingTicketQuery.getBody().contains(
                                "TICKET_NOT_FOUND"
                        )
                ),
                () -> assertEquals(
                        HttpStatus.UNAUTHORIZED,
                        unauthorizedQuery.getStatusCode()
                )
        );
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }
}