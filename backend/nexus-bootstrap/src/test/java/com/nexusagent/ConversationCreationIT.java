package com.nexusagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.agent.api.ChangeAgentStatusRequest;
import com.nexusagent.agent.api.ChangeAgentStatusResponse;
import com.nexusagent.agent.api.CreateAgentRequest;
import com.nexusagent.agent.api.CreateAgentResponse;
import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.agent.domain.AgentStatus;
import com.nexusagent.conversation.api.CreateConversationRequest;
import com.nexusagent.conversation.api.CreateConversationResponse;
import com.nexusagent.conversation.domain.ConversationStatus;
import com.nexusagent.conversation.domain.MessageContentType;
import com.nexusagent.conversation.domain.MessageRole;
import com.nexusagent.conversation.domain.MessageStatus;
import com.nexusagent.identity.api.BootstrapTenantRequest;
import com.nexusagent.identity.api.BootstrapTenantResponse;
import com.nexusagent.identity.api.LoginRequest;
import com.nexusagent.identity.api.LoginResponse;
import com.nexusagent.identity.spi.AccessTokenIssuer;
import com.nexusagent.identity.spi.IssuedAccessToken;
import com.nexusagent.identity.spi.TokenSubject;
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

import java.util.List;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
class ConversationCreationIT {

    private static final String PASSWORD =
            "StrongPassword123!";

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
    void shouldRequireAuthenticationBeforeValidationAndAllowMemberAndAdmin()
            throws Exception {
        TenantSession tenant =
                bootstrapAndLogin(
                        "conversation-auth-acme"
                );

        CreatedAgent agent =
                createActiveAgent(
                        tenant,
                        "conversation-auth-agent"
                );

        ResponseEntity<String> unauthorized =
                postConversation(
                        null,
                        "{}",
                        String.class
                );

        assertEquals(
                HttpStatus.UNAUTHORIZED,
                unauthorized.getStatusCode()
        );

        String memberToken =
                issueMemberToken(tenant);

        ResponseEntity<String> authenticatedInvalid =
                postConversation(
                        memberToken,
                        "{}",
                        String.class
                );

        assertProblem(
                authenticatedInvalid,
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED"
        );

        CreateConversationRequest memberRequest =
                new CreateConversationRequest(
                        agent.code(),
                        "Member conversation",
                        "Message sent with MEMBER role."
                );

        ResponseEntity<CreateConversationResponse>
                memberResponse =
                postConversation(
                        memberToken,
                        memberRequest,
                        CreateConversationResponse.class
                );

        assertEquals(
                HttpStatus.CREATED,
                memberResponse.getStatusCode()
        );

        assertCreatedConversation(
                requireBody(memberResponse),
                agent,
                memberRequest
        );

        CreateConversationRequest adminRequest =
                new CreateConversationRequest(
                        agent.code(),
                        "Admin conversation",
                        "Message sent with ADMIN role."
                );

        ResponseEntity<CreateConversationResponse>
                adminResponse =
                postConversation(
                        tenant.adminAccessToken(),
                        adminRequest,
                        CreateConversationResponse.class
                );

        assertEquals(
                HttpStatus.CREATED,
                adminResponse.getStatusCode()
        );

        assertCreatedConversation(
                requireBody(adminResponse),
                agent,
                adminRequest
        );

        assertAll(
                () -> assertEquals(
                        2L,
                        countConversations(
                                tenant.tenantId()
                        )
                ),
                () -> assertEquals(
                        2L,
                        countMessages(
                                tenant.tenantId()
                        )
                ),
                () -> assertEquals(
                        2L,
                        countConversationAudits(
                                tenant.tenantId()
                        )
                )
        );
    }

    @Test
    void shouldHideDraftDisabledAndCrossTenantAgents()
            throws Exception {
        TenantSession owner =
                bootstrapAndLogin(
                        "conversation-hidden-owner"
                );

        TenantSession outsider =
                bootstrapAndLogin(
                        "conversation-hidden-outsider"
                );

        CreatedAgent draftAgent =
                createAgent(
                        owner,
                        "draft-conversation-agent"
                );

        CreatedAgent disabledAgent =
                createAgent(
                        owner,
                        "disabled-conversation-agent"
                );

        changeAgentStatus(
                owner,
                disabledAgent.code(),
                AgentStatus.ACTIVE,
                0
        );

        changeAgentStatus(
                owner,
                disabledAgent.code(),
                AgentStatus.DISABLED,
                1
        );

        CreatedAgent activeAgent =
                createActiveAgent(
                        owner,
                        "private-conversation-agent"
                );

        ResponseEntity<String> draftResponse =
                postConversation(
                        owner.adminAccessToken(),
                        conversationRequest(
                                draftAgent.code()
                        ),
                        String.class
                );

        ResponseEntity<String> disabledResponse =
                postConversation(
                        owner.adminAccessToken(),
                        conversationRequest(
                                disabledAgent.code()
                        ),
                        String.class
                );

        ResponseEntity<String> crossTenantResponse =
                postConversation(
                        outsider.adminAccessToken(),
                        conversationRequest(
                                activeAgent.code()
                        ),
                        String.class
                );

        assertHiddenActiveAgent(
                draftResponse,
                draftAgent.code()
        );

        assertHiddenActiveAgent(
                disabledResponse,
                disabledAgent.code()
        );

        assertHiddenActiveAgent(
                crossTenantResponse,
                activeAgent.code()
        );

        assertAll(
                () -> assertEquals(
                        0L,
                        countConversations(
                                owner.tenantId()
                        )
                ),
                () -> assertEquals(
                        0L,
                        countMessages(
                                owner.tenantId()
                        )
                ),
                () -> assertEquals(
                        0L,
                        countConversationAudits(
                                owner.tenantId()
                        )
                ),
                () -> assertEquals(
                        0L,
                        countConversations(
                                outsider.tenantId()
                        )
                ),
                () -> assertEquals(
                        0L,
                        countMessages(
                                outsider.tenantId()
                        )
                ),
                () -> assertEquals(
                        0L,
                        countConversationAudits(
                                outsider.tenantId()
                        )
                )
        );
    }

    @Test
    void shouldPersistExactConversationMessageAndSafeNineKeyAudit()
            throws Exception {
        TenantSession tenant = bootstrapAndLogin(
                "conversation-persistence-acme"
        );

        CreatedAgent agent = createActiveAgent(
                tenant,
                "conversation-persistence-agent"
        );

        CreateConversationRequest request =
                new CreateConversationRequest(
                        agent.code(),
                        "  Payment incident  ",
                        "  customer-secret-must-not-enter-audit  "
                );

        ResponseEntity<CreateConversationResponse> entity =
                postConversation(
                        tenant.adminAccessToken(),
                        request,
                        CreateConversationResponse.class
                );

        assertEquals(
                HttpStatus.CREATED,
                entity.getStatusCode()
        );

        CreateConversationResponse response =
                requireBody(entity);

        CreateConversationRequest normalizedRequest =
                new CreateConversationRequest(
                        agent.code(),
                        "Payment incident",
                        "customer-secret-must-not-enter-audit"
                );

        assertCreatedConversation(
                response,
                agent,
                normalizedRequest
        );

        long conversationId = Long.parseLong(
                response.conversationId()
        );

        long messageId = Long.parseLong(
                response.initialMessage().messageId()
        );

        assertEquals(
                new ConversationDatabaseRow(
                        conversationId,
                        tenant.tenantId(),
                        tenant.adminUserId(),
                        agent.id(),
                        normalizedRequest.title(),
                        "ACTIVE",
                        response.lastMessageAt(),
                        2L,
                        0,
                        response.createdAt(),
                        response.updatedAt()
                ),
                readConversationRow(
                        tenant.tenantId(),
                        conversationId
                )
        );

        assertEquals(
                new MessageDatabaseRow(
                        messageId,
                        tenant.tenantId(),
                        conversationId,
                        1L,
                        "USER",
                        normalizedRequest.initialMessage(),
                        "TEXT",
                        "COMPLETED",
                        null,
                        null,
                        null,
                        null,
                        response.initialMessage()
                                .createdAt()
                ),
                readMessageRow(
                        tenant.tenantId(),
                        messageId
                )
        );

        ConversationAuditDatabaseRow audit =
                readConversationAuditRow(
                        tenant.tenantId(),
                        conversationId
                );

        JsonNode expectedAfter =
                objectMapper.readTree(
                        """
                        {
                          "agentId": "%d",
                          "agentCode": "%s",
                          "status": "ACTIVE",
                          "version": 0,
                          "initialMessageId": "%d",
                          "initialMessageSequenceNo": 1,
                          "initialMessageRole": "USER",
                          "initialMessageContentType": "TEXT",
                          "initialMessageStatus": "COMPLETED"
                        }
                        """.formatted(
                                agent.id(),
                                agent.code(),
                                messageId
                        )
                );

        assertAll(
                () -> assertTrue(audit.id() > 0),
                () -> assertEquals(
                        tenant.tenantId(),
                        audit.tenantId()
                ),
                () -> assertEquals(
                        "USER",
                        audit.actorType()
                ),
                () -> assertEquals(
                        Long.valueOf(
                                tenant.adminUserId()
                        ),
                        audit.actorId()
                ),
                () -> assertEquals(
                        "CONVERSATION_CREATED",
                        audit.action()
                ),
                () -> assertEquals(
                        "CONVERSATION",
                        audit.resourceType()
                ),
                () -> assertEquals(
                        Long.valueOf(conversationId),
                        audit.resourceId()
                ),
                () -> assertNull(
                        audit.toolExecutionId()
                ),
                () -> assertEquals(
                        "SUCCESS",
                        audit.result()
                ),
                // 关联信息由 RequestCorrelationFilter 自动生成
                // 并由 AuditLogWriter 自动补充。
                () -> assertNotNull(audit.requestId()),
                () -> assertNotNull(audit.traceId()),
                () -> assertEquals(
                        audit.requestId(),
                        audit.traceId()
                ),
                () -> assertEquals(
                        "127.0.0.1",
                        audit.ipAddress()
                ),
                () -> assertNull(audit.beforeJson()),
                () -> assertEquals(
                        9,
                        audit.afterKeyCount()
                ),
                () -> assertEquals(
                        expectedAfter,
                        objectMapper.readTree(
                                audit.afterJson()
                        )
                ),
                () -> assertFalse(
                        audit.afterJson().contains(
                                normalizedRequest.title()
                        )
                ),
                () -> assertFalse(
                        audit.afterJson().contains(
                                normalizedRequest
                                        .initialMessage()
                        )
                ),
                () -> assertNull(audit.errorCode()),
                () -> assertNull(audit.errorMessage()),
                () -> assertNotNull(
                        audit.createdAt()
                )
        );
    }

    @Test
    void shouldRollbackConversationMessageAndAuditWhenAuditWriteFails() {
        TenantSession tenant = bootstrapAndLogin(
                "conversation-rollback-acme"
        );

        CreatedAgent agent = createActiveAgent(
                tenant,
                "conversation-rollback-agent"
        );

        /*
         * 记录 Agent 创建和激活产生的审计数量。
         * 回滚后总审计数必须恢复到这个值。
         */
        long auditsBefore = countAuditLogs(
                tenant.tenantId()
        );

        /*
         * 必须在 Agent 创建并激活后安装异常桩，
         * 否则会拦截 Agent 初始化审计。
         */
        AuditLogWriter target =
                AopTestUtils.getUltimateTargetObject(
                        auditLogWriter
                );

        doThrow(new IllegalStateException(
                "Simulated conversation audit failure"
        ))
                .when(target)
                .write(argThat(command ->
                        command != null
                                && command.tenantId()
                                == tenant.tenantId()
                                && "CONVERSATION_CREATED"
                                .equals(command.action())
                                && "CONVERSATION"
                                .equals(
                                        command.resourceType()
                                )
                ));

        ResponseEntity<String> response =
                postConversation(
                        tenant.adminAccessToken(),
                        new CreateConversationRequest(
                                agent.code(),
                                "Rollback conversation",
                                "This message must be rolled back."
                        ),
                        String.class
                );

        assertTrue(
                response.getStatusCode()
                        .is5xxServerError()
        );

        verify(target).write(argThat(command ->
                command != null
                        && command.tenantId()
                        == tenant.tenantId()
                        && "CONVERSATION_CREATED"
                        .equals(command.action())
                        && "CONVERSATION"
                        .equals(command.resourceType())
        ));

        assertAll(
                () -> assertEquals(
                        0L,
                        countConversations(
                                tenant.tenantId()
                        )
                ),
                () -> assertEquals(
                        0L,
                        countMessages(
                                tenant.tenantId()
                        )
                ),
                () -> assertEquals(
                        0L,
                        countConversationAudits(
                                tenant.tenantId()
                        )
                ),
                () -> assertEquals(
                        auditsBefore,
                        countAuditLogs(
                                tenant.tenantId()
                        )
                )
        );
    }

    private ConversationDatabaseRow readConversationRow(
            long tenantId,
            long conversationId
    ) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    id,
                    tenant_id,
                    user_id,
                    agent_id,
                    title,
                    status,
                    last_message_at,
                    next_message_sequence,
                    version,
                    created_at,
                    updated_at
                FROM conversations
                WHERE tenant_id = ?
                  AND id = ?
                """,
                (resultSet, rowNumber) ->
                        new ConversationDatabaseRow(
                                resultSet.getLong("id"),
                                resultSet.getLong(
                                        "tenant_id"
                                ),
                                resultSet.getLong("user_id"),
                                resultSet.getLong("agent_id"),
                                resultSet.getString("title"),
                                resultSet.getString("status"),
                                resultSet.getTimestamp(
                                        "last_message_at"
                                ).toInstant(),
                                resultSet.getLong(
                                        "next_message_sequence"
                                ),
                                resultSet.getInt("version"),
                                resultSet.getTimestamp(
                                        "created_at"
                                ).toInstant(),
                                resultSet.getTimestamp(
                                        "updated_at"
                                ).toInstant()
                        ),
                tenantId,
                conversationId
        );
    }

    private MessageDatabaseRow readMessageRow(
            long tenantId,
            long messageId
    ) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    id,
                    tenant_id,
                    conversation_id,
                    sequence_no,
                    `role`,
                    content,
                    content_type,
                    status,
                    model_name,
                    prompt_tokens,
                    completion_tokens,
                    CAST(metadata_json AS CHAR)
                        AS metadata_json,
                    created_at
                FROM messages
                WHERE tenant_id = ?
                  AND id = ?
                """,
                (resultSet, rowNumber) ->
                        new MessageDatabaseRow(
                                resultSet.getLong("id"),
                                resultSet.getLong(
                                        "tenant_id"
                                ),
                                resultSet.getLong(
                                        "conversation_id"
                                ),
                                resultSet.getLong(
                                        "sequence_no"
                                ),
                                resultSet.getString("role"),
                                resultSet.getString("content"),
                                resultSet.getString(
                                        "content_type"
                                ),
                                resultSet.getString("status"),
                                resultSet.getString(
                                        "model_name"
                                ),
                                resultSet.getObject(
                                        "prompt_tokens",
                                        Integer.class
                                ),
                                resultSet.getObject(
                                        "completion_tokens",
                                        Integer.class
                                ),
                                resultSet.getString(
                                        "metadata_json"
                                ),
                                resultSet.getTimestamp(
                                        "created_at"
                                ).toInstant()
                        ),
                tenantId,
                messageId
        );
    }

    private ConversationAuditDatabaseRow
    readConversationAuditRow(
            long tenantId,
            long conversationId
    ) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    id,
                    tenant_id,
                    actor_type,
                    actor_id,
                    action,
                    resource_type,
                    resource_id,
                    tool_execution_id,
                    result,
                    request_id,
                    trace_id,
                    ip_address,
                    CAST(before_json AS CHAR)
                        AS before_json,
                    CAST(after_json AS CHAR)
                        AS after_json,
                    JSON_LENGTH(after_json)
                        AS after_key_count,
                    error_code,
                    error_message,
                    created_at
                FROM audit_logs
                WHERE tenant_id = ?
                  AND action = 'CONVERSATION_CREATED'
                  AND resource_type = 'CONVERSATION'
                  AND resource_id = ?
                """,
                (resultSet, rowNumber) ->
                        new ConversationAuditDatabaseRow(
                                resultSet.getLong("id"),
                                resultSet.getLong(
                                        "tenant_id"
                                ),
                                resultSet.getString(
                                        "actor_type"
                                ),
                                resultSet.getObject(
                                        "actor_id",
                                        Long.class
                                ),
                                resultSet.getString("action"),
                                resultSet.getString(
                                        "resource_type"
                                ),
                                resultSet.getObject(
                                        "resource_id",
                                        Long.class
                                ),
                                resultSet.getObject(
                                        "tool_execution_id",
                                        Long.class
                                ),
                                resultSet.getString("result"),
                                resultSet.getString(
                                        "request_id"
                                ),
                                resultSet.getString(
                                        "trace_id"
                                ),
                                resultSet.getString(
                                        "ip_address"
                                ),
                                resultSet.getString(
                                        "before_json"
                                ),
                                resultSet.getString(
                                        "after_json"
                                ),
                                resultSet.getInt(
                                        "after_key_count"
                                ),
                                resultSet.getString(
                                        "error_code"
                                ),
                                resultSet.getString(
                                        "error_message"
                                ),
                                resultSet.getTimestamp(
                                        "created_at"
                                ).toInstant()
                        ),
                tenantId,
                conversationId
        );
    }

    private long countAuditLogs(long tenantId) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_logs
                WHERE tenant_id = ?
                """,
                Long.class,
                tenantId
        );

        return count == null ? 0L : count;
    }

    private TenantSession bootstrapAndLogin(
            String tenantCode
    ) {
        ResponseEntity<BootstrapTenantResponse>
                bootstrap =
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

    private String issueMemberToken(
            TenantSession tenant
    ) {
        IssuedAccessToken token =
                accessTokenIssuer.issue(
                        new TokenSubject(
                                tenant.adminUserId(),
                                tenant.tenantId(),
                                "admin",
                                List.of("MEMBER")
                        )
                );

        return token.value();
    }

    private CreatedAgent createAgent(
            TenantSession tenant,
            String code
    ) {
        CreateAgentRequest request =
                new CreateAgentRequest(
                        code,
                        code + " Agent",
                        "Conversation integration Agent.",
                        "You are a conversation Agent.",
                        AgentModelProvider.OPENAI,
                        "gpt-5-mini",
                        null
                );

        ResponseEntity<CreateAgentResponse> response =
                restTemplate.exchange(
                        "/api/v1/agents",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                request,
                                bearerHeaders(
                                        tenant.adminAccessToken()
                                )
                        ),
                        CreateAgentResponse.class
                );

        assertEquals(
                HttpStatus.CREATED,
                response.getStatusCode()
        );

        CreateAgentResponse body =
                requireBody(response);

        assertAll(
                () -> assertEquals(
                        code,
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

        return new CreatedAgent(
                Long.parseLong(body.agentId()),
                body.code()
        );
    }

    private CreatedAgent createActiveAgent(
            TenantSession tenant,
            String code
    ) {
        CreatedAgent agent =
                createAgent(tenant, code);

        ChangeAgentStatusResponse activated =
                changeAgentStatus(
                        tenant,
                        code,
                        AgentStatus.ACTIVE,
                        0
                );

        assertAll(
                () -> assertEquals(
                        AgentStatus.DRAFT,
                        activated.previousStatus()
                ),
                () -> assertEquals(
                        AgentStatus.ACTIVE,
                        activated.currentStatus()
                ),
                () -> assertEquals(
                        1,
                        activated.version()
                )
        );

        return agent;
    }

    private ChangeAgentStatusResponse changeAgentStatus(
            TenantSession tenant,
            String code,
            AgentStatus targetStatus,
            int expectedVersion
    ) {
        ResponseEntity<ChangeAgentStatusResponse>
                response =
                restTemplate.exchange(
                        "/api/v1/agents/{agentCode}/status",
                        HttpMethod.PATCH,
                        new HttpEntity<>(
                                new ChangeAgentStatusRequest(
                                        targetStatus,
                                        expectedVersion
                                ),
                                bearerHeaders(
                                        tenant.adminAccessToken()
                                )
                        ),
                        ChangeAgentStatusResponse.class,
                        code
                );

        assertEquals(
                HttpStatus.OK,
                response.getStatusCode()
        );

        ChangeAgentStatusResponse body =
                requireBody(response);

        assertAll(
                () -> assertEquals(
                        code,
                        body.code()
                ),
                () -> assertEquals(
                        targetStatus,
                        body.currentStatus()
                ),
                () -> assertEquals(
                        expectedVersion + 1,
                        body.version()
                )
        );

        return body;
    }

    private <T> ResponseEntity<T> postConversation(
            String accessToken,
            Object request,
            Class<T> responseType
    ) {
        HttpHeaders headers =
                accessToken == null
                        ? jsonHeaders()
                        : bearerHeaders(accessToken);

        return restTemplate.exchange(
                "/api/v1/conversations",
                HttpMethod.POST,
                new HttpEntity<>(
                        request,
                        headers
                ),
                responseType
        );
    }

    private void assertCreatedConversation(
            CreateConversationResponse response,
            CreatedAgent agent,
            CreateConversationRequest request
    ) {
        assertAll(
                () -> assertNotNull(
                        response.conversationId()
                ),
                () -> assertEquals(
                        Long.toString(agent.id()),
                        response.agentId()
                ),
                () -> assertEquals(
                        agent.code(),
                        response.agentCode()
                ),
                () -> assertEquals(
                        request.title(),
                        response.title()
                ),
                () -> assertEquals(
                        ConversationStatus.ACTIVE,
                        response.status()
                ),
                () -> assertEquals(
                        0,
                        response.version()
                ),
                () -> assertNotNull(
                        response.lastMessageAt()
                ),
                () -> assertNotNull(
                        response.createdAt()
                ),
                () -> assertNotNull(
                        response.updatedAt()
                ),
                () -> assertNotNull(
                        response.initialMessage()
                ),
                () -> assertNotNull(
                        response.initialMessage()
                                .messageId()
                ),
                () -> assertEquals(
                        1L,
                        response.initialMessage()
                                .sequenceNo()
                ),
                () -> assertEquals(
                        MessageRole.USER,
                        response.initialMessage().role()
                ),
                () -> assertEquals(
                        request.initialMessage(),
                        response.initialMessage()
                                .content()
                ),
                () -> assertEquals(
                        MessageContentType.TEXT,
                        response.initialMessage()
                                .contentType()
                ),
                () -> assertEquals(
                        MessageStatus.COMPLETED,
                        response.initialMessage()
                                .status()
                ),
                () -> assertEquals(
                        response.lastMessageAt(),
                        response.initialMessage()
                                .createdAt()
                ),
                () -> assertEquals(
                        response.createdAt(),
                        response.updatedAt()
                )
        );
    }

    private void assertHiddenActiveAgent(
            ResponseEntity<String> response,
            String hiddenCode
    ) throws Exception {
        JsonNode problem = assertProblem(
                response,
                HttpStatus.NOT_FOUND,
                "ACTIVE_AGENT_NOT_FOUND"
        );

        String body = requireBody(response);

        assertAll(
                () -> assertFalse(
                        problem.has("agentCode")
                ),
                () -> assertEquals(
                        "Active Agent not found",
                        problem.path("detail").asText()
                ),
                () -> assertFalse(
                        body.contains(hiddenCode)
                )
        );
    }

    private JsonNode assertProblem(
            ResponseEntity<String> response,
            HttpStatus expectedStatus,
            String expectedErrorCode
    ) throws Exception {
        assertEquals(
                expectedStatus,
                response.getStatusCode()
        );

        JsonNode problem =
                objectMapper.readTree(
                        requireBody(response)
                );

        assertEquals(
                expectedErrorCode,
                problem.path("errorCode").asText()
        );

        return problem;
    }

    private long countConversations(
            long tenantId
    ) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM conversations
                WHERE tenant_id = ?
                """,
                Long.class,
                tenantId
        );

        return count == null ? 0L : count;
    }

    private long countMessages(
            long tenantId
    ) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM messages
                WHERE tenant_id = ?
                """,
                Long.class,
                tenantId
        );

        return count == null ? 0L : count;
    }

    private long countConversationAudits(
            long tenantId
    ) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_logs
                WHERE tenant_id = ?
                  AND action = 'CONVERSATION_CREATED'
                  AND resource_type = 'CONVERSATION'
                """,
                Long.class,
                tenantId
        );

        return count == null ? 0L : count;
    }

    private static CreateConversationRequest
    conversationRequest(
            String agentCode
    ) {
        return new CreateConversationRequest(
                agentCode,
                null,
                "Conversation visibility test."
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
            String adminAccessToken
    ) {
    }

    private record CreatedAgent(
            long id,
            String code
    ) {
    }

    private record ConversationDatabaseRow(
            long id,
            long tenantId,
            long userId,
            long agentId,
            String title,
            String status,
            Instant lastMessageAt,
            long nextMessageSequence,
            int version,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    private record MessageDatabaseRow(
            long id,
            long tenantId,
            long conversationId,
            long sequenceNo,
            String role,
            String content,
            String contentType,
            String status,
            String modelName,
            Integer promptTokens,
            Integer completionTokens,
            String metadataJson,
            Instant createdAt
    ) {
    }

    private record ConversationAuditDatabaseRow(
            long id,
            long tenantId,
            String actorType,
            Long actorId,
            String action,
            String resourceType,
            Long resourceId,
            Long toolExecutionId,
            String result,
            String requestId,
            String traceId,
            String ipAddress,
            String beforeJson,
            String afterJson,
            int afterKeyCount,
            String errorCode,
            String errorMessage,
            Instant createdAt
    ) {
    }
}