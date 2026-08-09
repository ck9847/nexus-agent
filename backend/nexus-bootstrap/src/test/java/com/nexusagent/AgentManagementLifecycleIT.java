package com.nexusagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.agent.api.AgentDetailResponse;
import com.nexusagent.agent.api.ChangeAgentStatusRequest;
import com.nexusagent.agent.api.CreateAgentRequest;
import com.nexusagent.agent.api.CreateAgentResponse;
import com.nexusagent.agent.domain.AgentModelConfig;
import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.agent.domain.AgentStatus;
import com.nexusagent.identity.api.BootstrapTenantRequest;
import com.nexusagent.identity.api.BootstrapTenantResponse;
import com.nexusagent.identity.api.LoginRequest;
import com.nexusagent.identity.api.LoginResponse;
import com.nexusagent.identity.spi.AccessTokenIssuer;
import com.nexusagent.identity.spi.IssuedAccessToken;
import com.nexusagent.identity.spi.TokenSubject;
import com.nexusagent.agent.api.ChangeAgentStatusResponse;
import com.nexusagent.audit.api.AuditLogWriter;
import org.springframework.test.context.bean.override.mockito.MockReset;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
class AgentManagementLifecycleIT {

    private static final String PASSWORD =
            "StrongPassword123!";

    private static final AgentModelConfig FULL_CONFIG =
            new AgentModelConfig(
                    new BigDecimal("0.2"),
                    new BigDecimal("0.9"),
                    2_048
            );

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

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccessTokenIssuer accessTokenIssuer;

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
    void shouldEnforceAuthenticationAdministratorAndTenantIsolation()
            throws Exception {
        TenantSession owner = bootstrapAndLogin(
                "agent-management-owner"
        );

        TenantSession outsider = bootstrapAndLogin(
                "agent-management-outsider"
        );

        String code = "owner-support-agent";

        CreateAgentResponse created = requireBody(
                createAgent(
                        owner,
                        agentRequest(
                                code,
                                FULL_CONFIG
                        )
                )
        );

        long agentId =
                Long.parseLong(created.agentId());

        ResponseEntity<String> unauthorizedGet =
                restTemplate.getForEntity(
                        "/api/v1/agents/{agentCode}",
                        String.class,
                        code
                );

        ResponseEntity<String> unauthorizedPatch =
                restTemplate.exchange(
                        "/api/v1/agents/{agentCode}/status",
                        HttpMethod.PATCH,
                        new HttpEntity<>(
                                "{}",
                                jsonHeaders()
                        ),
                        String.class,
                        code
                );

        IssuedAccessToken memberToken =
                accessTokenIssuer.issue(
                        new TokenSubject(
                                owner.adminUserId(),
                                owner.tenantId(),
                                "admin",
                                List.of("MEMBER")
                        )
                );

        ResponseEntity<String> memberGet =
                restTemplate.exchange(
                        "/api/v1/agents/{agentCode}",
                        HttpMethod.GET,
                        new HttpEntity<>(
                                bearerHeaders(
                                        memberToken.value()
                                )
                        ),
                        String.class,
                        code
                );

        ResponseEntity<String> memberPatch =
                restTemplate.exchange(
                        "/api/v1/agents/{agentCode}/status",
                        HttpMethod.PATCH,
                        new HttpEntity<>(
                                "{}",
                                bearerHeaders(
                                        memberToken.value()
                                )
                        ),
                        String.class,
                        code
                );

        ResponseEntity<String> outsiderGet =
                restTemplate.exchange(
                        "/api/v1/agents/{agentCode}",
                        HttpMethod.GET,
                        new HttpEntity<>(
                                bearerHeaders(
                                        outsider.accessToken()
                                )
                        ),
                        String.class,
                        code
                );

        ResponseEntity<String> outsiderPatch =
                restTemplate.exchange(
                        "/api/v1/agents/{agentCode}/status",
                        HttpMethod.PATCH,
                        new HttpEntity<>(
                                new ChangeAgentStatusRequest(
                                        AgentStatus.ACTIVE,
                                        0
                                ),
                                bearerHeaders(
                                        outsider.accessToken()
                                )
                        ),
                        String.class,
                        code
                );

        assertAll(
                () -> assertEquals(
                        HttpStatus.UNAUTHORIZED,
                        unauthorizedGet.getStatusCode()
                ),
                () -> assertEquals(
                        HttpStatus.UNAUTHORIZED,
                        unauthorizedPatch.getStatusCode()
                ),
                () -> assertEquals(
                        HttpStatus.FORBIDDEN,
                        memberGet.getStatusCode()
                ),
                () -> assertEquals(
                        HttpStatus.FORBIDDEN,
                        memberPatch.getStatusCode()
                ),
                () -> assertEquals(
                        HttpStatus.NOT_FOUND,
                        outsiderGet.getStatusCode()
                ),
                () -> assertEquals(
                        HttpStatus.NOT_FOUND,
                        outsiderPatch.getStatusCode()
                )
        );

        JsonNode getProblem =
                parseProblem(
                        outsiderGet,
                        "AGENT_NOT_FOUND"
                );

        JsonNode patchProblem =
                parseProblem(
                        outsiderPatch,
                        "AGENT_NOT_FOUND"
                );

        assertAll(
                () -> assertFalse(
                        getProblem.has("agentCode")
                ),
                () -> assertFalse(
                        patchProblem.has("agentCode")
                )
        );

        AgentDatabaseState state =
                readAgentState(
                        owner.tenantId(),
                        code
                );

        assertAll(
                () -> assertEquals(
                        AgentStatus.DRAFT.name(),
                        state.status()
                ),
                () -> assertEquals(
                        0,
                        state.version()
                ),
                () -> assertEquals(
                        0L,
                        countStatusAudits(
                                owner.tenantId(),
                                agentId
                        )
                )
        );
    }

    @Test
    void shouldReturnCompleteDetailsAndPreserveSqlNullModelConfig()
            throws Exception {
        TenantSession tenant = bootstrapAndLogin(
                "agent-detail-acme"
        );

        String configuredCode =
                "configured-agent";

        String nullConfigCode =
                "null-config-agent";

        CreateAgentRequest configuredRequest =
                agentRequest(
                        configuredCode,
                        FULL_CONFIG
                );

        CreateAgentRequest nullConfigRequest =
                agentRequest(
                        nullConfigCode,
                        null
                );

        CreateAgentResponse configuredCreated =
                requireBody(
                        createAgent(
                                tenant,
                                configuredRequest
                        )
                );

        CreateAgentResponse nullConfigCreated =
                requireBody(
                        createAgent(
                                tenant,
                                nullConfigRequest
                        )
                );

        ResponseEntity<AgentDetailResponse>
                configuredResponse =
                restTemplate.exchange(
                        "/api/v1/agents/{agentCode}",
                        HttpMethod.GET,
                        new HttpEntity<>(
                                bearerHeaders(
                                        tenant.accessToken()
                                )
                        ),
                        AgentDetailResponse.class,
                        configuredCode
                );

        assertEquals(
                HttpStatus.OK,
                configuredResponse.getStatusCode()
        );

        AgentDetailResponse detail =
                requireBody(configuredResponse);

        assertAll(
                () -> assertEquals(
                        configuredCreated.agentId(),
                        detail.agentId()
                ),
                () -> assertEquals(
                        configuredRequest.code(),
                        detail.code()
                ),
                () -> assertEquals(
                        configuredRequest.name(),
                        detail.name()
                ),
                () -> assertEquals(
                        configuredRequest.description(),
                        detail.description()
                ),
                () -> assertEquals(
                        configuredRequest.systemPrompt(),
                        detail.systemPrompt()
                ),
                () -> assertEquals(
                        AgentModelProvider.OPENAI,
                        detail.modelProvider()
                ),
                () -> assertEquals(
                        configuredRequest.modelName(),
                        detail.modelName()
                ),
                () -> assertEquals(
                        FULL_CONFIG,
                        detail.modelConfig()
                ),
                () -> assertEquals(
                        AgentStatus.DRAFT,
                        detail.status()
                ),
                () -> assertEquals(
                        Long.toString(
                                tenant.adminUserId()
                        ),
                        detail.createdByUserId()
                ),
                () -> assertEquals(
                        0,
                        detail.version()
                ),
                () -> assertNotNull(
                        detail.createdAt()
                ),
                () -> assertNotNull(
                        detail.updatedAt()
                ),
                () -> assertFalse(
                        detail.updatedAt().isBefore(
                                detail.createdAt()
                        )
                )
        );

        ResponseEntity<String> nullConfigResponse =
                restTemplate.exchange(
                        "/api/v1/agents/{agentCode}",
                        HttpMethod.GET,
                        new HttpEntity<>(
                                bearerHeaders(
                                        tenant.accessToken()
                                )
                        ),
                        String.class,
                        nullConfigCode
                );

        assertEquals(
                HttpStatus.OK,
                nullConfigResponse.getStatusCode()
        );

        JsonNode nullConfigJson =
                objectMapper.readTree(
                        requireBody(
                                nullConfigResponse
                        )
                );

        assertAll(
                () -> assertEquals(
                        nullConfigCreated.agentId(),
                        nullConfigJson.path(
                                "agentId"
                        ).asText()
                ),
                () -> assertTrue(
                        nullConfigJson.has(
                                "modelConfig"
                        )
                ),
                () -> assertTrue(
                        nullConfigJson.get(
                                "modelConfig"
                        ).isNull()
                )
        );

        Integer modelConfigIsNull =
                jdbcTemplate.queryForObject(
                        """
                        SELECT model_config IS NULL
                        FROM agents
                        WHERE tenant_id = ?
                          AND code = ?
                        """,
                        Integer.class,
                        tenant.tenantId(),
                        nullConfigCode
                );

        assertAll(
                () -> assertEquals(
                        1,
                        modelConfigIsNull
                ),
                () -> assertEquals(
                        0L,
                        countStatusAudits(
                                tenant.tenantId(),
                                Long.parseLong(
                                        configuredCreated.agentId()
                                )
                        )
                ),
                () -> assertEquals(
                        0L,
                        countStatusAudits(
                                tenant.tenantId(),
                                Long.parseLong(
                                        nullConfigCreated.agentId()
                                )
                        )
                )
        );
    }

    @Test
    void shouldCompleteLifecycleRejectStaleVersionAndWriteExactAudits()
            throws Exception {
        TenantSession tenant =
                bootstrapAndLogin("agent-lifecycle-acme");

        String code = "lifecycle-agent";

        CreateAgentResponse created = requireBody(
                createAgent(
                        tenant,
                        agentRequest(code, FULL_CONFIG)
                )
        );

        long agentId = Long.parseLong(created.agentId());

        assertPersistedAgentState(
                tenant.tenantId(),
                agentId,
                code,
                AgentStatus.DRAFT,
                0,
                0
        );

        ResponseEntity<String> invalid = changeStatus(
                tenant,
                code,
                AgentStatus.DISABLED,
                0,
                String.class
        );

        assertEquals(
                HttpStatus.CONFLICT,
                invalid.getStatusCode()
        );

        JsonNode invalidProblem = parseProblem(
                invalid,
                "INVALID_AGENT_STATUS_TRANSITION"
        );

        assertAll(
                () -> assertEquals(
                        "DRAFT",
                        invalidProblem.path("currentStatus").asText()
                ),
                () -> assertEquals(
                        "DISABLED",
                        invalidProblem.path("targetStatus").asText()
                )
        );

        assertPersistedAgentState(
                tenant.tenantId(),
                agentId,
                code,
                AgentStatus.DRAFT,
                0,
                0
        );

        ResponseEntity<ChangeAgentStatusResponse> activated =
                changeStatus(
                        tenant,
                        code,
                        AgentStatus.ACTIVE,
                        0,
                        ChangeAgentStatusResponse.class
                );

        assertEquals(HttpStatus.OK, activated.getStatusCode());

        assertChangeResponse(
                requireBody(activated),
                created,
                code,
                AgentStatus.DRAFT,
                AgentStatus.ACTIVE,
                1
        );

        assertPersistedAgentState(
                tenant.tenantId(),
                agentId,
                code,
                AgentStatus.ACTIVE,
                1,
                1
        );

        ResponseEntity<String> stale = changeStatus(
                tenant,
                code,
                AgentStatus.DISABLED,
                0,
                String.class
        );

        assertEquals(HttpStatus.CONFLICT, stale.getStatusCode());

        parseProblem(
                stale,
                "AGENT_VERSION_CONFLICT"
        );

        assertPersistedAgentState(
                tenant.tenantId(),
                agentId,
                code,
                AgentStatus.ACTIVE,
                1,
                1
        );

        ResponseEntity<ChangeAgentStatusResponse> disabled =
                changeStatus(
                        tenant,
                        code,
                        AgentStatus.DISABLED,
                        1,
                        ChangeAgentStatusResponse.class
                );

        assertEquals(HttpStatus.OK, disabled.getStatusCode());

        assertChangeResponse(
                requireBody(disabled),
                created,
                code,
                AgentStatus.ACTIVE,
                AgentStatus.DISABLED,
                2
        );

        assertPersistedAgentState(
                tenant.tenantId(),
                agentId,
                code,
                AgentStatus.DISABLED,
                2,
                2
        );

        ResponseEntity<ChangeAgentStatusResponse> reactivated =
                changeStatus(
                        tenant,
                        code,
                        AgentStatus.ACTIVE,
                        2,
                        ChangeAgentStatusResponse.class
                );

        assertEquals(
                HttpStatus.OK,
                reactivated.getStatusCode()
        );

        assertChangeResponse(
                requireBody(reactivated),
                created,
                code,
                AgentStatus.DISABLED,
                AgentStatus.ACTIVE,
                3
        );

        assertPersistedAgentState(
                tenant.tenantId(),
                agentId,
                code,
                AgentStatus.ACTIVE,
                3,
                3
        );

        assertEquals(
                List.of(
                        statusAudit(
                                tenant.adminUserId(),
                                agentId,
                                code,
                                AgentStatus.DRAFT,
                                0,
                                AgentStatus.ACTIVE,
                                1
                        ),
                        statusAudit(
                                tenant.adminUserId(),
                                agentId,
                                code,
                                AgentStatus.ACTIVE,
                                1,
                                AgentStatus.DISABLED,
                                2
                        ),
                        statusAudit(
                                tenant.adminUserId(),
                                agentId,
                                code,
                                AgentStatus.DISABLED,
                                2,
                                AgentStatus.ACTIVE,
                                3
                        )
                ),
                readStatusAuditRows(
                        tenant.tenantId(),
                        agentId
                )
        );
    }

    @Test
    void shouldAllowOnlyOneConcurrentActivation()
            throws Exception {
        TenantSession tenant =
                bootstrapAndLogin("agent-concurrent-acme");

        String code = "concurrent-agent";

        CreateAgentResponse created = requireBody(
                createAgent(
                        tenant,
                        agentRequest(code, FULL_CONFIG)
                )
        );

        long agentId = Long.parseLong(created.agentId());

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<ResponseEntity<String>> firstFuture = null;
        Future<ResponseEntity<String>> secondFuture = null;

        try {
            Callable<ResponseEntity<String>> request = () -> {
                ready.countDown();

                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Concurrent requests did not start in time"
                    );
                }

                return changeStatus(
                        tenant,
                        code,
                        AgentStatus.ACTIVE,
                        0,
                        String.class
                );
            };

            firstFuture = executor.submit(request);
            secondFuture = executor.submit(request);

            assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "Both requests must become ready"
            );

            start.countDown();

            ResponseEntity<String> first =
                    firstFuture.get(15, TimeUnit.SECONDS);

            ResponseEntity<String> second =
                    secondFuture.get(15, TimeUnit.SECONDS);

            List<Integer> statusCodes = List.of(
                            first.getStatusCode().value(),
                            second.getStatusCode().value()
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

            ResponseEntity<String> conflict =
                    first.getStatusCode() == HttpStatus.CONFLICT
                            ? first
                            : second;

            parseProblem(
                    conflict,
                    "AGENT_VERSION_CONFLICT"
            );

            ResponseEntity<String> success =
                    first.getStatusCode() == HttpStatus.OK
                            ? first
                            : second;

            ChangeAgentStatusResponse successBody =
                    objectMapper.readValue(
                            requireBody(success),
                            ChangeAgentStatusResponse.class
                    );

            assertChangeResponse(
                    successBody,
                    created,
                    code,
                    AgentStatus.DRAFT,
                    AgentStatus.ACTIVE,
                    1
            );

            assertPersistedAgentState(
                    tenant.tenantId(),
                    agentId,
                    code,
                    AgentStatus.ACTIVE,
                    1,
                    1
            );

            assertEquals(
                    List.of(statusAudit(
                            tenant.adminUserId(),
                            agentId,
                            code,
                            AgentStatus.DRAFT,
                            0,
                            AgentStatus.ACTIVE,
                            1
                    )),
                    readStatusAuditRows(
                            tenant.tenantId(),
                            agentId
                    )
            );
        } finally {
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
        TenantSession tenant =
                bootstrapAndLogin("agent-rollback-acme");

        String code = "rollback-agent";

        CreateAgentResponse created = requireBody(
                createAgent(
                        tenant,
                        agentRequest(code, FULL_CONFIG)
                )
        );

        long agentId = Long.parseLong(created.agentId());

        /*
         * 必须等 Agent 创建完成后再安装异常桩，
         * 否则 AGENT_CREATED 审计也会受到影响。
         */
        AuditLogWriter target =
                AopTestUtils.getUltimateTargetObject(
                        auditLogWriter
                );

        doThrow(new IllegalStateException(
                "Simulated agent status audit failure"
        ))
                .when(target)
                .write(argThat(command ->
                        command != null
                                && command.tenantId()
                                == tenant.tenantId()
                                && "AGENT_STATUS_CHANGED"
                                .equals(command.action())
                                && Long.valueOf(agentId)
                                .equals(command.resourceId())
                ));

        ResponseEntity<String> response = changeStatus(
                tenant,
                code,
                AgentStatus.ACTIVE,
                0,
                String.class
        );

        assertTrue(
                response.getStatusCode().is5xxServerError()
        );

        verify(target).write(argThat(command ->
                command != null
                        && command.tenantId()
                        == tenant.tenantId()
                        && "AGENT_STATUS_CHANGED"
                        .equals(command.action())
                        && Long.valueOf(agentId)
                        .equals(command.resourceId())
        ));

        assertPersistedAgentState(
                tenant.tenantId(),
                agentId,
                code,
                AgentStatus.DRAFT,
                0,
                0
        );
    }

    private TenantSession bootstrapAndLogin(
            String tenantCode
    ) {
        ResponseEntity<BootstrapTenantResponse> bootstrap =
                restTemplate.postForEntity(
                        "/api/v1/tenants/bootstrap",
                        new BootstrapTenantRequest(
                                tenantCode,
                                tenantCode + " Tenant",
                                "admin",
                                tenantCode
                                        + "@integration.example",
                                PASSWORD
                        ),
                        BootstrapTenantResponse.class
                );

        assertEquals(
                HttpStatus.CREATED,
                bootstrap.getStatusCode()
        );

        BootstrapTenantResponse bootstrapBody =
                requireBody(bootstrap);

        ResponseEntity<LoginResponse> login =
                restTemplate.postForEntity(
                        "/api/v1/auth/login",
                        new LoginRequest(
                                tenantCode,
                                "admin",
                                PASSWORD
                        ),
                        LoginResponse.class
                );

        assertEquals(
                HttpStatus.OK,
                login.getStatusCode()
        );

        LoginResponse loginBody =
                requireBody(login);

        assertEquals(
                List.of("ADMIN"),
                loginBody.roles()
        );

        return new TenantSession(
                Long.parseLong(
                        bootstrapBody.tenantId()
                ),
                Long.parseLong(
                        bootstrapBody.adminUserId()
                ),
                loginBody.accessToken()
        );
    }

    private ResponseEntity<CreateAgentResponse>
    createAgent(
            TenantSession tenant,
            CreateAgentRequest request
    ) {
        ResponseEntity<CreateAgentResponse> response =
                restTemplate.exchange(
                        "/api/v1/agents",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                request,
                                bearerHeaders(
                                        tenant.accessToken()
                                )
                        ),
                        CreateAgentResponse.class
                );

        assertEquals(
                HttpStatus.CREATED,
                response.getStatusCode()
        );

        return response;
    }

    private <T> ResponseEntity<T> changeStatus(
            TenantSession tenant,
            String code,
            AgentStatus targetStatus,
            int expectedVersion,
            Class<T> responseType
    ) {
        return restTemplate.exchange(
                "/api/v1/agents/{agentCode}/status",
                HttpMethod.PATCH,
                new HttpEntity<>(
                        new ChangeAgentStatusRequest(
                                targetStatus,
                                expectedVersion
                        ),
                        bearerHeaders(
                                tenant.accessToken()
                        )
                ),
                responseType,
                code
        );
    }

    private void assertPersistedAgentState(
            long tenantId,
            long agentId,
            String code,
            AgentStatus expectedStatus,
            int expectedVersion,
            long expectedAuditCount
    ) {
        AgentDatabaseState state =
                readAgentState(tenantId, code);

        assertAll(
                () -> assertEquals(
                        expectedStatus.name(),
                        state.status()
                ),
                () -> assertEquals(
                        expectedVersion,
                        state.version()
                ),
                () -> assertEquals(
                        expectedAuditCount,
                        countStatusAudits(
                                tenantId,
                                agentId
                        )
                )
        );
    }

    private static void assertChangeResponse(
            ChangeAgentStatusResponse response,
            CreateAgentResponse created,
            String code,
            AgentStatus previousStatus,
            AgentStatus currentStatus,
            int expectedVersion
    ) {
        assertAll(
                () -> assertEquals(
                        created.agentId(),
                        response.agentId()
                ),
                () -> assertEquals(
                        code,
                        response.code()
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
                () -> assertNotNull(
                        response.updatedAt()
                )
        );
    }

    private List<AgentStatusAuditRow> readStatusAuditRows(
            long tenantId,
            long agentId
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
                        JSON_EXTRACT(before_json, '$.code')
                    ) AS before_code,
                    JSON_UNQUOTE(
                        JSON_EXTRACT(before_json, '$.status')
                    ) AS before_status,
                    CAST(
                        JSON_UNQUOTE(
                            JSON_EXTRACT(
                                before_json,
                                '$.version'
                            )
                        ) AS UNSIGNED
                    ) AS before_version,
                    JSON_LENGTH(before_json)
                        AS before_key_count,
                    JSON_UNQUOTE(
                        JSON_EXTRACT(after_json, '$.code')
                    ) AS after_code,
                    JSON_UNQUOTE(
                        JSON_EXTRACT(after_json, '$.status')
                    ) AS after_status,
                    CAST(
                        JSON_UNQUOTE(
                            JSON_EXTRACT(
                                after_json,
                                '$.version'
                            )
                        ) AS UNSIGNED
                    ) AS after_version,
                    JSON_LENGTH(after_json)
                        AS after_key_count
                FROM audit_logs
                WHERE tenant_id = ?
                  AND action = 'AGENT_STATUS_CHANGED'
                  AND resource_type = 'AGENT'
                  AND resource_id = ?
                ORDER BY created_at, id
                """,
                (resultSet, rowNumber) ->
                        new AgentStatusAuditRow(
                                resultSet.getString("actor_type"),
                                resultSet.getLong("actor_id"),
                                resultSet.getString("action"),
                                resultSet.getString("resource_type"),
                                resultSet.getLong("resource_id"),
                                resultSet.getString("result"),
                                resultSet.getString("before_code"),
                                resultSet.getString("before_status"),
                                resultSet.getInt("before_version"),
                                resultSet.getInt("before_key_count"),
                                resultSet.getString("after_code"),
                                resultSet.getString("after_status"),
                                resultSet.getInt("after_version"),
                                resultSet.getInt("after_key_count")
                        ),
                tenantId,
                agentId
        );
    }

    private static AgentStatusAuditRow statusAudit(
            long actorId,
            long resourceId,
            String code,
            AgentStatus beforeStatus,
            int beforeVersion,
            AgentStatus afterStatus,
            int afterVersion
    ) {
        return new AgentStatusAuditRow(
                "USER",
                actorId,
                "AGENT_STATUS_CHANGED",
                "AGENT",
                resourceId,
                "SUCCESS",
                code,
                beforeStatus.name(),
                beforeVersion,
                3,
                code,
                afterStatus.name(),
                afterVersion,
                3
        );
    }

    private JsonNode parseProblem(
            ResponseEntity<String> response,
            String expectedErrorCode
    ) throws Exception {
        JsonNode problem =
                objectMapper.readTree(
                        requireBody(response)
                );

        assertEquals(
                expectedErrorCode,
                problem.path("errorCode")
                        .asText()
        );

        return problem;
    }

    private AgentDatabaseState readAgentState(
            long tenantId,
            String code
    ) {
        return jdbcTemplate.queryForObject(
                """
                SELECT status, version
                FROM agents
                WHERE tenant_id = ?
                  AND code = ?
                """,
                (resultSet, rowNumber) ->
                        new AgentDatabaseState(
                                resultSet.getString(
                                        "status"
                                ),
                                resultSet.getInt(
                                        "version"
                                )
                        ),
                tenantId,
                code
        );
    }

    private long countStatusAudits(
            long tenantId,
            long agentId
    ) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_logs
                WHERE tenant_id = ?
                  AND action =
                      'AGENT_STATUS_CHANGED'
                  AND resource_type = 'AGENT'
                  AND resource_id = ?
                """,
                Long.class,
                tenantId,
                agentId
        );

        return count == null ? 0L : count;
    }

    private static CreateAgentRequest agentRequest(
            String code,
            AgentModelConfig modelConfig
    ) {
        return new CreateAgentRequest(
                code,
                code + " Management Agent",
                "Management integration agent.",
                "System prompt for " + code,
                AgentModelProvider.OPENAI,
                "gpt-5-mini",
                modelConfig
        );
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(
                MediaType.APPLICATION_JSON
        );
        return headers;
    }

    private static HttpHeaders bearerHeaders(
            String accessToken
    ) {
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }

    private static <T> T requireBody(
            ResponseEntity<T> response
    ) {
        T body = response.getBody();
        assertNotNull(body);
        return body;
    }

    private record TenantSession(
            long tenantId,
            long adminUserId,
            String accessToken
    ) {
    }

    private record AgentDatabaseState(
            String status,
            int version
    ) {
    }

    private record AgentStatusAuditRow(
            String actorType,
            long actorId,
            String action,
            String resourceType,
            long resourceId,
            String result,
            String beforeCode,
            String beforeStatus,
            int beforeVersion,
            int beforeKeyCount,
            String afterCode,
            String afterStatus,
            int afterVersion,
            int afterKeyCount
    ) {
    }
}