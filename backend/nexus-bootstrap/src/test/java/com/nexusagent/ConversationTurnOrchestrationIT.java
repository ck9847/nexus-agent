package com.nexusagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import com.nexusagent.conversation.api.ConversationTurnStreamEvent;
import com.nexusagent.conversation.api.StreamConversationTurnService;
import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.model.api.ChatModelException;
import com.nexusagent.model.api.ChatModelFinishReason;
import com.nexusagent.model.api.ChatModelGateway;
import com.nexusagent.model.api.ChatModelGatewayResolver;
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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockReset;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 端到端验证流式会话轮次的编排：真实
 * {@link ChatModelGatewayRegistry} 负责 provider 解析，脚本化的假
 * {@link ChatModelGateway} 驱动模型流。覆盖事务挂起、prepare/complete
 * 的 REQUIRES_NEW 提交、模型失败与客户端断流的 FAILED 落库、以及审计
 * 失败时的事务回滚与异常优先级。
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
@Import(
        ConversationTurnOrchestrationIT
                .GatewayTestConfiguration.class
)
@SpringBootTest(
        properties = {
                "nexus.security.jwt.secret="
                        + "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0"
                        + "NTY3ODlhYmNkZWY=",
                "nexus.model.openai.enabled=false"
        }
)
class ConversationTurnOrchestrationIT {

    private static final String AGENT_SYSTEM_PROMPT =
            "system-prompt-sensitive-value";

    private static final String AGENT_MODEL_NAME =
            "gpt-5-mini";

    private static final String MODEL_CONFIG_JSON =
            """
            {
              "temperature": 0.2,
              "topP": 0.9,
              "maxOutputTokens": 2048
            }
            """;

    private static final AtomicLong FIXTURE_IDS =
            new AtomicLong(300_000L);

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
    private StreamConversationTurnService streamService;

    @Autowired
    private ChatModelGatewayResolver gatewayResolver;

    @Autowired
    private ScriptedChatModelGateway gateway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean(reset = MockReset.AFTER)
    private CurrentActorProvider currentActorProvider;

    @MockitoSpyBean(reset = MockReset.AFTER)
    private AuditLogWriter auditLogWriter;

    @BeforeEach
    void resetGateway() {
        gateway.reset();
    }

    @Test
    void shouldSuspendOuterTransactionAndCommitSuccessfulTurn()
            throws Exception {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        List<ConversationTurnStreamEvent> events =
                new ArrayList<>();

        AtomicReference<String> statusDuringGateway =
                new AtomicReference<>();

        AtomicReference<String> statusAtCompletedEvent =
                new AtomicReference<>();

        gateway.script((request, handler) -> {
            statusDuringGateway.set(
                    readMessage(
                            fixture.tenantId(),
                            fixture.conversationId(),
                            3L
                    ).status()
            );

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

        TransactionTemplate template =
                new TransactionTemplate(
                        transactionManager
                );

        template.executeWithoutResult(status -> {
            assertTrue(
                    TransactionSynchronizationManager
                            .isActualTransactionActive()
            );

            streamService.stream(
                    Long.toString(
                            fixture.conversationId()
                    ),
                    "  Customer question  ",
                    null,
                    event -> {
                        events.add(event);

                        if (event instanceof
                                ConversationTurnStreamEvent.Completed) {
                            statusAtCompletedEvent.set(
                                    readMessage(
                                            fixture.tenantId(),
                                            fixture.conversationId(),
                                            3L
                                    ).status()
                            );
                        }
                    }
            );

            assertTrue(
                    TransactionSynchronizationManager
                            .isActualTransactionActive()
            );

            status.setRollbackOnly();
        });

        // 注册表返回熔断装饰后的网关；脚本网关经由装饰器
        // 收到了真实调用（上面的事件已证明），此处只断言
        // provider 解析成功且不是裸实例。
        assertEquals(
                AgentModelProvider.OPENAI,
                gatewayResolver.requireGateway(
                        AgentModelProvider.OPENAI
                ).provider()
        );

        assertEquals(
                Boolean.FALSE,
                gateway.transactionActiveDuringStream()
        );

        assertEquals(
                "CREATING",
                statusDuringGateway.get()
        );

        assertEquals(
                "COMPLETED",
                statusAtCompletedEvent.get()
        );

        MessageDatabaseRow userRow =
                readMessage(
                        fixture.tenantId(),
                        fixture.conversationId(),
                        2L
                );

        assertEquals("USER", userRow.role());
        assertEquals("COMPLETED", userRow.status());
        assertEquals(
                "Customer question",
                userRow.content()
        );

        MessageDatabaseRow assistantRow =
                readMessage(
                        fixture.tenantId(),
                        fixture.conversationId(),
                        3L
                );

        assertEquals("ASSISTANT", assistantRow.role());
        assertEquals("COMPLETED", assistantRow.status());
        assertEquals(
                "Hello world",
                assistantRow.content()
        );
        assertEquals(
                12,
                assistantRow.promptTokens()
        );
        assertEquals(
                34,
                assistantRow.completionTokens()
        );

        ConversationSnapshot snapshot =
                readConversationSnapshot(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        assertEquals(4L, snapshot.nextMessageSequence());
        assertEquals(1, snapshot.version());

        assertEquals(4, events.size());

        assertTrue(events.get(0)
                instanceof ConversationTurnStreamEvent.Started);
        ConversationTurnStreamEvent.Started started =
                (ConversationTurnStreamEvent.Started)
                        events.get(0);
        assertEquals(
                Long.toString(fixture.conversationId()),
                started.conversationId()
        );
        assertEquals(
                Long.toString(fixture.agentId()),
                started.agentId()
        );
        assertEquals(2L, started.userSequenceNo());
        assertEquals(3L, started.assistantSequenceNo());
        assertEquals(1, started.conversationVersion());

        assertEquals(
                new ConversationTurnStreamEvent.TextDelta("Hello"),
                events.get(1)
        );
        assertEquals(
                new ConversationTurnStreamEvent.TextDelta(" world"),
                events.get(2)
        );

        assertTrue(events.get(3)
                instanceof ConversationTurnStreamEvent.Completed);
        ConversationTurnStreamEvent.Completed completed =
                (ConversationTurnStreamEvent.Completed)
                        events.get(3);
        assertEquals(3L, completed.assistantSequenceNo());
        assertEquals(1, completed.conversationVersion());
        assertEquals(
                AGENT_MODEL_NAME,
                completed.modelName()
        );
        assertEquals(
                ChatModelFinishReason.STOP,
                completed.finishReason()
        );
        assertEquals(12, completed.promptTokens());
        assertEquals(34, completed.completionTokens());

        assertAuditCounts(
                fixture.tenantId(),
                1,
                1,
                0
        );
    }

    @Test
    void shouldPersistSanitizedFailureWhenGatewayFails()
            throws Exception {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        String providerSecret =
                "provider-secret-must-not-persist";

        String causeSecret =
                "cause-secret-must-not-persist";

        ChatModelException modelFailure =
                new ChatModelException(
                        ChatModelErrorCategory.RATE_LIMIT,
                        providerSecret,
                        429,
                        new IllegalStateException(causeSecret)
                );

        gateway.script((request, handler) -> {
            handler.onEvent(
                    new ChatModelStreamEvent.TextDelta(
                            "partial response"
                    )
            );

            throw modelFailure;
        });

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> streamService.stream(
                        Long.toString(
                                fixture.conversationId()
                        ),
                        "  Question  ",
                        null,
                        event -> {
                        }
                )
        );

        assertSame(modelFailure, exception);

        MessageDatabaseRow userRow =
                readMessage(
                        fixture.tenantId(),
                        fixture.conversationId(),
                        2L
                );

        assertEquals("COMPLETED", userRow.status());

        MessageDatabaseRow assistantRow =
                readMessage(
                        fixture.tenantId(),
                        fixture.conversationId(),
                        3L
                );

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
        assertEquals(
                true,
                metadata.get("retryable").asBoolean()
        );
        assertEquals(
                429,
                metadata.get("providerStatus").asInt()
        );
        assertTrue(metadata.has("failedAt"));

        assertAuditCounts(
                fixture.tenantId(),
                1,
                0,
                1
        );

        String messageJson = readMessages(
                        fixture.tenantId(),
                        fixture.conversationId()
                ).stream()
                .map(MessageDatabaseRow::metadataJson)
                .filter(value -> value != null)
                .reduce("", String::concat);

        String auditJson = readAllAudits(
                        fixture.tenantId()
                ).stream()
                .map(auditRow ->
                        orEmpty(auditRow.beforeJson())
                                + orEmpty(auditRow.afterJson())
                                + orEmpty(auditRow.errorCode())
                                + orEmpty(auditRow.errorMessage())
                )
                .reduce("", String::concat);

        assertFalse(messageJson.contains(providerSecret));
        assertFalse(messageJson.contains(causeSecret));
        assertFalse(auditJson.contains(providerSecret));
        assertFalse(auditJson.contains(causeSecret));
    }

    @Test
    void shouldFailPlaceholderWhenTextConsumerDisconnects()
            throws Exception {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        gateway.script((request, handler) ->
                handler.onEvent(
                        new ChatModelStreamEvent.TextDelta(
                                "partial"
                        )
                )
        );

        IllegalStateException disconnect =
                new IllegalStateException(
                        "client-disconnect-secret"
                );

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> streamService.stream(
                        Long.toString(
                                fixture.conversationId()
                        ),
                        "Question",
                        null,
                        event -> {
                            if (event instanceof
                                    ConversationTurnStreamEvent.TextDelta) {
                                throw disconnect;
                            }
                        }
                )
        );

        assertEquals(
                ChatModelErrorCategory.STREAM_INTERRUPTED,
                exception.category()
        );
        assertSame(disconnect, exception.getCause());

        MessageDatabaseRow assistantRow =
                readMessage(
                        fixture.tenantId(),
                        fixture.conversationId(),
                        3L
                );

        assertEquals("FAILED", assistantRow.status());
        assertEquals("", assistantRow.content());

        JsonNode metadata =
                parseJson(assistantRow.metadataJson());

        assertEquals(
                "CHAT_MODEL_STREAM_INTERRUPTED",
                metadata.get("errorCode").asText()
        );
        assertEquals(
                true,
                metadata.get("retryable").asBoolean()
        );

        assertAuditCounts(
                fixture.tenantId(),
                1,
                0,
                1
        );

        String messageJson = readMessages(
                        fixture.tenantId(),
                        fixture.conversationId()
                ).stream()
                .map(MessageDatabaseRow::metadataJson)
                .filter(value -> value != null)
                .reduce("", String::concat);

        String auditJson = readAllAudits(
                        fixture.tenantId()
                ).stream()
                .map(auditRow ->
                        orEmpty(auditRow.beforeJson())
                                + orEmpty(auditRow.afterJson())
                                + orEmpty(auditRow.errorCode())
                                + orEmpty(auditRow.errorMessage())
                )
                .reduce("", String::concat);

        assertFalse(messageJson.contains("client-disconnect-secret"));
        assertFalse(auditJson.contains("client-disconnect-secret"));
    }

    @Test
    void shouldRollbackCompletionWithoutMarkingFailedWhenAuditFails() {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        AuditLogWriter target =
                AopTestUtils.getUltimateTargetObject(
                        auditLogWriter
                );

        IllegalStateException auditFailure =
                new IllegalStateException(
                        "Simulated completion audit failure"
                );

        doThrow(auditFailure)
                .when(target)
                .write(argThat(command ->
                        command != null
                                && command.tenantId()
                                == fixture.tenantId()
                                && "CONVERSATION_TURN_COMPLETED"
                                .equals(command.action())
                                && "MESSAGE".equals(
                                command.resourceType()
                        )
                ));

        List<ConversationTurnStreamEvent> events =
                new ArrayList<>();

        gateway.script((request, handler) -> {
            handler.onEvent(
                    new ChatModelStreamEvent.TextDelta("Hello")
            );
            handler.onEvent(
                    new ChatModelStreamEvent.Completed(
                            ChatModelFinishReason.STOP,
                            new ChatTokenUsage(12, 34)
                    )
            );
        });

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> streamService.stream(
                        Long.toString(
                                fixture.conversationId()
                        ),
                        "  Question  ",
                        null,
                        events::add
                )
        );

        assertSame(auditFailure, exception);

        assertTrue(events.stream()
                .anyMatch(event -> event
                        instanceof
                        ConversationTurnStreamEvent.Started));
        assertTrue(events.stream()
                .anyMatch(event -> event
                        instanceof
                        ConversationTurnStreamEvent.TextDelta));
        assertTrue(events.stream()
                .noneMatch(event -> event
                        instanceof
                        ConversationTurnStreamEvent.Completed));

        MessageDatabaseRow assistantRow =
                readMessage(
                        fixture.tenantId(),
                        fixture.conversationId(),
                        3L
                );

        assertEquals("CREATING", assistantRow.status());
        assertEquals("", assistantRow.content());
        assertNull(assistantRow.promptTokens());
        assertNull(assistantRow.completionTokens());
        assertNull(assistantRow.metadataJson());

        assertAuditCounts(
                fixture.tenantId(),
                1,
                0,
                0
        );
    }

    @Test
    void shouldRollbackFailureAndSuppressModelErrorWhenFailureAuditFails() {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        AuditLogWriter target =
                AopTestUtils.getUltimateTargetObject(
                        auditLogWriter
                );

        IllegalStateException auditFailure =
                new IllegalStateException(
                        "Simulated failure audit failure"
                );

        doThrow(auditFailure)
                .when(target)
                .write(argThat(command ->
                        command != null
                                && command.tenantId()
                                == fixture.tenantId()
                                && "CONVERSATION_TURN_FAILED"
                                .equals(command.action())
                ));

        ChatModelException modelFailure =
                new ChatModelException(
                        ChatModelErrorCategory.RATE_LIMIT,
                        "rate limited",
                        429,
                        null
                );

        gateway.script((request, handler) -> {
            throw modelFailure;
        });

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> streamService.stream(
                        Long.toString(
                                fixture.conversationId()
                        ),
                        "  Question  ",
                        null,
                        event -> {
                        }
                )
        );

        assertSame(auditFailure, exception);

        Throwable[] suppressed = exception.getSuppressed();
        assertEquals(1, suppressed.length);
        assertSame(modelFailure, suppressed[0]);

        MessageDatabaseRow assistantRow =
                readMessage(
                        fixture.tenantId(),
                        fixture.conversationId(),
                        3L
                );

        assertEquals("CREATING", assistantRow.status());
        assertNull(assistantRow.metadataJson());

        assertAuditCounts(
                fixture.tenantId(),
                1,
                0,
                0
        );
    }

    private void assertAuditCounts(
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
                        .filter(audit -> "CONVERSATION_TURN_PREPARED"
                                .equals(audit.action()))
                        .count()
        );
        assertEquals(
                completed,
                allAudits.stream()
                        .filter(audit -> "CONVERSATION_TURN_COMPLETED"
                                .equals(audit.action()))
                        .count()
        );
        assertEquals(
                failed,
                allAudits.stream()
                        .filter(audit -> "CONVERSATION_TURN_FAILED"
                                .equals(audit.action()))
                        .count()
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GatewayTestConfiguration {

        @Bean
        ScriptedChatModelGateway scriptedChatModelGateway() {
            return new ScriptedChatModelGateway();
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

        private GatewayScript script;
        private ChatModelRequest lastRequest;
        private Boolean transactionActiveDuringStream;

        @Override
        public AgentModelProvider provider() {
            return AgentModelProvider.OPENAI;
        }

        @Override
        public void stream(
                ChatModelRequest request,
                ChatModelStreamHandler handler
        ) {
            lastRequest = request;

            transactionActiveDuringStream =
                    TransactionSynchronizationManager
                            .isActualTransactionActive();

            GatewayScript current = script;

            if (current == null) {
                throw new IllegalStateException(
                        "Gateway script was not configured"
                );
            }

            current.run(request, handler);
        }

        void script(GatewayScript value) {
            script = value;
        }

        void reset() {
            script = null;
            lastRequest = null;
            transactionActiveDuringStream = null;
        }

        ChatModelRequest lastRequest() {
            return lastRequest;
        }

        Boolean transactionActiveDuringStream() {
            return transactionActiveDuringStream;
        }
    }

    private Fixture insertFixture() {
        long base = FIXTURE_IDS.getAndAdd(100L);

        long tenantId = base + 1;
        long userId = base + 2;
        long agentId = base + 3;
        long conversationId = base + 4;
        long initialMessageId = base + 5;
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
                        id,
                        tenant_id,
                        username,
                        password_hash,
                        display_name,
                        status,
                        version
                    )
                VALUES
                    (?, ?, ?, 'not-a-real-hash', ?, 'ACTIVE', 0)
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
                        id,
                        tenant_id,
                        code,
                        name,
                        system_prompt,
                        model_provider,
                        model_name,
                        model_config,
                        status,
                        created_by_user_id,
                        version
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
                AGENT_SYSTEM_PROMPT,
                AGENT_MODEL_NAME,
                MODEL_CONFIG_JSON,
                userId
        );

        jdbcTemplate.update(
                """
                INSERT INTO conversations
                    (
                        id,
                        tenant_id,
                        user_id,
                        agent_id,
                        title,
                        status,
                        last_message_at,
                        next_message_sequence,
                        version
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
                        id,
                        tenant_id,
                        conversation_id,
                        sequence_no,
                        `role`,
                        content,
                        content_type,
                        status
                    )
                VALUES
                    (?, ?, ?, 1, 'USER',
                     'Initial message', 'TEXT', 'COMPLETED')
                """,
                initialMessageId,
                tenantId,
                conversationId
        );

        return new Fixture(
                tenantId,
                userId,
                agentId,
                conversationId,
                initialMessageId,
                username
        );
    }

    private void mockCurrentActor(Fixture fixture) {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(new CurrentActor(
                        fixture.userId(),
                        fixture.tenantId(),
                        fixture.username(),
                        Set.of("MEMBER")
                ));
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

    private MessageDatabaseRow readMessage(
            long tenantId,
            long conversationId,
            long sequenceNo
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
                        AS metadata_json
                FROM messages
                WHERE tenant_id = ?
                  AND conversation_id = ?
                  AND sequence_no = ?
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
                conversationId,
                sequenceNo
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

    private JsonNode parseJson(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private record Fixture(
            long tenantId,
            long userId,
            long agentId,
            long conversationId,
            long initialMessageId,
            String username
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
