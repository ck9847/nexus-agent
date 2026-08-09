package com.nexusagent;

import com.nexusagent.identity.api.BootstrapTenantRequest;
import com.nexusagent.identity.api.BootstrapTenantResponse;
import com.nexusagent.identity.api.LoginRequest;
import com.nexusagent.identity.api.LoginResponse;
import com.nexusagent.ticket.api.ChangeTicketStatusRequest;
import com.nexusagent.ticket.api.ChangeTicketStatusResponse;
import com.nexusagent.ticket.api.CreateTicketRequest;
import com.nexusagent.ticket.api.CreateTicketResponse;
import com.nexusagent.ticket.domain.TicketPriority;
import com.nexusagent.ticket.domain.TicketStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.nexusagent.audit.api.AuditLogWriter;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.test.context.bean.override.mockito.MockReset;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest(
        webEnvironment =
                SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = """
                nexus.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=
                """
)
class TicketStatusLifecycleIT {

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

    @MockitoSpyBean(reset = MockReset.AFTER)
    private AuditLogWriter auditLogWriter;

    @BeforeEach
    void configurePatchSupport() {
        restTemplate.getRestTemplate()
                .setRequestFactory(
                        new JdkClientHttpRequestFactory()
                );
    }

    @Test
    void shouldEnforceTenantScopedLifecycleAndAudit() {
        String password = "StrongPassword123!";

        BootstrapTenantResponse ownerBootstrap =
                bootstrapTenant(
                        "ticket-status-owner",
                        "Ticket Status Owner",
                        "owner-status-admin@integration.example",
                        password
                );

        bootstrapTenant(
                "ticket-status-outsider",
                "Ticket Status Outsider",
                "outsider-status-admin@integration.example",
                password
        );

        LoginResponse ownerLogin = login(
                "ticket-status-owner",
                password
        );

        LoginResponse outsiderLogin = login(
                "ticket-status-outsider",
                password
        );

        assertAll(
                () -> assertEquals(
                        ownerBootstrap.tenantId(),
                        ownerLogin.tenantId()
                ),
                () -> assertEquals(
                        ownerBootstrap.adminUserId(),
                        ownerLogin.userId()
                )
        );

        long ownerTenantId = Long.parseLong(
                ownerBootstrap.tenantId()
        );

        long ownerAdminUserId = Long.parseLong(
                ownerBootstrap.adminUserId()
        );

        HttpHeaders ownerHeaders =
                bearerHeaders(ownerLogin);

        HttpHeaders outsiderHeaders =
                bearerHeaders(outsiderLogin);

        CreateTicketResponse ticket = createTicket(
                ownerHeaders,
                "Status lifecycle integration ticket"
        );

        assertEquals(
                TicketStatus.OPEN,
                ticket.status()
        );

        long ticketId =
                Long.parseLong(ticket.ticketId());

        assertPersistedState(
                ownerTenantId,
                ticketId,
                TicketStatus.OPEN,
                0,
                false,
                0
        );

        // 无 Token：必须返回 401，数据库不能改变。
        ResponseEntity<String> unauthorized =
                changeStatus(
                        new HttpHeaders(),
                        ticket.ticketNo(),
                        TicketStatus.IN_PROGRESS,
                        0,
                        String.class
                );

        assertEquals(
                HttpStatus.UNAUTHORIZED,
                unauthorized.getStatusCode()
        );

        assertPersistedState(
                ownerTenantId,
                ticketId,
                TicketStatus.OPEN,
                0,
                false,
                0
        );

        // 其他租户：表现为通用 404，不能泄漏工单存在。
        ResponseEntity<String> crossTenant =
                changeStatus(
                        outsiderHeaders,
                        ticket.ticketNo(),
                        TicketStatus.IN_PROGRESS,
                        0,
                        String.class
                );

        assertProblem(
                crossTenant,
                HttpStatus.NOT_FOUND,
                "TICKET_NOT_FOUND"
        );

        assertPersistedState(
                ownerTenantId,
                ticketId,
                TicketStatus.OPEN,
                0,
                false,
                0
        );

        // OPEN 不能直接 CLOSED。
        ResponseEntity<String> invalidTransition =
                changeStatus(
                        ownerHeaders,
                        ticket.ticketNo(),
                        TicketStatus.CLOSED,
                        0,
                        String.class
                );

        assertProblem(
                invalidTransition,
                HttpStatus.CONFLICT,
                "INVALID_TICKET_STATUS_TRANSITION"
        );

        assertPersistedState(
                ownerTenantId,
                ticketId,
                TicketStatus.OPEN,
                0,
                false,
                0
        );

        ChangeTicketStatusResponse inProgress =
                requireBody(
                        changeStatus(
                                ownerHeaders,
                                ticket.ticketNo(),
                                TicketStatus.IN_PROGRESS,
                                0,
                                ChangeTicketStatusResponse.class
                        ),
                        HttpStatus.OK
                );

        assertChangeResponse(
                inProgress,
                ticket,
                TicketStatus.OPEN,
                TicketStatus.IN_PROGRESS,
                1,
                false
        );

        assertPersistedState(
                ownerTenantId,
                ticketId,
                TicketStatus.IN_PROGRESS,
                1,
                false,
                1
        );

        // 使用旧版本 0，必须发生乐观锁冲突。
        ResponseEntity<String> staleVersion =
                changeStatus(
                        ownerHeaders,
                        ticket.ticketNo(),
                        TicketStatus.RESOLVED,
                        0,
                        String.class
                );

        assertProblem(
                staleVersion,
                HttpStatus.CONFLICT,
                "TICKET_VERSION_CONFLICT"
        );

        assertPersistedState(
                ownerTenantId,
                ticketId,
                TicketStatus.IN_PROGRESS,
                1,
                false,
                1
        );

        ChangeTicketStatusResponse resolved =
                requireBody(
                        changeStatus(
                                ownerHeaders,
                                ticket.ticketNo(),
                                TicketStatus.RESOLVED,
                                1,
                                ChangeTicketStatusResponse.class
                        ),
                        HttpStatus.OK
                );

        assertChangeResponse(
                resolved,
                ticket,
                TicketStatus.IN_PROGRESS,
                TicketStatus.RESOLVED,
                2,
                false
        );

        assertPersistedState(
                ownerTenantId,
                ticketId,
                TicketStatus.RESOLVED,
                2,
                false,
                2
        );

        ChangeTicketStatusResponse reopened =
                requireBody(
                        changeStatus(
                                ownerHeaders,
                                ticket.ticketNo(),
                                TicketStatus.IN_PROGRESS,
                                2,
                                ChangeTicketStatusResponse.class
                        ),
                        HttpStatus.OK
                );

        assertChangeResponse(
                reopened,
                ticket,
                TicketStatus.RESOLVED,
                TicketStatus.IN_PROGRESS,
                3,
                false
        );

        assertPersistedState(
                ownerTenantId,
                ticketId,
                TicketStatus.IN_PROGRESS,
                3,
                false,
                3
        );

        ChangeTicketStatusResponse resolvedAgain =
                requireBody(
                        changeStatus(
                                ownerHeaders,
                                ticket.ticketNo(),
                                TicketStatus.RESOLVED,
                                3,
                                ChangeTicketStatusResponse.class
                        ),
                        HttpStatus.OK
                );

        assertChangeResponse(
                resolvedAgain,
                ticket,
                TicketStatus.IN_PROGRESS,
                TicketStatus.RESOLVED,
                4,
                false
        );

        assertPersistedState(
                ownerTenantId,
                ticketId,
                TicketStatus.RESOLVED,
                4,
                false,
                4
        );

        ChangeTicketStatusResponse closed =
                requireBody(
                        changeStatus(
                                ownerHeaders,
                                ticket.ticketNo(),
                                TicketStatus.CLOSED,
                                4,
                                ChangeTicketStatusResponse.class
                        ),
                        HttpStatus.OK
                );

        assertChangeResponse(
                closed,
                ticket,
                TicketStatus.RESOLVED,
                TicketStatus.CLOSED,
                5,
                true
        );

        assertPersistedState(
                ownerTenantId,
                ticketId,
                TicketStatus.CLOSED,
                5,
                true,
                5
        );

        List<StatusAuditRow> auditRows =
                readStatusAuditRows(
                        ownerTenantId,
                        ticketId
                );

        assertEquals(
                List.of(
                        audit(
                                ownerAdminUserId,
                                ticketId,
                                "OPEN",
                                0,
                                "IN_PROGRESS",
                                1
                        ),
                        audit(
                                ownerAdminUserId,
                                ticketId,
                                "IN_PROGRESS",
                                1,
                                "RESOLVED",
                                2
                        ),
                        audit(
                                ownerAdminUserId,
                                ticketId,
                                "RESOLVED",
                                2,
                                "IN_PROGRESS",
                                3
                        ),
                        audit(
                                ownerAdminUserId,
                                ticketId,
                                "IN_PROGRESS",
                                3,
                                "RESOLVED",
                                4
                        ),
                        audit(
                                ownerAdminUserId,
                                ticketId,
                                "RESOLVED",
                                4,
                                "CLOSED",
                                5
                        )
                ),
                auditRows
        );
    }

    @Test
    void shouldAllowOnlyOneConcurrentStatusChange()
            throws Exception {
        String password = "StrongPassword123!";

        BootstrapTenantResponse bootstrap =
                bootstrapTenant(
                        "ticket-status-concurrent",
                        "Ticket Status Concurrent",
                        "concurrent-admin@integration.example",
                        password
                );

        LoginResponse login = login(
                "ticket-status-concurrent",
                password
        );

        CreateTicketResponse ticket = createTicket(
                bearerHeaders(login),
                "Concurrent status ticket"
        );

        long tenantId = Long.parseLong(
                bootstrap.tenantId()
        );

        long userId = Long.parseLong(
                bootstrap.adminUserId()
        );

        long ticketId = Long.parseLong(
                ticket.ticketId()
        );

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        CountDownLatch ready =
                new CountDownLatch(2);

        CountDownLatch start =
                new CountDownLatch(1);

        Future<ResponseEntity<String>> firstFuture =
                null;

        Future<ResponseEntity<String>> secondFuture =
                null;

        try {
            Callable<ResponseEntity<String>> request =
                    () -> {
                        ready.countDown();

                        if (!start.await(
                                10,
                                TimeUnit.SECONDS
                        )) {
                            throw new IllegalStateException(
                                    "Concurrent requests "
                                            + "did not start in time"
                            );
                        }

                        return changeStatus(
                                bearerHeaders(login),
                                ticket.ticketNo(),
                                TicketStatus.IN_PROGRESS,
                                0,
                                String.class
                        );
                    };

            firstFuture = executor.submit(request);
            secondFuture = executor.submit(request);

            assertTrue(
                    ready.await(
                            10,
                            TimeUnit.SECONDS
                    ),
                    "Both requests must become ready"
            );

            start.countDown();

            ResponseEntity<String> firstResponse =
                    firstFuture.get(
                            15,
                            TimeUnit.SECONDS
                    );

            ResponseEntity<String> secondResponse =
                    secondFuture.get(
                            15,
                            TimeUnit.SECONDS
                    );

            List<Integer> statusCodes =
                    List.of(
                                    firstResponse
                                            .getStatusCode()
                                            .value(),
                                    secondResponse
                                            .getStatusCode()
                                            .value()
                            )
                            .stream()
                            .sorted()
                            .toList();

            assertEquals(
                    List.of(
                            HttpStatus.OK.value(),
                            HttpStatus.CONFLICT.value()
                    ),
                    statusCodes
            );

            ResponseEntity<String> conflictResponse =
                    firstResponse.getStatusCode()
                            == HttpStatus.CONFLICT
                            ? firstResponse
                            : secondResponse;

            assertProblem(
                    conflictResponse,
                    HttpStatus.CONFLICT,
                    "TICKET_VERSION_CONFLICT"
            );

            assertPersistedState(
                    tenantId,
                    ticketId,
                    TicketStatus.IN_PROGRESS,
                    1,
                    false,
                    1
            );

            assertEquals(
                    List.of(audit(
                            userId,
                            ticketId,
                            "OPEN",
                            0,
                            "IN_PROGRESS",
                            1
                    )),
                    readStatusAuditRows(
                            tenantId,
                            ticketId
                    )
            );
        } finally {
            // 即使主线程提前失败，也要释放工作线程。
            start.countDown();

            if (firstFuture != null
                    && !firstFuture.isDone()) {
                firstFuture.cancel(true);
            }

            if (secondFuture != null
                    && !secondFuture.isDone()) {
                secondFuture.cancel(true);
            }

            executor.shutdownNow();

            assertTrue(
                    executor.awaitTermination(
                            5,
                            TimeUnit.SECONDS
                    ),
                    "Executor must terminate"
            );
        }
    }

    @Test
    void shouldRollbackStatusWhenAuditWriteFails() {
        String password = "StrongPassword123!";

        BootstrapTenantResponse bootstrap =
                bootstrapTenant(
                        "ticket-status-rollback",
                        "Ticket Status Rollback",
                        "status-rollback-admin@integration.example",
                        password
                );

        LoginResponse login = login(
                "ticket-status-rollback",
                password
        );

        CreateTicketResponse ticket = createTicket(
                bearerHeaders(login),
                "Status rollback ticket"
        );

        long tenantId = Long.parseLong(
                bootstrap.tenantId()
        );

        long ticketId = Long.parseLong(
                ticket.ticketId()
        );

        /*
         * 必须在 bootstrap、login 和 createTicket
         * 全部成功后再安装异常桩。
         */
        AuditLogWriter auditLogWriterTarget =
                AopTestUtils.getUltimateTargetObject(
                        auditLogWriter
                );

        doThrow(new IllegalStateException(
                "Simulated status audit write failure"
        ))
                .when(auditLogWriterTarget)
                .write(argThat(command ->
                        command != null
                                && command.tenantId()
                                == tenantId
                                && "TICKET_STATUS_CHANGED"
                                .equals(command.action())
                                && Long.valueOf(ticketId)
                                .equals(
                                        command.resourceId()
                                )
                ));

        ResponseEntity<String> response =
                changeStatus(
                        bearerHeaders(login),
                        ticket.ticketNo(),
                        TicketStatus.IN_PROGRESS,
                        0,
                        String.class
                );

        assertTrue(
                response.getStatusCode()
                        .is5xxServerError()
        );

        verify(auditLogWriterTarget)
                .write(argThat(command ->
                        command != null
                                && command.tenantId()
                                == tenantId
                                && "TICKET_STATUS_CHANGED"
                                .equals(command.action())
                                && Long.valueOf(ticketId)
                                .equals(
                                        command.resourceId()
                                )
                ));

        /*
         * UPDATE 已经执行过，但审计写入异常必须
         * 令整个事务回滚。
         */
        assertPersistedState(
                tenantId,
                ticketId,
                TicketStatus.OPEN,
                0,
                false,
                0
        );
    }

    private BootstrapTenantResponse bootstrapTenant(
            String tenantCode,
            String tenantName,
            String email,
            String password
    ) {
        ResponseEntity<BootstrapTenantResponse> response =
                restTemplate.postForEntity(
                        "/api/v1/tenants/bootstrap",
                        new BootstrapTenantRequest(
                                tenantCode,
                                tenantName,
                                "admin",
                                email,
                                password
                        ),
                        BootstrapTenantResponse.class
                );

        return requireBody(
                response,
                HttpStatus.CREATED
        );
    }

    private LoginResponse login(
            String tenantCode,
            String password
    ) {
        ResponseEntity<LoginResponse> response =
                restTemplate.postForEntity(
                        "/api/v1/auth/login",
                        new LoginRequest(
                                tenantCode,
                                "admin",
                                password
                        ),
                        LoginResponse.class
                );

        return requireBody(
                response,
                HttpStatus.OK
        );
    }

    private CreateTicketResponse createTicket(
            HttpHeaders headers,
            String title
    ) {
        ResponseEntity<CreateTicketResponse> response =
                restTemplate.exchange(
                        "/api/v1/tickets",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                new CreateTicketRequest(
                                        title,
                                        "Lifecycle integration test",
                                        TicketPriority.HIGH
                                ),
                                jsonHeaders(headers)
                        ),
                        CreateTicketResponse.class
                );

        return requireBody(
                response,
                HttpStatus.CREATED
        );
    }

    private <T> ResponseEntity<T> changeStatus(
            HttpHeaders headers,
            String ticketNo,
            TicketStatus targetStatus,
            int expectedVersion,
            Class<T> responseType
    ) {
        return restTemplate.exchange(
                "/api/v1/tickets/{ticketNo}/status",
                HttpMethod.PATCH,
                new HttpEntity<>(
                        new ChangeTicketStatusRequest(
                                targetStatus,
                                expectedVersion
                        ),
                        jsonHeaders(headers)
                ),
                responseType,
                ticketNo
        );
    }

    private void assertPersistedState(
            long tenantId,
            long ticketId,
            TicketStatus expectedStatus,
            int expectedVersion,
            boolean expectedClosed,
            long expectedAuditCount
    ) {
        TicketDatabaseState state =
                readTicketState(
                        tenantId,
                        ticketId
                );

        long auditCount =
                countStatusAudits(
                        tenantId,
                        ticketId
                );

        assertAll(
                () -> assertEquals(
                        expectedStatus,
                        state.status()
                ),
                () -> assertEquals(
                        expectedVersion,
                        state.version()
                ),
                () -> {
                    if (expectedClosed) {
                        assertNotNull(state.closedAt());
                    } else {
                        assertNull(state.closedAt());
                    }
                },
                () -> assertEquals(
                        expectedAuditCount,
                        auditCount
                )
        );
    }

    private TicketDatabaseState readTicketState(
            long tenantId,
            long ticketId
    ) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    status,
                    version,
                    closed_at
                FROM tickets
                WHERE tenant_id = ?
                  AND id = ?
                """,
                (resultSet, rowNumber) ->
                        new TicketDatabaseState(
                                TicketStatus.valueOf(
                                        resultSet.getString(
                                                "status"
                                        )
                                ),
                                resultSet.getInt(
                                        "version"
                                ),
                                resultSet.getTimestamp(
                                        "closed_at"
                                )
                        ),
                tenantId,
                ticketId
        );
    }

    private long countStatusAudits(
            long tenantId,
            long ticketId
    ) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_logs
                WHERE tenant_id = ?
                  AND action = 'TICKET_STATUS_CHANGED'
                  AND resource_type = 'TICKET'
                  AND resource_id = ?
                """,
                Long.class,
                tenantId,
                ticketId
        );

        return count == null ? 0L : count;
    }

    private List<StatusAuditRow> readStatusAuditRows(
            long tenantId,
            long ticketId
    ) {
        return jdbcTemplate.query(
                """
                SELECT
                    actor_type,
                    actor_id,
                    action,
                    resource_type,
                    resource_id,
                    result,
                    JSON_UNQUOTE(
                        JSON_EXTRACT(
                            before_json,
                            '$.status'
                        )
                    ) AS before_status,
                    CAST(
                        JSON_UNQUOTE(
                            JSON_EXTRACT(
                                before_json,
                                '$.version'
                            )
                        ) AS UNSIGNED
                    ) AS before_version,
                    JSON_UNQUOTE(
                        JSON_EXTRACT(
                            after_json,
                            '$.status'
                        )
                    ) AS after_status,
                    CAST(
                        JSON_UNQUOTE(
                            JSON_EXTRACT(
                                after_json,
                                '$.version'
                            )
                        ) AS UNSIGNED
                    ) AS after_version
                FROM audit_logs
                WHERE tenant_id = ?
                  AND action = 'TICKET_STATUS_CHANGED'
                  AND resource_id = ?
                ORDER BY created_at, id
                """,
                (resultSet, rowNumber) ->
                        new StatusAuditRow(
                                resultSet.getString(
                                        "actor_type"
                                ),
                                resultSet.getLong(
                                        "actor_id"
                                ),
                                resultSet.getString(
                                        "action"
                                ),
                                resultSet.getString(
                                        "resource_type"
                                ),
                                resultSet.getLong(
                                        "resource_id"
                                ),
                                resultSet.getString(
                                        "result"
                                ),
                                resultSet.getString(
                                        "before_status"
                                ),
                                resultSet.getInt(
                                        "before_version"
                                ),
                                resultSet.getString(
                                        "after_status"
                                ),
                                resultSet.getInt(
                                        "after_version"
                                )
                        ),
                tenantId,
                ticketId
        );
    }

    private static void assertChangeResponse(
            ChangeTicketStatusResponse response,
            CreateTicketResponse ticket,
            TicketStatus previousStatus,
            TicketStatus currentStatus,
            int expectedVersion,
            boolean expectedClosed
    ) {
        assertAll(
                () -> assertEquals(
                        ticket.ticketId(),
                        response.ticketId()
                ),
                () -> assertEquals(
                        ticket.ticketNo(),
                        response.ticketNo()
                ),
                () -> assertEquals(
                        previousStatus,
                        response.previousStatus()
                ),
                () -> assertEquals(
                        currentStatus,
                        response.currentStatus()
                ),
                () -> assertEquals(
                        expectedVersion,
                        response.version()
                ),
                () -> {
                    if (expectedClosed) {
                        assertNotNull(response.closedAt());
                    } else {
                        assertNull(response.closedAt());
                    }
                },
                () -> assertNotNull(
                        response.updatedAt()
                )
        );
    }

    private static void assertProblem(
            ResponseEntity<String> response,
            HttpStatus expectedStatus,
            String expectedErrorCode
    ) {
        String body = response.getBody();

        assertAll(
                () -> assertEquals(
                        expectedStatus,
                        response.getStatusCode()
                ),
                () -> assertNotNull(body),
                () -> assertTrue(
                        body != null
                                && body.contains(
                                expectedErrorCode
                        )
                )
        );
    }

    private static <T> T requireBody(
            ResponseEntity<T> response,
            HttpStatus expectedStatus
    ) {
        assertEquals(
                expectedStatus,
                response.getStatusCode()
        );
        assertNotNull(response.getBody());

        return response.getBody();
    }

    private static HttpHeaders bearerHeaders(
            LoginResponse login
    ) {
        HttpHeaders headers = new HttpHeaders();

        headers.setBearerAuth(
                login.accessToken()
        );

        return headers;
    }

    private static HttpHeaders jsonHeaders(
            HttpHeaders source
    ) {
        HttpHeaders headers = new HttpHeaders();

        headers.putAll(source);
        headers.setContentType(
                MediaType.APPLICATION_JSON
        );

        return headers;
    }

    private static StatusAuditRow audit(
            long actorId,
            long resourceId,
            String beforeStatus,
            int beforeVersion,
            String afterStatus,
            int afterVersion
    ) {
        return new StatusAuditRow(
                "USER",
                actorId,
                "TICKET_STATUS_CHANGED",
                "TICKET",
                resourceId,
                "SUCCESS",
                beforeStatus,
                beforeVersion,
                afterStatus,
                afterVersion
        );
    }

    private record TicketDatabaseState(
            TicketStatus status,
            int version,
            Timestamp closedAt
    ) {
    }

    private record StatusAuditRow(
            String actorType,
            long actorId,
            String action,
            String resourceType,
            long resourceId,
            String result,
            String beforeStatus,
            int beforeVersion,
            String afterStatus,
            int afterVersion
    ) {
    }
}