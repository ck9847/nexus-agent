package com.nexusagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import com.nexusagent.conversation.api.ConversationTurnInProgressException;
import com.nexusagent.conversation.internal.CompleteConversationTurnService;
import com.nexusagent.conversation.internal.CompletedConversationTurn;
import com.nexusagent.conversation.internal.FailConversationTurnService;
import com.nexusagent.conversation.internal.PrepareConversationTurnService;
import com.nexusagent.conversation.internal.PreparedConversationTurn;
import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.model.api.ChatModelException;
import com.nexusagent.model.api.ChatModelFinishReason;
import com.nexusagent.model.api.ChatModelMessage;
import com.nexusagent.model.api.ChatModelOptions;
import com.nexusagent.model.api.ChatTokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockReset;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest(
        properties = """
                nexus.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=
                """
)
class ConversationTurnPersistenceIT {

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

    private static final ChatModelOptions MODEL_OPTIONS =
            new ChatModelOptions(
                    new BigDecimal("0.2"),
                    new BigDecimal("0.9"),
                    2048
            );

    private static final AtomicLong FIXTURE_IDS =
            new AtomicLong(100_000L);

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
    private PrepareConversationTurnService prepareService;

    @Autowired
    private CompleteConversationTurnService completeService;

    @Autowired
    private FailConversationTurnService failService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean(reset = MockReset.AFTER)
    private CurrentActorProvider currentActorProvider;

    @MockitoSpyBean(reset = MockReset.AFTER)
    private AuditLogWriter auditLogWriter;

    @Test
    void shouldPrepareTurnWithExactMessagesModelRequestAndSafeAudit()
            throws Exception {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        PreparedConversationTurn prepared =
                prepareService.prepare(
                        Long.toString(
                                fixture.conversationId()
                        ),
                        "  New customer question  "
                );

        List<MessageDatabaseRow> messages =
                readMessages(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        assertEquals(
                List.of(1L, 2L, 3L),
                messages.stream()
                        .map(MessageDatabaseRow::sequenceNo)
                        .toList()
        );

        MessageDatabaseRow userRow =
                readMessage(
                        fixture.tenantId(),
                        fixture.conversationId(),
                        2L
                );

        assertEquals(
                "New customer question",
                userRow.content()
        );
        assertEquals("COMPLETED", userRow.status());

        MessageDatabaseRow assistantRow =
                readMessage(
                        fixture.tenantId(),
                        fixture.conversationId(),
                        3L
        );

        assertEquals("", assistantRow.content());
        assertEquals("CREATING", assistantRow.status());
        assertEquals(
                AGENT_MODEL_NAME,
                assistantRow.modelName()
        );
        assertNull(assistantRow.promptTokens());
        assertNull(assistantRow.completionTokens());
        assertNull(assistantRow.metadataJson());

        ConversationSnapshot snapshot =
                readConversationSnapshot(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        assertEquals(4L, snapshot.nextMessageSequence());
        assertEquals(1, snapshot.version());
        assertEquals(
                prepared.preparedAt(),
                snapshot.lastMessageAt()
        );
        assertEquals(
                prepared.preparedAt(),
                snapshot.updatedAt()
        );

        assertEquals(
                AGENT_MODEL_NAME,
                prepared.modelRequest().modelName()
        );
        assertEquals(
                AGENT_SYSTEM_PROMPT,
                prepared.modelRequest().systemPrompt()
        );
        assertEquals(
                MODEL_OPTIONS,
                prepared.modelRequest().options()
        );
        assertEquals(
                0,
                prepared.modelRequest().tools().size()
        );
        assertEquals(
                List.of(
                        ChatModelMessage.user(
                                "Initial message"
                        ),
                        ChatModelMessage.user(
                                "New customer question"
                        )
                ),
                prepared.modelRequest().messages()
        );

        List<AuditDatabaseRow> preparedAudits =
                readTurnAudits(
                        fixture.tenantId(),
                        "CONVERSATION_TURN_PREPARED",
                        "CONVERSATION",
                        fixture.conversationId()
                );

        assertEquals(1, preparedAudits.size());

        AuditDatabaseRow audit = preparedAudits.get(0);

        assertEquals("USER", audit.actorType());
        assertEquals(
                fixture.userId(),
                audit.actorId()
        );
        assertEquals("SUCCESS", audit.result());

        JsonNode after = parseJson(audit.afterJson());

        assertEquals(8, after.size());
        assertEquals(
                Long.toString(fixture.agentId()),
                after.get("agentId").asText()
        );
        assertEquals(
                Long.toString(prepared.userMessageId()),
                after.get("userMessageId").asText()
        );
        assertEquals(
                prepared.userSequenceNo(),
                after.get("userSequenceNo").asLong()
        );
        assertEquals(
                "COMPLETED",
                after.get("userStatus").asText()
        );
        assertEquals(
                Long.toString(prepared.assistantMessageId()),
                after.get("assistantMessageId").asText()
        );
        assertEquals(
                prepared.assistantSequenceNo(),
                after.get("assistantSequenceNo").asLong()
        );
        assertEquals(
                "CREATING",
                after.get("assistantStatus").asText()
        );
        assertEquals(
                prepared.conversationVersion(),
                after.get("conversationVersion").asInt()
        );

        String auditText = audit.afterJson();
        assertFalse(auditText.contains("New customer question"));
        assertFalse(auditText.contains("Initial message"));
        assertFalse(auditText.contains(AGENT_SYSTEM_PROMPT));
        assertFalse(auditText.contains("modelConfig"));
    }

    @Test
    void shouldRejectSecondTurnUntilCreatingAssistantIsCompleted() {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        PreparedConversationTurn first =
                prepareService.prepare(
                        Long.toString(
                                fixture.conversationId()
                        ),
                        "  First question  "
                );

        assertThrows(
                ConversationTurnInProgressException.class,
                () -> prepareService.prepare(
                        Long.toString(
                                fixture.conversationId()
                        ),
                        "Second question"
                )
        );

        assertEquals(
                3L,
                countMessages(
                        fixture.tenantId(),
                        fixture.conversationId()
                )
        );

        ConversationSnapshot interrupted =
                readConversationSnapshot(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        assertEquals(4L, interrupted.nextMessageSequence());
        assertEquals(1, interrupted.version());

        assertEquals(
                1L,
                countAudits(
                        fixture.tenantId(),
                        "CONVERSATION_TURN_PREPARED",
                        "CONVERSATION",
                        fixture.conversationId()
                )
        );

        completeService.complete(
                first,
                "  First answer  ",
                ChatModelFinishReason.STOP,
                new ChatTokenUsage(5, 7)
        );

        PreparedConversationTurn second =
                prepareService.prepare(
                        Long.toString(
                                fixture.conversationId()
                        ),
                        "  Third question  "
                );

        assertEquals(4L, second.userSequenceNo());
        assertEquals(5L, second.assistantSequenceNo());

        ConversationSnapshot advanced =
                readConversationSnapshot(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        assertEquals(6L, advanced.nextMessageSequence());
        assertEquals(2, advanced.version());
    }

    @Test
    void shouldCompleteCreatingAssistantWithExactMetadataAndAudit()
            throws Exception {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        PreparedConversationTurn prepared =
                prepareService.prepare(
                        Long.toString(
                                fixture.conversationId()
                        ),
                        "  Question  "
                );

        CompletedConversationTurn completed =
                completeService.complete(
                        prepared,
                        "  Preserve surrounding whitespace  ",
                        ChatModelFinishReason.STOP,
                        new ChatTokenUsage(12, 34)
                );

        MessageDatabaseRow assistantRow =
                readMessage(
                        fixture.tenantId(),
                        fixture.conversationId(),
                        3L
                );

        assertEquals(
                "  Preserve surrounding whitespace  ",
                assistantRow.content()
        );
        assertEquals("COMPLETED", assistantRow.status());
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
        assertEquals(
                completed.completedAt().toString(),
                metadata.get("completedAt").asText()
        );

        MessageDatabaseRow userRow =
                readMessage(
                        fixture.tenantId(),
                        fixture.conversationId(),
                        2L
                );

        assertEquals("COMPLETED", userRow.status());
        assertEquals("Question", userRow.content());
        assertNull(userRow.metadataJson());

        List<AuditDatabaseRow> audits =
                readTurnAudits(
                        fixture.tenantId(),
                        "CONVERSATION_TURN_COMPLETED",
                        "MESSAGE",
                        prepared.assistantMessageId()
                );

        assertEquals(1, audits.size());

        AuditDatabaseRow audit = audits.get(0);

        assertEquals("AGENT", audit.actorType());
        assertEquals(
                fixture.agentId(),
                audit.actorId()
        );
        assertEquals("MESSAGE", audit.resourceType());
        assertEquals(
                prepared.assistantMessageId(),
                audit.resourceId()
        );
        assertEquals("SUCCESS", audit.result());

        JsonNode before = parseJson(audit.beforeJson());

        assertEquals(1, before.size());
        assertEquals(
                "CREATING",
                before.get("status").asText()
        );

        JsonNode after = parseJson(audit.afterJson());

        assertEquals(10, after.size());
        assertEquals(
                Long.toString(fixture.conversationId()),
                after.get("conversationId").asText()
        );
        assertEquals(
                Long.toString(prepared.assistantMessageId()),
                after.get("messageId").asText()
        );
        assertEquals(
                prepared.assistantSequenceNo(),
                after.get("sequenceNo").asLong()
        );
        assertEquals(
                "COMPLETED",
                after.get("status").asText()
        );
        assertEquals(
                "OPENAI",
                after.get("modelProvider").asText()
        );
        assertEquals(
                AGENT_MODEL_NAME,
                after.get("modelName").asText()
        );
        assertEquals(
                "STOP",
                after.get("finishReason").asText()
        );
        assertEquals(12, after.get("promptTokens").asInt());
        assertEquals(
                34,
                after.get("completionTokens").asInt()
        );
        assertEquals(
                completed.completedAt().toString(),
                after.get("completedAt").asText()
        );

        String auditText = audit.afterJson();
        assertFalse(
                auditText.contains(
                        "Preserve surrounding whitespace"
                )
        );
        assertFalse(auditText.contains(AGENT_SYSTEM_PROMPT));
        assertFalse(auditText.contains("systemPrompt"));
        assertFalse(auditText.contains("modelConfig"));
    }

    @Test
    void shouldFailCreatingAssistantWithoutPersistingExceptionSecrets()
            throws Exception {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        PreparedConversationTurn prepared =
                prepareService.prepare(
                        Long.toString(
                                fixture.conversationId()
                        ),
                        "  Question  "
                );

        ChatModelException failure =
                new ChatModelException(
                        ChatModelErrorCategory.RATE_LIMIT,
                        "provider-secret-must-not-persist",
                        429,
                        new IllegalStateException(
                                "low-level-secret-must-not-persist"
                        )
                );

        failService.fail(prepared, failure);

        MessageDatabaseRow assistantRow =
                readMessage(
                        fixture.tenantId(),
                        fixture.conversationId(),
                        3L
                );

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

        List<AuditDatabaseRow> audits =
                readTurnAudits(
                        fixture.tenantId(),
                        "CONVERSATION_TURN_FAILED",
                        "MESSAGE",
                        prepared.assistantMessageId()
                );

        assertEquals(1, audits.size());

        AuditDatabaseRow audit = audits.get(0);

        assertEquals("AGENT", audit.actorType());
        assertEquals(
                fixture.agentId(),
                audit.actorId()
        );
        assertEquals("FAILURE", audit.result());
        assertEquals(
                "CHAT_MODEL_RATE_LIMIT",
                audit.errorCode()
        );
        assertEquals(
                "Chat model turn failed",
                audit.errorMessage()
        );

        JsonNode auditAfter = parseJson(audit.afterJson());
        assertEquals("FAILED", auditAfter.get("status").asText());
        assertEquals(
                true,
                auditAfter.get("retryable").asBoolean()
        );
        assertEquals(
                429,
                auditAfter.get("providerStatus").asInt()
        );

        List<MessageDatabaseRow> allMessages =
                readMessages(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        String messageJson = allMessages.stream()
                .map(MessageDatabaseRow::metadataJson)
                .filter(value -> value != null)
                .reduce("", String::concat);

        List<AuditDatabaseRow> allAudits =
                readAllAudits(fixture.tenantId());

        String auditJson = allAudits.stream()
                .map(auditRow ->
                        orEmpty(auditRow.beforeJson())
                                + orEmpty(auditRow.afterJson())
                                + orEmpty(auditRow.errorCode())
                                + orEmpty(auditRow.errorMessage())
                )
                .reduce("", String::concat);

        assertFalse(
                messageJson.contains(
                        "provider-secret-must-not-persist"
                )
        );
        assertFalse(
                messageJson.contains(
                        "low-level-secret-must-not-persist"
                )
        );
        assertFalse(
                auditJson.contains(
                        "provider-secret-must-not-persist"
                )
        );
        assertFalse(
                auditJson.contains(
                        "low-level-secret-must-not-persist"
                )
        );
    }

    @Test
    void shouldRollbackPreparedMessagesAndConversationWhenAuditFails() {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        ConversationSnapshot before =
                readConversationSnapshot(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        assertEquals(2L, before.nextMessageSequence());
        assertEquals(0, before.version());

        AuditLogWriter target =
                AopTestUtils.getUltimateTargetObject(
                        auditLogWriter
                );

        doThrow(new IllegalStateException(
                "Simulated turn audit failure"
        ))
                .when(target)
                .write(argThat(command ->
                        command != null
                                && command.tenantId()
                                == fixture.tenantId()
                                && "CONVERSATION_TURN_PREPARED"
                                .equals(command.action())
                ));

        assertThrows(
                IllegalStateException.class,
                () -> prepareService.prepare(
                        Long.toString(
                                fixture.conversationId()
                        ),
                        "  Question  "
                )
        );

        assertEquals(
                1L,
                countMessages(
                        fixture.tenantId(),
                        fixture.conversationId()
                )
        );

        List<MessageDatabaseRow> messages =
                readMessages(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        assertEquals(
                List.of(1L),
                messages.stream()
                        .map(MessageDatabaseRow::sequenceNo)
                        .toList()
        );

        ConversationSnapshot after =
                readConversationSnapshot(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        assertEquals(2L, after.nextMessageSequence());
        assertEquals(0, after.version());
        assertEquals(before.lastMessageAt(), after.lastMessageAt());
        assertEquals(before.updatedAt(), after.updatedAt());

        assertEquals(
                0L,
                countAudits(
                        fixture.tenantId(),
                        "CONVERSATION_TURN_PREPARED",
                        "CONVERSATION",
                        fixture.conversationId()
                )
        );
    }

    @Test
    void shouldRollbackCompletedAssistantMessageWhenAuditFails() {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        PreparedConversationTurn prepared =
                prepareService.prepare(
                        Long.toString(
                                fixture.conversationId()
                        ),
                        "  Question  "
                );

        AuditLogWriter target =
                AopTestUtils.getUltimateTargetObject(
                        auditLogWriter
                );

        doThrow(new IllegalStateException(
                "Simulated turn audit failure"
        ))
                .when(target)
                .write(argThat(command ->
                        command != null
                                && command.tenantId()
                                == fixture.tenantId()
                                && "CONVERSATION_TURN_COMPLETED"
                                .equals(command.action())
                ));

        assertThrows(
                IllegalStateException.class,
                () -> completeService.complete(
                        prepared,
                        "  Answer  ",
                        ChatModelFinishReason.STOP,
                        new ChatTokenUsage(1, 2)
                )
        );

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

        assertEquals(
                0L,
                countAudits(
                        fixture.tenantId(),
                        "CONVERSATION_TURN_COMPLETED",
                        "MESSAGE",
                        prepared.assistantMessageId()
                )
        );

        assertEquals(
                1L,
                countAudits(
                        fixture.tenantId(),
                        "CONVERSATION_TURN_PREPARED",
                        "CONVERSATION",
                        fixture.conversationId()
                )
        );
    }

    @Test
    void shouldRollbackFailedAssistantMessageWhenAuditFails() {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        PreparedConversationTurn prepared =
                prepareService.prepare(
                        Long.toString(
                                fixture.conversationId()
                        ),
                        "  Question  "
                );

        AuditLogWriter target =
                AopTestUtils.getUltimateTargetObject(
                        auditLogWriter
                );

        doThrow(new IllegalStateException(
                "Simulated turn audit failure"
        ))
                .when(target)
                .write(argThat(command ->
                        command != null
                                && command.tenantId()
                                == fixture.tenantId()
                                && "CONVERSATION_TURN_FAILED"
                                .equals(command.action())
                ));

        assertThrows(
                IllegalStateException.class,
                () -> failService.fail(
                        prepared,
                        new ChatModelException(
                                ChatModelErrorCategory.RATE_LIMIT,
                                "boom",
                                429,
                                null
                        )
                )
        );

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

        assertEquals(
                0L,
                countAudits(
                        fixture.tenantId(),
                        "CONVERSATION_TURN_FAILED",
                        "MESSAGE",
                        prepared.assistantMessageId()
                )
        );

        assertEquals(
                1L,
                countAudits(
                        fixture.tenantId(),
                        "CONVERSATION_TURN_PREPARED",
                        "CONVERSATION",
                        fixture.conversationId()
                )
        );
    }

    @Test
    void shouldRejectCompletionAndFailureForForgedSequence() {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        PreparedConversationTurn prepared =
                prepareService.prepare(
                        Long.toString(
                                fixture.conversationId()
                        ),
                        "  Question  "
                );

        long forgedUserSequence =
                prepared.userSequenceNo() + 100L;

        PreparedConversationTurn forged =
                new PreparedConversationTurn(
                        prepared.tenantId(),
                        prepared.userId(),
                        prepared.conversationId(),
                        prepared.agent(),
                        prepared.userMessageId(),
                        forgedUserSequence,
                        prepared.assistantMessageId(),
                        forgedUserSequence + 1L,
                        prepared.conversationVersion(),
                        prepared.preparedAt(),
                        prepared.modelRequest()
                );

        assertThrows(
                IllegalStateException.class,
                () -> completeService.complete(
                        forged,
                        "  Answer  ",
                        ChatModelFinishReason.STOP,
                        new ChatTokenUsage(1, 2)
                )
        );

        assertThrows(
                IllegalStateException.class,
                () -> failService.fail(
                        forged,
                        new ChatModelException(
                                ChatModelErrorCategory.TIMEOUT,
                                "boom",
                                null,
                                null
                        )
                )
        );

        MessageDatabaseRow assistantRow =
                readMessage(
                        fixture.tenantId(),
                        fixture.conversationId(),
                        3L
                );

        assertEquals("CREATING", assistantRow.status());
        assertEquals("", assistantRow.content());
        assertNull(assistantRow.metadataJson());

        assertEquals(
                0L,
                countAudits(
                        fixture.tenantId(),
                        "CONVERSATION_TURN_COMPLETED",
                        "MESSAGE",
                        prepared.assistantMessageId()
                )
        );

        assertEquals(
                0L,
                countAudits(
                        fixture.tenantId(),
                        "CONVERSATION_TURN_FAILED",
                        "MESSAGE",
                        prepared.assistantMessageId()
                )
        );
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

    private List<AuditDatabaseRow> readTurnAudits(
            long tenantId,
            String action,
            String resourceType,
            long resourceId
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
                  AND action = ?
                  AND resource_type = ?
                  AND resource_id = ?
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
                tenantId,
                action,
                resourceType,
                resourceId
        );
    }

    private List<AuditDatabaseRow> readAllAudits(long tenantId) {
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

    private long countMessages(
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

    private long countAudits(
            long tenantId,
            String action,
            String resourceType,
            long resourceId
    ) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_logs
                WHERE tenant_id = ?
                  AND action = ?
                  AND resource_type = ?
                  AND resource_id = ?
                """,
                Long.class,
                tenantId,
                action,
                resourceType,
                resourceId
        );

        return count == null ? 0L : count;
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
