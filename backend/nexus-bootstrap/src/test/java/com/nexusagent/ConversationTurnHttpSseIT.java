package com.nexusagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.agent.api.ChangeAgentStatusRequest;
import com.nexusagent.agent.api.ChangeAgentStatusResponse;
import com.nexusagent.agent.api.CreateAgentRequest;
import com.nexusagent.agent.api.CreateAgentResponse;
import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.agent.domain.AgentStatus;
import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import com.nexusagent.conversation.api.CreateConversationRequest;
import com.nexusagent.conversation.api.CreateConversationResponse;
import com.nexusagent.conversation.api.StreamConversationTurnRequest;
import com.nexusagent.identity.api.BootstrapTenantRequest;
import com.nexusagent.identity.api.BootstrapTenantResponse;
import com.nexusagent.identity.api.LoginRequest;
import com.nexusagent.identity.api.LoginResponse;
import com.nexusagent.identity.spi.AccessTokenIssuer;
import com.nexusagent.identity.spi.IssuedAccessToken;
import com.nexusagent.identity.spi.TokenSubject;
import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.model.api.ChatModelException;
import com.nexusagent.model.api.ChatModelFinishReason;
import com.nexusagent.model.api.ChatModelGateway;
import com.nexusagent.model.api.ChatModelRequest;
import com.nexusagent.model.api.ChatModelStreamEvent;
import com.nexusagent.model.api.ChatModelStreamHandler;
import com.nexusagent.model.api.ChatTokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端验证会话轮次流式端点在真实 HTTP + SSE 传输上的完整闭环：
 * 结构化解析 SSE 帧，断言 started/delta/completed 事件与数据库提交的
 * 一致性、模型错误的安全脱敏与 FAILED 占位落库、以及跨所有者/跨租户
 * 会话对流的隐藏（单一 CONVERSATION_NOT_FOUND error 事件、无任何落库）。
 *
 * <p>与 {@link ConversationTurnStreamingSecurityIT} 互补：本类只关心
 * 认证后的行为，不重复匿名 401 / MEMBER / ADMIN 鉴权矩阵。
 *
 * <p>{@link ScriptedChatModelGateway} 通过真实
 * {@link ChatModelGatewayRegistry} 以 provider=OPENAI 解析，构造器注入
 * 真实的 {@link CurrentActorProvider}（{@code nexus.model.openai.enabled=false}
 * 下 OpenAI gateway 不注册），其 {@code stream()} 记录最近请求、最近
 * actor 与调用次数，供断言证明 SecurityContext 正确传播到 worker 线程。
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
@Import(ConversationTurnHttpSseIT.GatewayTestConfiguration.class)
@SpringBootTest(
        webEnvironment =
                SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "nexus.security.jwt.secret="
                        + "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0"
                        + "NTY3ODlhYmNkZWY=",
                "nexus.model.openai.enabled=false",
                "nexus.conversation.streaming.timeout=10s"
        }
)
class ConversationTurnHttpSseIT {

    private static final String PASSWORD =
            "StrongPassword123!";

    private static final String AGENT_MODEL_NAME =
            "gpt-5-mini";

    private static final String PROVIDER_SECRET =
            "provider-secret-must-not-leak";

    private static final String CAUSE_SECRET =
            "cause-secret-must-not-leak";

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

    @Autowired
    private ScriptedChatModelGateway gateway;

    @BeforeEach
    void setUp() {
        gateway.reset();

        restTemplate.getRestTemplate()
                .setRequestFactory(
                        new JdkClientHttpRequestFactory()
                );
    }

    @Test
    void shouldStreamSuccessfulTurnOverHttpAndPersistCommittedLifecycle()
            throws Exception {
        TenantSession tenant =
                bootstrapAndLogin(
                        "conversation-http-success-acme"
                );

        CreatedAgent agent =
                createActiveAgent(
                        tenant,
                        "conversation-http-success-agent"
                );

        CreatedConversation conversation =
                createConversation(
                        tenant,
                        agent,
                        "Success conversation",
                        "Initial message"
                );

        String memberToken = issueMemberToken(tenant);

        gateway.script((request, handler) -> {
            handler.onEvent(
                    new ChatModelStreamEvent.TextDelta("Hello")
            );
            handler.onEvent(
                    new ChatModelStreamEvent.TextDelta(" world")
            );
            handler.onEvent(
                    new ChatModelStreamEvent.Completed(
                            ChatModelFinishReason.STOP,
                            new ChatTokenUsage(12, 34)
                    )
            );
        });

        ResponseEntity<String> streamed =
                streamTurn(
                        memberToken,
                        conversation.id(),
                        "  Customer question  ",
                        String.class
                );

        assertEquals(
                HttpStatus.OK,
                streamed.getStatusCode()
        );
        assertTrue(
                streamed.getHeaders()
                        .getContentType()
                        .isCompatibleWith(
                                MediaType.TEXT_EVENT_STREAM
                        )
        );

        List<SseFrame> frames =
                parseSse(requireBody(streamed));

        assertEquals(4, frames.size());
        assertEquals("started", frames.get(0).event());
        assertEquals("delta", frames.get(1).event());
        assertEquals("delta", frames.get(2).event());
        assertEquals("completed", frames.get(3).event());

        JsonNode started = frames.get(0).data();
        assertEquals(
                Long.toString(conversation.id()),
                started.get("conversationId").asText()
        );
        assertEquals(2, started.get("userSequenceNo").asInt());
        assertEquals(
                3,
                started.get("assistantSequenceNo").asInt()
        );
        assertEquals(
                1,
                started.get("conversationVersion").asInt()
        );

        assertEquals(
                "Hello",
                frames.get(1).data().get("text").asText()
        );
        assertEquals(
                " world",
                frames.get(2).data().get("text").asText()
        );

        JsonNode completed = frames.get(3).data();
        assertEquals(
                started.get("assistantMessageId").asText(),
                completed.get("assistantMessageId").asText()
        );
        assertEquals(
                3,
                completed.get("assistantSequenceNo").asInt()
        );
        assertEquals(
                1,
                completed.get("conversationVersion").asInt()
        );
        assertEquals(
                "STOP",
                completed.get("finishReason").asText()
        );
        assertEquals(
                12,
                completed.get("promptTokens").asInt()
        );
        assertEquals(
                34,
                completed.get("completionTokens").asInt()
        );
        assertEquals(
                AGENT_MODEL_NAME,
                completed.get("modelName").asText()
        );

        List<MessageDatabaseRow> messages =
                readMessages(
                        tenant.tenantId(),
                        conversation.id()
                );

        assertEquals(3, messages.size());

        MessageDatabaseRow userRow = messages.get(1);
        assertEquals("USER", userRow.role());
        assertEquals("COMPLETED", userRow.status());
        assertEquals(
                "Customer question",
                userRow.content()
        );

        MessageDatabaseRow assistantRow = messages.get(2);
        assertEquals("ASSISTANT", assistantRow.role());
        assertEquals("COMPLETED", assistantRow.status());
        assertEquals("Hello world", assistantRow.content());
        assertEquals(
                AGENT_MODEL_NAME,
                assistantRow.modelName()
        );
        assertEquals(12, assistantRow.promptTokens());
        assertEquals(34, assistantRow.completionTokens());

        JsonNode metadata =
                parseJson(assistantRow.metadataJson());
        assertEquals(3, metadata.size());
        assertEquals(
                "OPENAI",
                metadata.get("provider").asText()
        );
        assertEquals(
                "STOP",
                metadata.get("finishReason").asText()
        );
        assertTrue(metadata.has("completedAt"));

        ConversationSnapshot snapshot =
                readConversationSnapshot(
                        tenant.tenantId(),
                        conversation.id()
                );
        assertEquals(4L, snapshot.nextMessageSequence());
        assertEquals(1, snapshot.version());

        assertTurnAuditCounts(
                tenant.tenantId(),
                1,
                1,
                0
        );

        CurrentActor actor = gateway.lastActor();
        assertAll(
                () -> assertNotNull(actor),
                () -> assertEquals(
                        tenant.adminUserId(),
                        actor.userId()
                ),
                () -> assertEquals(
                        tenant.tenantId(),
                        actor.tenantId()
                ),
                () -> assertEquals("admin", actor.username()),
                () -> assertEquals(
                        Set.of("MEMBER"),
                        actor.roles()
                )
        );

        assertEquals(1, gateway.calls());
        assertNotNull(gateway.lastRequest());
        assertEquals(
                AGENT_MODEL_NAME,
                gateway.lastRequest().modelName()
        );
    }

    @Test
    void shouldStreamSanitizedModelErrorAndPersistFailedPlaceholder()
            throws Exception {
        TenantSession tenant =
                bootstrapAndLogin(
                        "conversation-http-failure-acme"
                );

        CreatedAgent agent =
                createActiveAgent(
                        tenant,
                        "conversation-http-failure-agent"
                );

        CreatedConversation conversation =
                createConversation(
                        tenant,
                        agent,
                        "Failure conversation",
                        "Initial message"
                );

        gateway.script((request, handler) -> {
            handler.onEvent(
                    new ChatModelStreamEvent.TextDelta(
                            "partial"
                    )
            );

            throw new ChatModelException(
                    ChatModelErrorCategory.RATE_LIMIT,
                    PROVIDER_SECRET,
                    429,
                    new IllegalStateException(CAUSE_SECRET)
            );
        });

        ResponseEntity<String> streamed =
                streamTurn(
                        tenant.adminAccessToken(),
                        conversation.id(),
                        "  Customer question  ",
                        String.class
                );

        assertEquals(
                HttpStatus.OK,
                streamed.getStatusCode()
        );

        String body = requireBody(streamed);
        assertFalse(body.contains(PROVIDER_SECRET));
        assertFalse(body.contains(CAUSE_SECRET));

        List<SseFrame> frames = parseSse(body);
        assertEquals(3, frames.size());
        assertEquals("started", frames.get(0).event());
        assertEquals("delta", frames.get(1).event());
        assertEquals("error", frames.get(2).event());
        assertEquals(
                "partial",
                frames.get(1).data().get("text").asText()
        );

        JsonNode error = frames.get(2).data();
        assertEquals(
                objectMapper.readTree(
                        "{\"errorCode\":\"CHAT_MODEL_RATE_LIMIT\","
                                + "\"message\":\"Chat model turn "
                                + "failed\","
                                + "\"retryable\":true}"
                ),
                error
        );
        assertEquals(
                "CHAT_MODEL_RATE_LIMIT",
                error.get("errorCode").asText()
        );
        assertEquals(
                "Chat model turn failed",
                error.get("message").asText()
        );
        assertEquals(true, error.get("retryable").asBoolean());

        List<MessageDatabaseRow> messages =
                readMessages(
                        tenant.tenantId(),
                        conversation.id()
                );

        assertEquals(3, messages.size());

        MessageDatabaseRow userRow = messages.get(1);
        assertEquals("USER", userRow.role());
        assertEquals("COMPLETED", userRow.status());
        assertEquals(
                "Customer question",
                userRow.content()
        );

        MessageDatabaseRow assistantRow = messages.get(2);
        assertEquals("ASSISTANT", assistantRow.role());
        assertEquals("FAILED", assistantRow.status());
        assertEquals("", assistantRow.content());
        assertNull(assistantRow.promptTokens());
        assertNull(assistantRow.completionTokens());

        JsonNode metadata =
                parseJson(assistantRow.metadataJson());
        assertEquals(5, metadata.size());
        assertEquals(
                "OPENAI",
                metadata.get("provider").asText()
        );
        assertEquals(
                "CHAT_MODEL_RATE_LIMIT",
                metadata.get("errorCode").asText()
        );
        assertEquals(true, metadata.get("retryable").asBoolean());
        assertEquals(
                429,
                metadata.get("providerStatus").asInt()
        );
        assertTrue(metadata.has("failedAt"));
        assertFalse(metadata.toString().contains(PROVIDER_SECRET));
        assertFalse(metadata.toString().contains(CAUSE_SECRET));

        assertTurnAuditCounts(
                tenant.tenantId(),
                1,
                0,
                1
        );

        AuditDatabaseRow failedAudit =
                readAllAudits(tenant.tenantId())
                        .stream()
                        .filter(audit ->
                                "CONVERSATION_TURN_FAILED"
                                        .equals(audit.action()))
                        .findFirst()
                        .orElseThrow();

        assertEquals(
                "CHAT_MODEL_RATE_LIMIT",
                failedAudit.errorCode()
        );
        assertEquals(
                "Chat model turn failed",
                failedAudit.errorMessage()
        );

        String auditJson =
                readAllAudits(tenant.tenantId())
                        .stream()
                        .map(audit ->
                                orEmpty(audit.beforeJson())
                                        + orEmpty(audit.afterJson())
                                        + orEmpty(audit.errorCode())
                                        + orEmpty(audit.errorMessage())
                        )
                        .reduce("", String::concat);

        assertFalse(auditJson.contains(PROVIDER_SECRET));
        assertFalse(auditJson.contains(CAUSE_SECRET));
    }

    @Test
    void shouldHideConversationFromOtherOwnerAndTenantWithoutMutation()
            throws Exception {
        TenantSession owner =
                bootstrapAndLogin(
                        "conversation-http-owner-acme"
                );

        CreatedAgent agent =
                createActiveAgent(
                        owner,
                        "conversation-http-owner-agent"
                );

        CreatedConversation conversation =
                createConversation(
                        owner,
                        agent,
                        "Owner conversation",
                        "Initial message"
                );

        TenantSession outsider =
                bootstrapAndLogin(
                        "conversation-http-outsider-acme"
                );

        // 同租户不同所有者：不存在的 userId，仍是租户 A。
        String sameTenantOtherOwner =
                accessTokenIssuer.issue(
                        new TokenSubject(
                                owner.adminUserId() + 999_999L,
                                owner.tenantId(),
                                "other-owner",
                                List.of("MEMBER")
                        )
                ).value();

        // 跨租户：租户 B 的成员。
        String crossTenantMember =
                issueMemberToken(outsider);

        for (String token :
                List.of(
                        sameTenantOtherOwner,
                        crossTenantMember
                )) {
            ResponseEntity<String> streamed =
                    streamTurn(
                            token,
                            conversation.id(),
                            "Hello",
                            String.class
                    );

            assertEquals(
                    HttpStatus.OK,
                    streamed.getStatusCode()
            );
            assertTrue(
                    streamed.getHeaders()
                            .getContentType()
                            .isCompatibleWith(
                                    MediaType.TEXT_EVENT_STREAM
                            )
            );

            String body = requireBody(streamed);
            assertFalse(
                    body.contains(
                            Long.toString(conversation.id())
                    )
            );
            assertFalse(body.contains("event:started"));
            assertFalse(body.contains("event:delta"));
            assertFalse(body.contains("event:completed"));

            List<SseFrame> frames = parseSse(body);
            assertEquals(1, frames.size());
            assertEquals("error", frames.get(0).event());
            assertEquals(
                    "CONVERSATION_NOT_FOUND",
                    frames.get(0).data()
                            .get("errorCode").asText()
            );
            assertEquals(
                    "Conversation not found",
                    frames.get(0).data()
                            .get("message").asText()
            );
            assertEquals(
                    false,
                    frames.get(0).data()
                            .get("retryable").asBoolean()
            );
        }

        assertEquals(0, gateway.calls());

        ConversationSnapshot snapshot =
                readConversationSnapshot(
                        owner.tenantId(),
                        conversation.id()
                );

        assertEquals(2L, snapshot.nextMessageSequence());
        assertEquals(0, snapshot.version());
        assertEquals(
                1L,
                countConversationMessages(
                        owner.tenantId(),
                        conversation.id()
                )
        );
        assertEquals(0L, countTurnAudits(owner.tenantId()));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GatewayTestConfiguration {

        @Bean
        ScriptedChatModelGateway scriptedChatModelGateway(
                CurrentActorProvider currentActorProvider
        ) {
            return new ScriptedChatModelGateway(
                    currentActorProvider
            );
        }
    }

    @FunctionalInterface
    private interface GatewayScript {

        void run(
                ChatModelRequest request,
                ChatModelStreamHandler handler
        );
    }

    static final class ScriptedChatModelGateway
            implements ChatModelGateway {

        private final CurrentActorProvider currentActorProvider;
        private final AtomicReference<GatewayScript> script =
                new AtomicReference<>();
        private final AtomicReference<ChatModelRequest> lastRequest =
                new AtomicReference<>();
        private final AtomicReference<CurrentActor> lastActor =
                new AtomicReference<>();
        private final AtomicInteger calls = new AtomicInteger();

        ScriptedChatModelGateway(
                CurrentActorProvider currentActorProvider
        ) {
            this.currentActorProvider = Objects.requireNonNull(
                    currentActorProvider,
                    "currentActorProvider must not be null"
            );
        }

        @Override
        public AgentModelProvider provider() {
            return AgentModelProvider.OPENAI;
        }

        @Override
        public void stream(
                ChatModelRequest request,
                ChatModelStreamHandler handler
        ) {
            calls.incrementAndGet();
            lastRequest.set(request);
            lastActor.set(
                    currentActorProvider.requireCurrentActor()
            );

            GatewayScript current = script.get();

            if (current == null) {
                throw new IllegalStateException(
                        "Gateway script was not configured"
                );
            }

            current.run(request, handler);
        }

        void script(GatewayScript value) {
            script.set(value);
        }

        void reset() {
            script.set(null);
            lastRequest.set(null);
            lastActor.set(null);
            calls.set(0);
        }

        ChatModelRequest lastRequest() {
            return lastRequest.get();
        }

        CurrentActor lastActor() {
            return lastActor.get();
        }

        int calls() {
            return calls.get();
        }
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
                        AGENT_MODEL_NAME,
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

        assertEquals(AgentStatus.DRAFT, body.status());

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

    private CreatedConversation createConversation(
            TenantSession tenant,
            CreatedAgent agent,
            String title,
            String initialMessage
    ) {
        ResponseEntity<CreateConversationResponse>
                response =
                restTemplate.exchange(
                        "/api/v1/conversations",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                new CreateConversationRequest(
                                        agent.code(),
                                        title,
                                        initialMessage
                                ),
                                bearerHeaders(
                                        tenant.adminAccessToken()
                                )
                        ),
                        CreateConversationResponse.class
                );

        assertEquals(
                HttpStatus.CREATED,
                response.getStatusCode()
        );

        CreateConversationResponse body =
                requireBody(response);

        return new CreatedConversation(
                Long.parseLong(body.conversationId()),
                Long.parseLong(
                        body.initialMessage().messageId()
                ),
                body.lastMessageAt()
        );
    }

    private <T> ResponseEntity<T> streamTurn(
            String accessToken,
            long conversationId,
            String content,
            Class<T> responseType
    ) {
        HttpHeaders headers =
                accessToken == null
                        ? jsonHeaders()
                        : bearerHeaders(accessToken);

        return restTemplate.exchange(
                "/api/v1/conversations/"
                        + "{conversationId}/turns:stream",
                HttpMethod.POST,
                new HttpEntity<>(
                        new StreamConversationTurnRequest(content),
                        headers
                ),
                responseType,
                conversationId
        );
    }

    private List<SseFrame> parseSse(String body)
            throws Exception {
        String normalized = body.replace("\r\n", "\n");
        String[] blocks = normalized.split("\\n\\n+");

        List<SseFrame> frames = new ArrayList<>();

        for (String block : blocks) {
            String event = null;
            StringBuilder data = new StringBuilder();

            for (String line : block.split("\\n")) {
                if (line.startsWith("event:")) {
                    event = line.substring(
                            "event:".length()
                    ).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(
                            "data:".length()
                    ).trim());
                }
            }

            if (event != null) {
                frames.add(new SseFrame(
                        event,
                        objectMapper.readTree(data.toString())
                ));
            }
        }

        return frames;
    }

    private void assertTurnAuditCounts(
            long tenantId,
            int prepared,
            int completed,
            int failed
    ) {
        List<AuditDatabaseRow> allAudits =
                readAllAudits(tenantId);

        assertEquals(
                prepared,
                allAudits.stream()
                        .filter(audit ->
                                "CONVERSATION_TURN_PREPARED"
                                        .equals(audit.action()))
                        .count()
        );
        assertEquals(
                completed,
                allAudits.stream()
                        .filter(audit ->
                                "CONVERSATION_TURN_COMPLETED"
                                        .equals(audit.action()))
                        .count()
        );
        assertEquals(
                failed,
                allAudits.stream()
                        .filter(audit ->
                                "CONVERSATION_TURN_FAILED"
                                        .equals(audit.action()))
                        .count()
        );
    }

    private ConversationSnapshot readConversationSnapshot(
            long tenantId,
            long conversationId
    ) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    status,
                    last_message_at,
                    next_message_sequence,
                    version,
                    updated_at
                FROM conversations
                WHERE tenant_id = ?
                  AND id = ?
                """,
                (resultSet, rowNumber) ->
                        new ConversationSnapshot(
                                resultSet.getString("status"),
                                resultSet.getTimestamp(
                                        "last_message_at"
                                ).toInstant(),
                                resultSet.getLong(
                                        "next_message_sequence"
                                ),
                                resultSet.getInt("version"),
                                resultSet.getTimestamp(
                                        "updated_at"
                                ).toInstant()
                        ),
                tenantId,
                conversationId
        );
    }

    private List<MessageDatabaseRow> readMessages(
            long tenantId,
            long conversationId
    ) {
        return jdbcTemplate.query(
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
                        AS metadata_json
                FROM messages
                WHERE tenant_id = ?
                  AND conversation_id = ?
                ORDER BY sequence_no
                """,
                (resultSet, rowNumber) ->
                        new MessageDatabaseRow(
                                resultSet.getLong("id"),
                                resultSet.getLong("tenant_id"),
                                resultSet.getLong(
                                        "conversation_id"
                                ),
                                resultSet.getLong("sequence_no"),
                                resultSet.getString("role"),
                                resultSet.getString("content"),
                                resultSet.getString(
                                        "content_type"
                                ),
                                resultSet.getString("status"),
                                resultSet.getString("model_name"),
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
                                )
                        ),
                tenantId,
                conversationId
        );
    }

    private List<AuditDatabaseRow> readAllAudits(
            long tenantId
    ) {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    tenant_id,
                    actor_type,
                    actor_id,
                    action,
                    resource_type,
                    resource_id,
                    result,
                    CAST(before_json AS CHAR)
                        AS before_json,
                    CAST(after_json AS CHAR)
                        AS after_json,
                    error_code,
                    error_message
                FROM audit_logs
                WHERE tenant_id = ?
                ORDER BY id
                """,
                (resultSet, rowNumber) ->
                        new AuditDatabaseRow(
                                resultSet.getLong("id"),
                                resultSet.getLong("tenant_id"),
                                resultSet.getString("actor_type"),
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
                                resultSet.getString("result"),
                                resultSet.getString(
                                        "before_json"
                                ),
                                resultSet.getString(
                                        "after_json"
                                ),
                                resultSet.getString(
                                        "error_code"
                                ),
                                resultSet.getString(
                                        "error_message"
                                )
                        ),
                tenantId
        );
    }

    private long countConversationMessages(
            long tenantId,
            long conversationId
    ) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM messages
                WHERE tenant_id = ?
                  AND conversation_id = ?
                """,
                Long.class,
                tenantId,
                conversationId
        );

        return count == null ? 0L : count;
    }

    private long countTurnAudits(long tenantId) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_logs
                WHERE tenant_id = ?
                  AND action IN (
                      'CONVERSATION_TURN_PREPARED',
                      'CONVERSATION_TURN_COMPLETED',
                      'CONVERSATION_TURN_FAILED'
                  )
                """,
                Long.class,
                tenantId
        );

        return count == null ? 0L : count;
    }

    private JsonNode parseJson(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
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

    private record SseFrame(String event, JsonNode data) {
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

    private record CreatedConversation(
            long id,
            long initialMessageId,
            Instant initialLastMessageAt
    ) {
    }

    private record ConversationSnapshot(
            String status,
            Instant lastMessageAt,
            long nextMessageSequence,
            int version,
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
            String metadataJson
    ) {
    }

    private record AuditDatabaseRow(
            long id,
            long tenantId,
            String actorType,
            Long actorId,
            String action,
            String resourceType,
            Long resourceId,
            String result,
            String beforeJson,
            String afterJson,
            String errorCode,
            String errorMessage
    ) {
    }
}
