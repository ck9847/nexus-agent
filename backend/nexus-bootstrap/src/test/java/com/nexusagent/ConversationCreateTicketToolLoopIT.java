package com.nexusagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import com.nexusagent.conversation.api.StreamConversationTurnRequest;
import com.nexusagent.identity.spi.AccessTokenIssuer;
import com.nexusagent.identity.spi.IssuedAccessToken;
import com.nexusagent.identity.spi.TokenSubject;
import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.model.api.ChatModelException;
import com.nexusagent.model.api.ChatModelFinishReason;
import com.nexusagent.model.api.ChatModelGateway;
import com.nexusagent.model.api.ChatModelMessage;
import com.nexusagent.model.api.ChatModelRequest;
import com.nexusagent.model.api.ChatModelRole;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端验证 create_ticket 工具两轮闭环在真实 HTTP + SSE + MySQL 上的
 * 完整行为：首轮工具调用、建单、续写轮文本回答、失败边界与安全脱敏。
 *
 * <p>{@link QueuedChatModelGateway} 通过真实
 * {@link com.nexusagent.model.internal.ChatModelGatewayRegistry} 以
 * provider=OPENAI 解析，脚本按队列消费，可精确编排两轮模型响应。
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
@Import(ConversationCreateTicketToolLoopIT.GatewayTestConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "nexus.security.jwt.secret="
                        + "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0"
                        + "NTY3ODlhYmNkZWY=",
                "nexus.model.openai.enabled=false",
                "nexus.conversation.streaming.timeout=10s"
        }
)
class ConversationCreateTicketToolLoopIT {

    private static final AtomicLong FIXTURE_IDS =
            new AtomicLong(500_000L);

    private static final String SYSTEM_PROMPT =
            "system-prompt-sensitive-value";

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
    private QueuedChatModelGateway gateway;

    @BeforeEach
    void setUp() {
        gateway.reset();

        restTemplate.getRestTemplate()
                .setRequestFactory(
                        new JdkClientHttpRequestFactory()
                );
    }

    @Test
    void shouldCreateTicketThroughTwoModelRoundsAndPersistExactLifecycle()
            throws Exception {
        Fixture fixture = insertFixture();
        String memberToken = issueMemberToken(fixture);

        gateway.enqueue((request, handler) -> {
            assertEquals(1, request.tools().size());
            assertEquals(
                    "create_ticket",
                    request.tools().get(0).name()
            );

            handler.onEvent(new ChatModelStreamEvent.ToolCallDelta(
                    0, "call-", null, null
            ));
            handler.onEvent(new ChatModelStreamEvent.ToolCallDelta(
                    0, "1", "create_",
                    "{\"title\":\"Server down\","
            ));
            handler.onEvent(new ChatModelStreamEvent.ToolCallDelta(
                    0, null, "ticket",
                    "\"description\":\"Cannot connect\","
                            + "\"priority\":\"HIGH\"}"
            ));
            handler.onEvent(new ChatModelStreamEvent.Completed(
                    ChatModelFinishReason.TOOL_CALLS,
                    new ChatTokenUsage(11, 7)
            ));
        });

        gateway.enqueue((request, handler) -> {
            assertTrue(request.tools().isEmpty());

            List<ChatModelMessage> messages = request.messages();

            ChatModelMessage assistant =
                    messages.get(messages.size() - 2);
            ChatModelMessage tool =
                    messages.get(messages.size() - 1);

            assertEquals(ChatModelRole.ASSISTANT, assistant.role());
            assertEquals(1, assistant.toolCalls().size());
            assertEquals(
                    "call-1",
                    assistant.toolCalls().get(0).id()
            );
            assertEquals(
                    "create_ticket",
                    assistant.toolCalls().get(0).name()
            );

            assertEquals(ChatModelRole.TOOL, tool.role());
            assertEquals("call-1", tool.toolCallId());

            String ticketNo = parseToolOutput(tool.content())
                    .get("ticketNo").asText();

            handler.onEvent(new ChatModelStreamEvent.TextDelta(
                    "Created "
            ));
            handler.onEvent(new ChatModelStreamEvent.TextDelta(
                    ticketNo
            ));
            handler.onEvent(new ChatModelStreamEvent.Completed(
                    ChatModelFinishReason.STOP,
                    new ChatTokenUsage(21, 9)
            ));
        });

        ResponseEntity<String> streamed =
                streamTurn(
                        memberToken,
                        fixture.conversationId(),
                        "Please create a ticket"
                );

        assertEquals(HttpStatus.OK, streamed.getStatusCode());
        assertTrue(streamed.getHeaders().getContentType()
                .isCompatibleWith(MediaType.TEXT_EVENT_STREAM));

        String body = requireBody(streamed);
        assertFalse(body.contains(SYSTEM_PROMPT));

        List<SseFrame> frames = parseSse(body);

        assertEquals(4, frames.size());
        assertEquals("started", frames.get(0).event());
        assertEquals("delta", frames.get(1).event());
        assertEquals("delta", frames.get(2).event());
        assertEquals("completed", frames.get(3).event());

        assertFalse(body.contains("ToolCall"));
        assertFalse(body.contains("tool_calls"));

        JsonNode started = frames.get(0).data();
        assertEquals(
                Long.toString(fixture.conversationId()),
                started.get("conversationId").asText()
        );
        assertEquals(2, started.get("userSequenceNo").asInt());
        assertEquals(3, started.get("assistantSequenceNo").asInt());

        assertEquals(
                "Created ",
                frames.get(1).data().get("text").asText()
        );

        JsonNode completed = frames.get(3).data();
        assertEquals(5, completed.get("assistantSequenceNo").asInt());
        assertEquals(2, completed.get("conversationVersion").asInt());
        assertEquals(
                AGENT_MODEL_NAME,
                completed.get("modelName").asText()
        );
        assertEquals(
                "STOP",
                completed.get("finishReason").asText()
        );
        assertEquals(21, completed.get("promptTokens").asInt());
        assertEquals(9, completed.get("completionTokens").asInt());

        List<MessageDatabaseRow> messages =
                readMessages(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        assertEquals(5, messages.size());

        assertEquals(1L, messages.get(0).sequenceNo());
        assertEquals("USER", messages.get(0).role());
        assertEquals("TEXT", messages.get(0).contentType());
        assertEquals("COMPLETED", messages.get(0).status());

        assertEquals(2L, messages.get(1).sequenceNo());
        assertEquals("USER", messages.get(1).role());
        assertEquals("TEXT", messages.get(1).contentType());
        assertEquals("COMPLETED", messages.get(1).status());

        assertEquals(3L, messages.get(2).sequenceNo());
        assertEquals("ASSISTANT", messages.get(2).role());
        assertEquals("JSON", messages.get(2).contentType());
        assertEquals("COMPLETED", messages.get(2).status());

        assertEquals(4L, messages.get(3).sequenceNo());
        assertEquals("TOOL", messages.get(3).role());
        assertEquals("JSON", messages.get(3).contentType());
        assertEquals("COMPLETED", messages.get(3).status());

        assertEquals(5L, messages.get(4).sequenceNo());
        assertEquals("ASSISTANT", messages.get(4).role());
        assertEquals("TEXT", messages.get(4).contentType());
        assertEquals("COMPLETED", messages.get(4).status());

        long firstAssistantId = messages.get(2).id();
        long toolMessageId = messages.get(3).id();
        long finalAssistantId = messages.get(4).id();

        assertTrue(firstAssistantId != finalAssistantId);

        assertEquals(
                Long.toString(firstAssistantId),
                started.get("assistantMessageId").asText()
        );
        assertEquals(
                Long.toString(finalAssistantId),
                completed.get("assistantMessageId").asText()
        );

        ConversationSnapshot conversation =
                readConversationSnapshot(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        assertEquals(6L, conversation.nextMessageSequence());
        assertEquals(2, conversation.version());

        List<TicketDatabaseRow> tickets =
                readTickets(fixture.tenantId());

        assertEquals(1, tickets.size());

        TicketDatabaseRow ticket = tickets.get(0);

        assertEquals("AGENT", ticket.source());
        assertEquals(
                fixture.userId(),
                ticket.requesterUserId()
        );
        assertEquals(
                fixture.agentId(),
                ticket.createdByAgentId()
        );
        assertEquals("Server down", ticket.title());
        assertEquals("Cannot connect", ticket.description());
        assertEquals("HIGH", ticket.priority());
        assertEquals("OPEN", ticket.status());

        List<ToolExecutionDatabaseRow> executions =
                readToolExecutions(fixture.tenantId());

        assertEquals(1, executions.size());

        ToolExecutionDatabaseRow execution = executions.get(0);

        assertEquals("SUCCEEDED", execution.status());
        assertEquals(
                firstAssistantId,
                execution.requestMessageId()
        );
        assertEquals(
                toolMessageId,
                execution.resultMessageId()
        );
        assertEquals("TICKET", execution.resultEntityType());
        assertEquals(ticket.id(), execution.resultEntityId());

        assertEquals(
                objectMapper.readTree(
                        "{\"title\":\"Server down\","
                                + "\"description\":\"Cannot connect\","
                                + "\"priority\":\"HIGH\"}"
                ),
                parseJson(execution.inputJson())
        );

        JsonNode output = parseJson(execution.outputJson());

        assertEquals(
                Long.toString(ticket.id()),
                output.get("ticketId").asText()
        );
        assertEquals(ticket.ticketNo(), output.get("ticketNo").asText());
        assertEquals("OPEN", output.get("status").asText());

        assertEquals(2, gateway.requests().size());
        assertEquals(2, gateway.actors().size());

        for (CurrentActor actor : gateway.actors()) {
            assertEquals(fixture.tenantId(), actor.tenantId());
            assertEquals(fixture.userId(), actor.userId());
            assertEquals("user" + fixture.userId(), actor.username());
            assertEquals(Set.of("MEMBER"), actor.roles());
        }

        assertAuditActionExists(
                fixture.tenantId(),
                "CONVERSATION_TURN_PREPARED"
        );
        assertAuditActionExists(
                fixture.tenantId(),
                "TOOL_EXECUTION_REGISTERED"
        );
        assertAuditActionExists(
                fixture.tenantId(),
                "CONVERSATION_TOOL_CALL_COMPLETED"
        );
        assertAuditActionExists(
                fixture.tenantId(),
                "TICKET_CREATED"
        );
        assertAuditActionExists(
                fixture.tenantId(),
                "TOOL_MESSAGE_WRITTEN"
        );
        assertAuditActionExists(
                fixture.tenantId(),
                "CONVERSATION_TOOL_CONTINUATION_PREPARED"
        );
        assertAuditActionExists(
                fixture.tenantId(),
                "TOOL_EXECUTION_SUCCEEDED"
        );
        assertAuditActionExists(
                fixture.tenantId(),
                "CONVERSATION_TURN_COMPLETED"
        );

        long executionId = execution.id();

        AuditDatabaseRow registered = findAudit(
                fixture.tenantId(),
                "TOOL_EXECUTION_REGISTERED"
        );
        assertEquals("AGENT", registered.actorType());
        assertEquals(fixture.agentId(), registered.actorId());
        assertEquals(
                "TOOL_EXECUTION",
                registered.resourceType()
        );
        assertEquals(executionId, registered.resourceId());
        assertEquals(executionId, registered.toolExecutionId());

        AuditDatabaseRow ticketCreated = findAudit(
                fixture.tenantId(),
                "TICKET_CREATED"
        );
        assertEquals("AGENT", ticketCreated.actorType());
        assertEquals(fixture.agentId(), ticketCreated.actorId());
        assertEquals("TICKET", ticketCreated.resourceType());
        assertEquals(ticket.id(), ticketCreated.resourceId());
        assertEquals(executionId, ticketCreated.toolExecutionId());

        AuditDatabaseRow toolCallCompleted = findAudit(
                fixture.tenantId(),
                "CONVERSATION_TOOL_CALL_COMPLETED"
        );
        assertEquals("MESSAGE", toolCallCompleted.resourceType());
        assertEquals(firstAssistantId, toolCallCompleted.resourceId());
        assertEquals(executionId, toolCallCompleted.toolExecutionId());

        AuditDatabaseRow toolMessageWritten = findAudit(
                fixture.tenantId(),
                "TOOL_MESSAGE_WRITTEN"
        );
        assertEquals("MESSAGE", toolMessageWritten.resourceType());
        assertEquals(toolMessageId, toolMessageWritten.resourceId());
        assertEquals(executionId, toolMessageWritten.toolExecutionId());

        AuditDatabaseRow continuationPrepared = findAudit(
                fixture.tenantId(),
                "CONVERSATION_TOOL_CONTINUATION_PREPARED"
        );
        assertEquals("MESSAGE", continuationPrepared.resourceType());
        assertEquals(finalAssistantId, continuationPrepared.resourceId());
        assertEquals(executionId, continuationPrepared.toolExecutionId());

        AuditDatabaseRow prepared = findAudit(
                fixture.tenantId(),
                "CONVERSATION_TURN_PREPARED"
        );
        assertEquals("USER", prepared.actorType());
        assertEquals(fixture.userId(), prepared.actorId());
        assertEquals("CONVERSATION", prepared.resourceType());
        assertEquals(
                fixture.conversationId(),
                prepared.resourceId()
        );

        AuditDatabaseRow completedTurn = findAudit(
                fixture.tenantId(),
                "CONVERSATION_TURN_COMPLETED"
        );
        assertEquals("AGENT", completedTurn.actorType());
        assertEquals(fixture.agentId(), completedTurn.actorId());
        assertEquals("MESSAGE", completedTurn.resourceType());
        assertEquals(finalAssistantId, completedTurn.resourceId());

        String auditJson = readAllAudits(fixture.tenantId())
                .stream()
                .map(audit ->
                        orEmpty(audit.beforeJson())
                                + orEmpty(audit.afterJson())
                                + orEmpty(audit.errorCode())
                                + orEmpty(audit.errorMessage()))
                .reduce("", String::concat);

        assertFalse(auditJson.contains(SYSTEM_PROMPT));

        String metadataJson = messages.stream()
                .map(row -> orEmpty(row.metadataJson()))
                .reduce("", String::concat);

        assertFalse(metadataJson.contains(SYSTEM_PROMPT));
    }

    @Test
    void shouldFailExecutionWithoutTicketOrCreatingPlaceholderForInvalidArguments()
            throws Exception {
        Fixture fixture = insertFixture();
        String memberToken = issueMemberToken(fixture);

        gateway.enqueue((request, handler) -> {
            handler.onEvent(new ChatModelStreamEvent.ToolCallDelta(
                    0, "call-bad", null, null
            ));
            handler.onEvent(new ChatModelStreamEvent.ToolCallDelta(
                    0, null, "create_ticket",
                    "{\"title\":\"Broken service\","
                            + "\"description\":\"Details\","
                            + "\"priority\":\"CRITICAL\"}"
            ));
            handler.onEvent(new ChatModelStreamEvent.Completed(
                    ChatModelFinishReason.TOOL_CALLS,
                    new ChatTokenUsage(11, 7)
            ));
        });

        ResponseEntity<String> streamed =
                streamTurn(
                        memberToken,
                        fixture.conversationId(),
                        "Please create a broken ticket"
                );

        assertEquals(HttpStatus.OK, streamed.getStatusCode());

        String body = requireBody(streamed);

        List<SseFrame> frames = parseSse(body);

        assertEquals(2, frames.size());
        assertEquals("started", frames.get(0).event());
        assertEquals("error", frames.get(1).event());

        JsonNode error = frames.get(1).data();

        assertEquals(
                "INVALID_ARGUMENT",
                error.get("errorCode").asText()
        );

        assertEquals(1, gateway.requests().size());

        assertEquals(0L, countTickets(fixture.tenantId()));

        List<MessageDatabaseRow> messages =
                readMessages(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        assertEquals(3, messages.size());

        assertEquals(3L, messages.get(2).sequenceNo());
        assertEquals("ASSISTANT", messages.get(2).role());
        assertEquals("JSON", messages.get(2).contentType());
        assertEquals("COMPLETED", messages.get(2).status());

        for (MessageDatabaseRow message : messages) {
            assertFalse("CREATING".equals(message.status()));
        }

        ConversationSnapshot conversation =
                readConversationSnapshot(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        assertEquals(4L, conversation.nextMessageSequence());
        assertEquals(1, conversation.version());

        List<ToolExecutionDatabaseRow> executions =
                readToolExecutions(fixture.tenantId());

        assertEquals(1, executions.size());

        ToolExecutionDatabaseRow execution = executions.get(0);

        assertEquals("FAILED", execution.status());
        assertEquals("INVALID_TOOL_INPUT", execution.errorCode());
        assertNull(execution.resultMessageId());
        assertNull(execution.resultEntityId());
        assertNull(execution.outputJson());

        assertFalse(body.contains("CRITICAL"));
        assertFalse(body.contains("does not match"));

        String auditJson = readAllAudits(fixture.tenantId())
                .stream()
                .map(audit ->
                        orEmpty(audit.beforeJson())
                                + orEmpty(audit.afterJson())
                                + orEmpty(audit.errorCode())
                                + orEmpty(audit.errorMessage()))
                .reduce("", String::concat);

        assertFalse(auditJson.contains("CRITICAL"));
        assertFalse(auditJson.contains(SYSTEM_PROMPT));

        String metadataJson = messages.stream()
                .map(row -> orEmpty(row.metadataJson()))
                .reduce("", String::concat);

        assertFalse(metadataJson.contains("CRITICAL"));
    }

    @Test
    void shouldKeepSucceededTicketAndFailFinalAssistantWhenSecondModelFails()
            throws Exception {
        Fixture fixture = insertFixture();
        String memberToken = issueMemberToken(fixture);

        gateway.enqueue((request, handler) -> {
            handler.onEvent(new ChatModelStreamEvent.ToolCallDelta(
                    0, "call-", null, null
            ));
            handler.onEvent(new ChatModelStreamEvent.ToolCallDelta(
                    0, "1", "create_",
                    "{\"title\":\"Server down\","
            ));
            handler.onEvent(new ChatModelStreamEvent.ToolCallDelta(
                    0, null, "ticket",
                    "\"description\":\"Cannot connect\","
                            + "\"priority\":\"HIGH\"}"
            ));
            handler.onEvent(new ChatModelStreamEvent.Completed(
                    ChatModelFinishReason.TOOL_CALLS,
                    new ChatTokenUsage(11, 7)
            ));
        });

        gateway.enqueue((request, handler) -> {
            handler.onEvent(new ChatModelStreamEvent.TextDelta(
                    "Partially created "
            ));

            throw new ChatModelException(
                    ChatModelErrorCategory.RATE_LIMIT,
                    PROVIDER_SECRET,
                    429,
                    new IllegalStateException(CAUSE_SECRET)
            );
        });

        ResponseEntity<String> streamed =
                streamTurn(
                        memberToken,
                        fixture.conversationId(),
                        "Please create a ticket"
                );

        assertEquals(HttpStatus.OK, streamed.getStatusCode());

        String body = requireBody(streamed);

        assertFalse(body.contains(PROVIDER_SECRET));
        assertFalse(body.contains(CAUSE_SECRET));
        assertFalse(body.contains("event:completed"));

        List<SseFrame> frames = parseSse(body);

        assertEquals(3, frames.size());
        assertEquals("started", frames.get(0).event());
        assertEquals("delta", frames.get(1).event());
        assertEquals("error", frames.get(2).event());

        assertEquals(
                "Partially created ",
                frames.get(1).data().get("text").asText()
        );

        JsonNode error = frames.get(2).data();

        assertEquals(
                "CHAT_MODEL_RATE_LIMIT",
                error.get("errorCode").asText()
        );

        List<TicketDatabaseRow> tickets =
                readTickets(fixture.tenantId());

        assertEquals(1, tickets.size());
        assertEquals("AGENT", tickets.get(0).source());

        List<ToolExecutionDatabaseRow> executions =
                readToolExecutions(fixture.tenantId());

        assertEquals(1, executions.size());
        assertEquals("SUCCEEDED", executions.get(0).status());

        List<MessageDatabaseRow> messages =
                readMessages(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        assertEquals(5, messages.size());

        assertEquals(4L, messages.get(3).sequenceNo());
        assertEquals("TOOL", messages.get(3).role());
        assertEquals("COMPLETED", messages.get(3).status());

        MessageDatabaseRow finalAssistant = messages.get(4);

        assertEquals(5L, finalAssistant.sequenceNo());
        assertEquals("ASSISTANT", finalAssistant.role());
        assertEquals("FAILED", finalAssistant.status());
        assertEquals("", finalAssistant.content());
        assertNull(finalAssistant.promptTokens());
        assertNull(finalAssistant.completionTokens());

        JsonNode metadata = parseJson(finalAssistant.metadataJson());

        assertEquals(
                "CHAT_MODEL_RATE_LIMIT",
                metadata.get("errorCode").asText()
        );

        ConversationSnapshot conversation =
                readConversationSnapshot(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        assertEquals(6L, conversation.nextMessageSequence());
        assertEquals(2, conversation.version());

        assertAuditActionExists(
                fixture.tenantId(),
                "CONVERSATION_TURN_FAILED"
        );

        assertEquals(
                0L,
                countAudits(
                        fixture.tenantId(),
                        "CONVERSATION_TURN_COMPLETED"
                )
        );

        String auditJson = readAllAudits(fixture.tenantId())
                .stream()
                .map(audit ->
                        orEmpty(audit.beforeJson())
                                + orEmpty(audit.afterJson())
                                + orEmpty(audit.errorCode())
                                + orEmpty(audit.errorMessage()))
                .reduce("", String::concat);

        assertFalse(auditJson.contains(PROVIDER_SECRET));
        assertFalse(auditJson.contains(CAUSE_SECRET));

        String metadataJson = messages.stream()
                .map(row -> orEmpty(row.metadataJson()))
                .reduce("", String::concat);

        assertFalse(metadataJson.contains(PROVIDER_SECRET));
        assertFalse(metadataJson.contains(CAUSE_SECRET));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GatewayTestConfiguration {

        @Bean
        QueuedChatModelGateway queuedChatModelGateway(
                CurrentActorProvider currentActorProvider
        ) {
            return new QueuedChatModelGateway(
                    currentActorProvider
            );
        }
    }

    @FunctionalInterface
    private interface GatewayScript {

        void stream(
                ChatModelRequest request,
                ChatModelStreamHandler handler
        );
    }

    static final class QueuedChatModelGateway
            implements ChatModelGateway {

        private final CurrentActorProvider actorProvider;
        private final Queue<GatewayScript> scripts =
                new ConcurrentLinkedQueue<>();
        private final List<ChatModelRequest> requests =
                new CopyOnWriteArrayList<>();
        private final List<CurrentActor> actors =
                new CopyOnWriteArrayList<>();

        QueuedChatModelGateway(
                CurrentActorProvider actorProvider
        ) {
            this.actorProvider = Objects.requireNonNull(
                    actorProvider,
                    "actorProvider must not be null"
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
            requests.add(request);
            actors.add(actorProvider.requireCurrentActor());

            GatewayScript script = scripts.poll();

            if (script == null) {
                throw new IllegalStateException(
                        "No gateway script configured"
                );
            }

            script.stream(request, handler);
        }

        void enqueue(GatewayScript script) {
            scripts.add(script);
        }

        void reset() {
            scripts.clear();
            requests.clear();
            actors.clear();
        }

        List<ChatModelRequest> requests() {
            return requests;
        }

        List<CurrentActor> actors() {
            return actors;
        }
    }

    private Fixture insertFixture() {
        long base = FIXTURE_IDS.getAndAdd(100L);

        long tenantId = base + 1;
        long userId = base + 2;
        long agentId = base + 3;
        long conversationId = base + 4;
        long userMessageId = base + 5;
        String username = "user" + userId;

        jdbcTemplate.update(
                """
                INSERT INTO tenants
                    (id, code, name, status, version)
                VALUES
                    (?, ?, ?, 'ACTIVE', 0)
                """,
                tenantId,
                "tenant-" + tenantId,
                "Tenant " + tenantId
        );

        jdbcTemplate.update(
                """
                INSERT INTO users
                    (
                        id, tenant_id, username, password_hash,
                        display_name, status, version
                    )
                VALUES
                    (?, ?, ?, 'not-a-real-hash',
                     ?, 'ACTIVE', 0)
                """,
                userId,
                tenantId,
                username,
                "User " + userId
        );

        jdbcTemplate.update(
                """
                INSERT INTO agents
                    (
                        id, tenant_id, code, name, system_prompt,
                        model_provider, model_name, model_config,
                        status, created_by_user_id, version
                    )
                VALUES
                    (
                        ?, ?, ?, 'Agent', ?, 'OPENAI', ?,
                        CAST(? AS JSON), 'ACTIVE', ?, 0
                    )
                """,
                agentId,
                tenantId,
                "agent-" + agentId,
                SYSTEM_PROMPT,
                AGENT_MODEL_NAME,
                """
                {
                  "temperature": 0.2,
                  "topP": 0.9,
                  "maxOutputTokens": 2048
                }
                """,
                userId
        );

        jdbcTemplate.update(
                """
                INSERT INTO conversations
                    (
                        id, tenant_id, user_id, agent_id, title,
                        status, last_message_at,
                        next_message_sequence, version
                    )
                VALUES
                    (
                        ?, ?, ?, ?, 'Seed conversation',
                        'ACTIVE', CURRENT_TIMESTAMP(3), 2, 0
                    )
                """,
                conversationId,
                tenantId,
                userId,
                agentId
        );

        jdbcTemplate.update(
                """
                INSERT INTO messages
                    (
                        id, tenant_id, conversation_id,
                        sequence_no, `role`, content,
                        content_type, status
                    )
                VALUES
                    (?, ?, ?, 1, 'USER', 'Initial message',
                     'TEXT', 'COMPLETED')
                """,
                userMessageId,
                tenantId,
                conversationId
        );

        return new Fixture(
                tenantId,
                userId,
                agentId,
                conversationId,
                username
        );
    }

    private String issueMemberToken(Fixture fixture) {
        IssuedAccessToken token =
                accessTokenIssuer.issue(
                        new TokenSubject(
                                fixture.userId(),
                                fixture.tenantId(),
                                fixture.username(),
                                List.of("MEMBER")
                        )
                );

        return token.value();
    }

    private ResponseEntity<String> streamTurn(
            String accessToken,
            long conversationId,
            String content
    ) {
        return restTemplate.exchange(
                "/api/v1/conversations/"
                        + "{conversationId}/turns:stream",
                HttpMethod.POST,
                new HttpEntity<>(
                        new StreamConversationTurnRequest(content),
                        bearerHeaders(accessToken)
                ),
                String.class,
                conversationId
        );
    }

    private List<SseFrame> parseSse(String body) throws Exception {
        String normalized = body.replace("\r\n", "\n");
        String[] blocks = normalized.split("\n\n+");

        List<SseFrame> frames = new ArrayList<>();

        for (String block : blocks) {
            String event = null;
            StringBuilder data = new StringBuilder();

            for (String line : block.split("\n")) {
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

    private JsonNode parseToolOutput(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Tool output is not valid JSON",
                    exception
            );
        }
    }

    private JsonNode parseJson(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    private List<MessageDatabaseRow> readMessages(
            long tenantId,
            long conversationId
    ) {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
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

    private ConversationSnapshot readConversationSnapshot(
            long tenantId,
            long conversationId
    ) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    next_message_sequence,
                    version
                FROM conversations
                WHERE tenant_id = ?
                  AND id = ?
                """,
                (resultSet, rowNumber) ->
                        new ConversationSnapshot(
                                resultSet.getLong(
                                        "next_message_sequence"
                                ),
                                resultSet.getInt("version")
                        ),
                tenantId,
                conversationId
        );
    }

    private List<TicketDatabaseRow> readTickets(long tenantId) {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    ticket_no,
                    title,
                    description,
                    priority,
                    status,
                    source,
                    requester_user_id,
                    created_by_agent_id
                FROM tickets
                WHERE tenant_id = ?
                ORDER BY id
                """,
                (resultSet, rowNumber) ->
                        new TicketDatabaseRow(
                                resultSet.getLong("id"),
                                resultSet.getString("ticket_no"),
                                resultSet.getString("title"),
                                resultSet.getString(
                                        "description"
                                ),
                                resultSet.getString("priority"),
                                resultSet.getString("status"),
                                resultSet.getString("source"),
                                resultSet.getLong(
                                        "requester_user_id"
                                ),
                                resultSet.getObject(
                                        "created_by_agent_id",
                                        Long.class
                                )
                        ),
                tenantId
        );
    }

    private List<ToolExecutionDatabaseRow> readToolExecutions(
            long tenantId
    ) {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    request_message_id,
                    result_message_id,
                    status,
                    result_entity_type,
                    result_entity_id,
                    error_code,
                    CAST(input_json AS CHAR) AS input_json,
                    CAST(output_json AS CHAR) AS output_json
                FROM tool_executions
                WHERE tenant_id = ?
                ORDER BY id
                """,
                (resultSet, rowNumber) ->
                        new ToolExecutionDatabaseRow(
                                resultSet.getLong("id"),
                                resultSet.getObject(
                                        "request_message_id",
                                        Long.class
                                ),
                                resultSet.getObject(
                                        "result_message_id",
                                        Long.class
                                ),
                                resultSet.getString("status"),
                                resultSet.getString(
                                        "result_entity_type"
                                ),
                                resultSet.getObject(
                                        "result_entity_id",
                                        Long.class
                                ),
                                resultSet.getString("error_code"),
                                resultSet.getString(
                                        "input_json"
                                ),
                                resultSet.getString(
                                        "output_json"
                                )
                        ),
                tenantId
        );
    }

    private List<AuditDatabaseRow> readAllAudits(long tenantId) {
        return jdbcTemplate.query(
                """
                SELECT
                    actor_type,
                    actor_id,
                    action,
                    resource_type,
                    resource_id,
                    tool_execution_id,
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

    private void assertAuditActionExists(
            long tenantId,
            String action
    ) {
        long count = countAudits(tenantId, action);
        assertTrue(
                count > 0,
                "Expected at least one audit with action "
                        + action
        );
    }

    private AuditDatabaseRow findAudit(
            long tenantId,
            String action
    ) {
        return readAllAudits(tenantId).stream()
                .filter(audit -> action.equals(audit.action()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Audit action not found: " + action
                ));
    }

    private long countAudits(long tenantId, String action) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_logs
                WHERE tenant_id = ?
                  AND action = ?
                """,
                Long.class,
                tenantId,
                action
        );

        return count == null ? 0L : count;
    }

    private long countTickets(long tenantId) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM tickets
                WHERE tenant_id = ?
                """,
                Long.class,
                tenantId
        );

        return count == null ? 0L : count;
    }

    private static HttpHeaders bearerHeaders(
            String accessToken
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
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

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private record Fixture(
            long tenantId,
            long userId,
            long agentId,
            long conversationId,
            String username
    ) {
    }

    private record SseFrame(String event, JsonNode data) {
    }

    private record ConversationSnapshot(
            long nextMessageSequence,
            int version
    ) {
    }

    private record MessageDatabaseRow(
            long id,
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

    private record TicketDatabaseRow(
            long id,
            String ticketNo,
            String title,
            String description,
            String priority,
            String status,
            String source,
            long requesterUserId,
            Long createdByAgentId
    ) {
    }

    private record ToolExecutionDatabaseRow(
            long id,
            Long requestMessageId,
            Long resultMessageId,
            String status,
            String resultEntityType,
            Long resultEntityId,
            String errorCode,
            String inputJson,
            String outputJson
    ) {
    }

    private record AuditDatabaseRow(
            String actorType,
            Long actorId,
            String action,
            String resourceType,
            Long resourceId,
            Long toolExecutionId,
            String beforeJson,
            String afterJson,
            String errorCode,
            String errorMessage
    ) {
    }
}
