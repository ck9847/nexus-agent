package com.nexusagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.agent.api.CreateAgentRequest;
import com.nexusagent.agent.api.CreateAgentResponse;
import com.nexusagent.agent.domain.AgentModelConfig;
import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.agent.domain.AgentStatus;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.identity.api.BootstrapTenantRequest;
import com.nexusagent.identity.api.BootstrapTenantResponse;
import com.nexusagent.identity.api.LoginRequest;
import com.nexusagent.identity.api.LoginResponse;
import com.nexusagent.identity.spi.AccessTokenIssuer;
import com.nexusagent.identity.spi.IssuedAccessToken;
import com.nexusagent.identity.spi.TokenSubject;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

@Testcontainers
@SpringBootTest(
        webEnvironment =
                SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = """
                nexus.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=
                """
)
class AgentCreationIT {

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

    @MockitoSpyBean
    private AuditLogWriter auditLogWriter;

    @Test
    void shouldEnforceAuthenticationAndAdministratorRoleBeforeValidation()
            throws Exception {
        TenantSession tenant = bootstrapAndLogin(
                "agent-security-acme"
        );

        ResponseEntity<String> unauthorized =
                restTemplate.exchange(
                        "/api/v1/agents",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                "{}",
                                jsonHeaders()
                        ),
                        String.class
                );

        IssuedAccessToken memberToken =
                accessTokenIssuer.issue(
                        new TokenSubject(
                                tenant.adminUserId(),
                                tenant.tenantId(),
                                "admin",
                                List.of("MEMBER")
                        )
                );

        ResponseEntity<String> forbidden =
                restTemplate.exchange(
                        "/api/v1/agents",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                "{}",
                                bearerHeaders(
                                        memberToken.value()
                                )
                        ),
                        String.class
                );

        ResponseEntity<String> invalidAdminRequest =
                restTemplate.exchange(
                        "/api/v1/agents",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                "{}",
                                bearerHeaders(
                                        tenant.accessToken()
                                )
                        ),
                        String.class
                );

        assertAll(
                () -> assertEquals(
                        HttpStatus.UNAUTHORIZED,
                        unauthorized.getStatusCode()
                ),
                () -> assertEquals(
                        HttpStatus.FORBIDDEN,
                        forbidden.getStatusCode()
                ),
                () -> assertEquals(
                        HttpStatus.BAD_REQUEST,
                        invalidAdminRequest.getStatusCode()
                )
        );

        JsonNode invalidProblem =
                objectMapper.readTree(
                        requireBody(
                                invalidAdminRequest
                        )
                );

        assertEquals(
                "VALIDATION_FAILED",
                invalidProblem.path("errorCode")
                        .asText()
        );

        assertAll(
                () -> assertEquals(
                        0L,
                        countAgents(
                                tenant.tenantId(),
                                null
                        )
                ),
                () -> assertEquals(
                        0L,
                        countAgentAudits(
                                tenant.tenantId()
                        )
                )
        );
    }

    @Test
    void shouldCreateTenantScopedAgentWithJsonAndSafeAudit()
            throws Exception {
        TenantSession tenant = bootstrapAndLogin(
                "agent-persistence-acme"
        );

        String secretPrompt =
                "SYSTEM_PROMPT_MUST_NOT_APPEAR_IN_AUDIT";

        CreateAgentRequest request =
                new CreateAgentRequest(
                        "support-agent",
                        "Support Agent",
                        "Handles enterprise support requests.",
                        secretPrompt,
                        AgentModelProvider.OPENAI,
                        "gpt-5-mini",
                        FULL_CONFIG
                );

        ResponseEntity<CreateAgentResponse> response =
                createAgent(
                        request,
                        tenant.accessToken()
                );

        assertEquals(
                HttpStatus.CREATED,
                response.getStatusCode()
        );

        CreateAgentResponse body =
                requireBody(response);

        assertAll(
                () -> assertNotNull(body.agentId()),
                () -> assertEquals(
                        "support-agent",
                        body.code()
                ),
                () -> assertEquals(
                        AgentStatus.DRAFT,
                        body.status()
                ),
                () -> assertEquals(
                        0,
                        body.version()
                )
        );

        long agentId =
                Long.parseLong(body.agentId());

        Map<String, Object> agent =
                jdbcTemplate.queryForMap(
                        """
                        SELECT
                            tenant_id,
                            code,
                            name,
                            description,
                            system_prompt,
                            model_provider,
                            model_name,
                            JSON_TYPE(model_config)
                                AS model_config_type,
                            CAST(model_config AS CHAR)
                                AS model_config_json,
                            status,
                            created_by_user_id,
                            version
                        FROM agents
                        WHERE id = ?
                        """,
                        agentId
                );

        assertAll(
                () -> assertEquals(
                        tenant.tenantId(),
                        number(agent.get("tenant_id"))
                ),
                () -> assertEquals(
                        request.code(),
                        agent.get("code")
                ),
                () -> assertEquals(
                        request.name(),
                        agent.get("name")
                ),
                () -> assertEquals(
                        request.description(),
                        agent.get("description")
                ),
                () -> assertEquals(
                        secretPrompt,
                        agent.get("system_prompt")
                ),
                () -> assertEquals(
                        "OPENAI",
                        agent.get("model_provider")
                ),
                () -> assertEquals(
                        "gpt-5-mini",
                        agent.get("model_name")
                ),
                () -> assertEquals(
                        "OBJECT",
                        agent.get("model_config_type")
                ),
                () -> assertEquals(
                        "DRAFT",
                        agent.get("status")
                ),
                () -> assertEquals(
                        tenant.adminUserId(),
                        number(
                                agent.get(
                                        "created_by_user_id"
                                )
                        )
                ),
                () -> assertEquals(
                        0,
                        ((Number) agent.get("version"))
                                .intValue()
                )
        );

        JsonNode storedConfig =
                objectMapper.readTree(
                        String.valueOf(
                                agent.get(
                                        "model_config_json"
                                )
                        )
                );

        assertAll(
                () -> assertEquals(
                        3,
                        storedConfig.size()
                ),
                () -> assertTrue(
                        storedConfig.path("temperature")
                                .isNumber()
                ),
                () -> assertEquals(
                        new BigDecimal("0.2"),
                        storedConfig.path("temperature")
                                .decimalValue()
                ),
                () -> assertEquals(
                        new BigDecimal("0.9"),
                        storedConfig.path("topP")
                                .decimalValue()
                ),
                () -> assertEquals(
                        2_048,
                        storedConfig.path(
                                "maxOutputTokens"
                        ).asInt()
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
                            result,
                            before_json,
                            CAST(after_json AS CHAR)
                                AS after_json,
                            error_code,
                            error_message
                        FROM audit_logs
                        WHERE tenant_id = ?
                          AND action = 'AGENT_CREATED'
                          AND resource_id = ?
                        """,
                        tenant.tenantId(),
                        agentId
                );

        assertAll(
                () -> assertEquals(
                        tenant.tenantId(),
                        number(audit.get("tenant_id"))
                ),
                () -> assertEquals(
                        "USER",
                        audit.get("actor_type")
                ),
                () -> assertEquals(
                        tenant.adminUserId(),
                        number(audit.get("actor_id"))
                ),
                () -> assertEquals(
                        "AGENT_CREATED",
                        audit.get("action")
                ),
                () -> assertEquals(
                        "AGENT",
                        audit.get("resource_type")
                ),
                () -> assertEquals(
                        agentId,
                        number(audit.get("resource_id"))
                ),
                () -> assertEquals(
                        "SUCCESS",
                        audit.get("result")
                ),
                () -> assertNull(
                        audit.get("before_json")
                ),
                () -> assertNull(
                        audit.get("error_code")
                ),
                () -> assertNull(
                        audit.get("error_message")
                )
        );

        JsonNode after =
                objectMapper.readTree(
                        String.valueOf(
                                audit.get("after_json")
                        )
                );

        Set<String> fieldNames = new HashSet<>();
        after.fieldNames().forEachRemaining(
                fieldNames::add
        );

        assertAll(
                () -> assertEquals(
                        Set.of(
                                "code",
                                "name",
                                "modelProvider",
                                "modelName",
                                "status",
                                "version"
                        ),
                        fieldNames
                ),
                () -> assertEquals(
                        "support-agent",
                        after.path("code").asText()
                ),
                () -> assertEquals(
                        "Support Agent",
                        after.path("name").asText()
                ),
                () -> assertEquals(
                        "OPENAI",
                        after.path(
                                "modelProvider"
                        ).asText()
                ),
                () -> assertEquals(
                        "gpt-5-mini",
                        after.path("modelName")
                                .asText()
                ),
                () -> assertEquals(
                        "DRAFT",
                        after.path("status").asText()
                ),
                () -> assertEquals(
                        0,
                        after.path("version").asInt()
                ),
                () -> assertFalse(
                        after.toString()
                                .contains(secretPrompt)
                )
        );
    }

    @Test
    void shouldRejectSameTenantDuplicateButAllowSameCodeAcrossTenants()
            throws Exception {
        TenantSession owner = bootstrapAndLogin(
                "agent-code-owner"
        );

        TenantSession peer = bootstrapAndLogin(
                "agent-code-peer"
        );

        String code = "shared-support-agent";

        CreateAgentRequest ownerRequest =
                agentRequest(
                        code,
                        FULL_CONFIG
                );

        CreateAgentRequest peerRequest =
                agentRequest(
                        code,
                        null
                );

        ResponseEntity<CreateAgentResponse> ownerCreated =
                createAgent(
                        ownerRequest,
                        owner.accessToken()
                );

        ResponseEntity<String> ownerDuplicate =
                createAgentText(
                        ownerRequest,
                        owner.accessToken()
                );

        ResponseEntity<CreateAgentResponse> peerCreated =
                createAgent(
                        peerRequest,
                        peer.accessToken()
                );

        assertAll(
                () -> assertEquals(
                        HttpStatus.CREATED,
                        ownerCreated.getStatusCode()
                ),
                () -> assertEquals(
                        HttpStatus.CONFLICT,
                        ownerDuplicate.getStatusCode()
                ),
                () -> assertEquals(
                        HttpStatus.CREATED,
                        peerCreated.getStatusCode()
                )
        );

        CreateAgentResponse ownerBody =
                requireBody(ownerCreated);

        CreateAgentResponse peerBody =
                requireBody(peerCreated);

        assertNotEquals(
                ownerBody.agentId(),
                peerBody.agentId()
        );

        JsonNode duplicateProblem =
                objectMapper.readTree(
                        requireBody(ownerDuplicate)
                );

        assertAll(
                () -> assertEquals(
                        "AGENT_CODE_ALREADY_EXISTS",
                        duplicateProblem.path(
                                "errorCode"
                        ).asText()
                ),
                () -> assertEquals(
                        code,
                        duplicateProblem.path(
                                "agentCode"
                        ).asText()
                ),
                () -> assertEquals(
                        1L,
                        countAgents(
                                owner.tenantId(),
                                code
                        )
                ),
                () -> assertEquals(
                        1L,
                        countAgents(
                                peer.tenantId(),
                                code
                        )
                ),
                () -> assertEquals(
                        1L,
                        countAgentAudits(
                                owner.tenantId()
                        )
                ),
                () -> assertEquals(
                        1L,
                        countAgentAudits(
                                peer.tenantId()
                        )
                )
        );

        String peerModelConfig =
                jdbcTemplate.queryForObject(
                        """
                        SELECT CAST(model_config AS CHAR)
                        FROM agents
                        WHERE id = ?
                        """,
                        String.class,
                        Long.parseLong(
                                peerBody.agentId()
                        )
                );

        assertNull(
                peerModelConfig,
                "absent modelConfig must be SQL NULL"
        );
    }

    @Test
    void shouldRollbackAgentWhenAuditWriteFails() {
        TenantSession tenant = bootstrapAndLogin(
                "agent-rollback-acme"
        );

        AuditLogWriter target =
                AopTestUtils.getUltimateTargetObject(
                        auditLogWriter
                );

        doThrow(new IllegalStateException(
                "Simulated agent audit write failure"
        ))
                .when(target)
                .write(argThat(command ->
                        command != null
                                && command.tenantId()
                                == tenant.tenantId()
                                && "AGENT_CREATED".equals(
                                command.action()
                        )
                ));

        String code = "rollback-agent";

        ResponseEntity<String> response =
                createAgentText(
                        agentRequest(
                                code,
                                FULL_CONFIG
                        ),
                        tenant.accessToken()
                );

        assertTrue(
                response.getStatusCode()
                        .is5xxServerError()
        );

        assertAll(
                () -> assertEquals(
                        0L,
                        countAgents(
                                tenant.tenantId(),
                                code
                        ),
                        "agent insert must be rolled back"
                ),
                () -> assertEquals(
                        0L,
                        countAgentAudits(
                                tenant.tenantId()
                        ),
                        "failed audit must not persist"
                )
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

    private ResponseEntity<CreateAgentResponse> createAgent(
            CreateAgentRequest request,
            String accessToken
    ) {
        return restTemplate.exchange(
                "/api/v1/agents",
                HttpMethod.POST,
                new HttpEntity<>(
                        request,
                        bearerHeaders(accessToken)
                ),
                CreateAgentResponse.class
        );
    }

    private ResponseEntity<String> createAgentText(
            CreateAgentRequest request,
            String accessToken
    ) {
        return restTemplate.exchange(
                "/api/v1/agents",
                HttpMethod.POST,
                new HttpEntity<>(
                        request,
                        bearerHeaders(accessToken)
                ),
                String.class
        );
    }

    private long countAgents(
            long tenantId,
            String code
    ) {
        String sql;
        Object[] arguments;

        if (code == null) {
            sql = """
                    SELECT COUNT(*)
                    FROM agents
                    WHERE tenant_id = ?
                    """;
            arguments = new Object[]{tenantId};
        } else {
            sql = """
                    SELECT COUNT(*)
                    FROM agents
                    WHERE tenant_id = ?
                      AND code = ?
                    """;
            arguments = new Object[]{
                    tenantId,
                    code
            };
        }

        Long count = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                arguments
        );

        return count == null ? 0L : count;
    }

    private long countAgentAudits(
            long tenantId
    ) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_logs
                WHERE tenant_id = ?
                  AND action = 'AGENT_CREATED'
                """,
                Long.class,
                tenantId
        );

        return count == null ? 0L : count;
    }

    private static CreateAgentRequest agentRequest(
            String code,
            AgentModelConfig config
    ) {
        return new CreateAgentRequest(
                code,
                "Support Agent",
                "Handles enterprise support requests.",
                "You are an enterprise support agent.",
                AgentModelProvider.OPENAI,
                "gpt-5-mini",
                config
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

    private static long number(Object value) {
        return ((Number) value).longValue();
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
}