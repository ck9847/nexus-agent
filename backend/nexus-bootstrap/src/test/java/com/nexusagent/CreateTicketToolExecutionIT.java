package com.nexusagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.agent.api.ActiveAgentRuntime;
import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import com.nexusagent.conversation.api.ConversationNotFoundException;
import com.nexusagent.conversation.internal.CompleteConversationToolCallService;
import com.nexusagent.conversation.internal.PreparedConversationTurn;
import com.nexusagent.model.api.ChatModelMessage;
import com.nexusagent.model.api.ChatModelOptions;
import com.nexusagent.model.api.ChatModelRequest;
import com.nexusagent.model.api.ChatModelToolCall;
import com.nexusagent.model.api.ChatTokenUsage;
import com.nexusagent.tool.api.RegisterToolExecutionCommand;
import com.nexusagent.tool.api.RegisterToolExecutionResult;
import com.nexusagent.tool.api.RegisterToolExecutionService;
import com.nexusagent.tool.api.ToolExecutionApprovalRequiredException;
import com.nexusagent.tool.api.ToolExecutionInProgressException;
import com.nexusagent.tool.internal.AgentToolExecutionContext;
import com.nexusagent.tool.internal.DefaultExecuteCreateTicketToolService;
import com.nexusagent.tool.internal.ExecuteCreateTicketToolResult;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
class CreateTicketToolExecutionIT {

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
    private RegisterToolExecutionService registerService;

    @Autowired
    private DefaultExecuteCreateTicketToolService executeService;

    @Autowired
    private CompleteConversationToolCallService
            toolCallCompleteService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean(reset = MockReset.AFTER)
    private CurrentActorProvider currentActorProvider;

    @MockitoSpyBean(reset = MockReset.AFTER)
    private AuditLogWriter auditLogWriter;

    @Test
    void shouldExecuteCreateTicketWithExactRowsAndSafeAudits()
            throws Exception {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        String secret = "unique-secret-value";

        JsonNode input = objectMapper.readTree(
                """
                {
                    "title": "  Server down  ",
                    "description": "  %s  ",
                    "priority": "HIGH"
                }
                """.formatted(secret)
        );

        RegisteredExecution execution =
                registerCreateTicket(
                        fixture,
                        "call-success",
                        input,
                        false
                );

        completeFirstRound(
                fixture,
                execution,
                input
        );

        ExecuteCreateTicketToolResult result =
                executeService.execute(
                        context(fixture, execution)
                );

        assertFalse(result.replayed());

        long ticketId = Long.parseLong(result.ticketId());
        long toolMessageId = result.resultMessageId();
        long finalAssistantMessageId =
                result.assistantMessageId();

        assertTrue(ticketId > 0);
        assertTrue(toolMessageId > 0);
        assertTrue(finalAssistantMessageId > 0);
        assertNotEquals(toolMessageId, finalAssistantMessageId);
        assertEquals(3L, result.resultMessageSequenceNo());
        assertEquals(4L, result.assistantSequenceNo());
        assertEquals(1, result.conversationVersion());
        assertNotNull(result.assistantPreparedAt());
        assertNotNull(result.ticketNo());
        assertFalse(result.ticketNo().isBlank());

        List<TicketDatabaseRow> tickets =
                readTickets(fixture.tenantId());

        assertEquals(1, tickets.size());

        TicketDatabaseRow ticket = tickets.get(0);

        assertEquals(ticketId, ticket.id());
        assertEquals(fixture.tenantId(), ticket.tenantId());
        assertEquals(result.ticketNo(), ticket.ticketNo());
        assertEquals("Server down", ticket.title());
        assertEquals(secret, ticket.description());
        assertEquals("HIGH", ticket.priority());
        assertEquals("OPEN", ticket.status());
        assertEquals("AGENT", ticket.source());
        assertEquals(
                fixture.userId(),
                ticket.requesterUserId()
        );
        assertNull(ticket.assigneeUserId());
        assertEquals(
                fixture.agentId(),
                ticket.createdByAgentId()
        );
        assertEquals(0, ticket.version());

        List<MessageDatabaseRow> messages =
                readMessages(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        assertEquals(4, messages.size());

        assertEquals(
                List.of("USER", "ASSISTANT", "TOOL",
                        "ASSISTANT"),
                messages.stream()
                        .map(MessageDatabaseRow::role)
                        .toList()
        );

        MessageDatabaseRow requestMessage =
                messages.get(1);

        assertEquals(
                fixture.assistantMessageId(),
                requestMessage.id()
        );
        assertEquals(2L, requestMessage.sequenceNo());
        assertEquals("COMPLETED", requestMessage.status());
        assertEquals("JSON", requestMessage.contentType());
        assertEquals(
                "gpt-5-mini",
                requestMessage.modelName()
        );

        JsonNode requestContent = parseJson(
                requestMessage.content()
        );

        assertEquals(
                execution.toolCallId(),
                requestContent.get("id").asText()
        );
        assertEquals(
                "create_ticket",
                requestContent.get("name").asText()
        );
        assertEquals(
                "  Server down  ",
                requestContent.get("arguments")
                        .get("title")
                        .asText()
        );

        MessageDatabaseRow toolMessage = messages.get(2);

        assertEquals(toolMessageId, toolMessage.id());
        assertEquals(3L, toolMessage.sequenceNo());
        assertEquals("TOOL", toolMessage.role());
        assertEquals("JSON", toolMessage.contentType());
        assertEquals("COMPLETED", toolMessage.status());
        assertNull(toolMessage.modelName());
        assertNull(toolMessage.promptTokens());
        assertNull(toolMessage.completionTokens());

        JsonNode content = parseJson(toolMessage.content());

        assertEquals(
                result.ticketId(),
                content.get("ticketId").asText()
        );
        assertEquals(
                result.ticketNo(),
                content.get("ticketNo").asText()
        );
        assertEquals("OPEN", content.get("status").asText());
        assertEquals(3, content.size());

        JsonNode toolMetadata = parseJson(
                toolMessage.metadataJson()
        );

        assertEquals(3, toolMetadata.size());
        assertEquals(
                Long.toString(execution.toolExecutionId()),
                toolMetadata.get("toolExecutionId").asText()
        );
        assertEquals(
                execution.toolCallId(),
                toolMetadata.get("toolCallId").asText()
        );
        assertEquals(
                "create_ticket",
                toolMetadata.get("toolName").asText()
        );

        MessageDatabaseRow continuation =
                messages.get(3);

        assertEquals(
                finalAssistantMessageId,
                continuation.id()
        );
        assertEquals(4L, continuation.sequenceNo());
        assertEquals("ASSISTANT", continuation.role());
        assertEquals("", continuation.content());
        assertEquals("TEXT", continuation.contentType());
        assertEquals("CREATING", continuation.status());
        assertEquals(
                "gpt-5-mini",
                continuation.modelName()
        );
        assertNull(continuation.promptTokens());
        assertNull(continuation.completionTokens());

        JsonNode continuationMetadata = parseJson(
                continuation.metadataJson()
        );

        assertEquals(5, continuationMetadata.size());
        assertEquals(
                "TOOL_CONTINUATION",
                continuationMetadata
                        .get("messageKind")
                        .asText()
        );
        assertEquals(
                Long.toString(execution.toolExecutionId()),
                continuationMetadata
                        .get("toolExecutionId")
                        .asText()
        );
        assertEquals(
                execution.toolCallId(),
                continuationMetadata
                        .get("toolCallId")
                        .asText()
        );
        assertEquals(
                Long.toString(toolMessageId),
                continuationMetadata
                        .get("resultMessageId")
                        .asText()
        );
        assertEquals(
                1,
                continuationMetadata
                        .get("conversationVersion")
                        .asInt()
        );

        ConversationSnapshot conversation =
                readConversationSnapshot(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        assertEquals("ACTIVE", conversation.status());
        assertEquals(5L, conversation.nextMessageSequence());
        assertEquals(1, conversation.version());

        ToolExecutionDatabaseRow executionRow =
                readExecution(
                        fixture.tenantId(),
                        execution.toolExecutionId()
                );

        assertEquals("SUCCEEDED", executionRow.status());
        assertEquals(
                toolMessageId,
                executionRow.resultMessageId()
        );
        assertEquals("TICKET", executionRow.resultEntityType());
        assertEquals(
                ticketId,
                executionRow.resultEntityId()
        );
        assertNotNull(executionRow.startedAt());
        assertNotNull(executionRow.completedAt());
        assertTrue(executionRow.durationMs() >= 0);
        assertNull(executionRow.errorCode());
        assertNull(executionRow.errorMessage());

        for (String action : List.of(
                "TOOL_EXECUTION_REGISTERED",
                "CONVERSATION_TOOL_CALL_COMPLETED",
                "TOOL_EXECUTION_STARTED",
                "TICKET_CREATED",
                "TOOL_MESSAGE_WRITTEN",
                "CONVERSATION_TOOL_CONTINUATION_PREPARED",
                "TOOL_EXECUTION_SUCCEEDED"
        )) {
            assertEquals(
                    1L,
                    countAudits(
                            fixture.tenantId(),
                            action
                    ),
                    "Expected exactly one audit: " + action
            );
        }

        List<AuditDatabaseRow> allAudits =
                readAudits(fixture.tenantId());

        String auditText = allAudits.stream()
                .map(row -> orEmpty(row.beforeJson())
                        + orEmpty(row.afterJson())
                        + orEmpty(row.errorCode())
                        + orEmpty(row.errorMessage()))
                .reduce("", String::concat);

        assertFalse(auditText.contains("Server down"));
        assertFalse(auditText.contains(secret));
        assertFalse(auditText.contains("description"));
    }

    @Test
    void shouldReplaySucceededExecutionAfterConversationArchived()
            throws Exception {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        JsonNode input = validInput();

        RegisteredExecution execution =
                registerCreateTicket(
                        fixture,
                        "call-replay",
                        input,
                        false
                );

        completeFirstRound(
                fixture,
                execution,
                input
        );

        ExecuteCreateTicketToolResult first =
                executeService.execute(
                        context(fixture, execution)
                );

        assertFalse(first.replayed());

        long ticketsBefore = countTickets(fixture.tenantId());
        long messagesBefore = countMessages(
                fixture.tenantId(),
                fixture.conversationId()
        );
        long auditsBefore = countAudits(fixture.tenantId());

        ConversationSnapshot conversationBefore =
                readConversationSnapshot(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        jdbcTemplate.update(
                """
                UPDATE conversations
                SET status = 'ARCHIVED'
                WHERE tenant_id = ?
                  AND id = ?
                """,
                fixture.tenantId(),
                fixture.conversationId()
        );

        ExecuteCreateTicketToolResult replay =
                executeService.execute(
                        context(fixture, execution)
                );

        assertTrue(replay.replayed());
        assertEquals(first.ticketId(), replay.ticketId());
        assertEquals(first.ticketNo(), replay.ticketNo());
        assertEquals(
                first.resultMessageId(),
                replay.resultMessageId()
        );
        assertEquals(
                first.toolExecutionId(),
                replay.toolExecutionId()
        );
        assertEquals(
                first.resultMessageSequenceNo(),
                replay.resultMessageSequenceNo()
        );
        assertEquals(
                first.assistantMessageId(),
                replay.assistantMessageId()
        );
        assertEquals(
                first.assistantSequenceNo(),
                replay.assistantSequenceNo()
        );
        assertEquals(
                first.conversationVersion(),
                replay.conversationVersion()
        );
        assertEquals(
                first.assistantPreparedAt(),
                replay.assistantPreparedAt()
        );

        assertEquals(
                ticketsBefore,
                countTickets(fixture.tenantId())
        );
        assertEquals(
                messagesBefore,
                countMessages(
                        fixture.tenantId(),
                        fixture.conversationId()
                )
        );
        assertEquals(
                auditsBefore,
                countAudits(fixture.tenantId())
        );

        assertEquals(
                4L,
                messagesBefore
        );

        ConversationSnapshot conversationAfter =
                readConversationSnapshot(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        assertEquals("ARCHIVED", conversationAfter.status());
        assertEquals(
                conversationBefore.nextMessageSequence(),
                conversationAfter.nextMessageSequence()
        );
        assertEquals(
                conversationBefore.version(),
                conversationAfter.version()
        );

        assertEquals(
                1L,
                countTickets(fixture.tenantId())
        );
    }

    @Test
    void shouldFailInvalidInputWithoutStartingOrWritingBusinessRows()
            throws Exception {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        String secret = "secret-title-description";

        JsonNode input = objectMapper.readTree(
                """
                {
                    "title": "%s-title",
                    "description": "%s-description",
                    "priority": "HIGH",
                    "tenantId": 999
                }
                """.formatted(secret, secret)
        );

        RegisteredExecution execution =
                registerCreateTicket(
                        fixture,
                        "call-invalid",
                        input,
                        false
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> executeService.execute(
                        context(fixture, execution)
                )
        );

        ToolExecutionDatabaseRow executionRow =
                readExecution(
                        fixture.tenantId(),
                        execution.toolExecutionId()
                );

        assertEquals("FAILED", executionRow.status());
        assertNull(executionRow.startedAt());
        assertNull(executionRow.resultMessageId());
        assertNull(executionRow.resultEntityId());
        assertNull(executionRow.outputJson());
        assertEquals(
                "INVALID_TOOL_INPUT",
                executionRow.errorCode()
        );
        assertEquals(
                "Create ticket tool input is invalid",
                executionRow.errorMessage()
        );

        assertEquals(0L, countTickets(fixture.tenantId()));
        assertEquals(
                2L,
                countMessages(
                        fixture.tenantId(),
                        fixture.conversationId()
                )
        );

        ConversationSnapshot conversation =
                readConversationSnapshot(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        assertEquals("ACTIVE", conversation.status());
        assertEquals(3L, conversation.nextMessageSequence());
        assertEquals(0, conversation.version());

        assertEquals(
                0L,
                countAudits(
                        fixture.tenantId(),
                        "TOOL_EXECUTION_STARTED"
                )
        );
        assertEquals(
                1L,
                countAudits(
                        fixture.tenantId(),
                        "TOOL_EXECUTION_FAILED"
                )
        );

        List<AuditDatabaseRow> allAudits =
                readAudits(fixture.tenantId());

        String auditText = allAudits.stream()
                .map(row -> orEmpty(row.beforeJson())
                        + orEmpty(row.afterJson())
                        + orEmpty(row.errorCode())
                        + orEmpty(row.errorMessage()))
                .reduce("", String::concat);

        assertFalse(auditText.contains(secret));
    }

    @Test
    void shouldRollbackTicketMessageConversationAndSuccessWhenFinalAuditFails()
            throws Exception {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        RegisteredExecution execution =
                registerCreateTicket(
                        fixture,
                        "call-audit-fail",
                        validInput(),
                        false
                );

        completeFirstRound(
                fixture,
                execution,
                validInput()
        );

        AuditLogWriter target =
                AopTestUtils.getUltimateTargetObject(
                        auditLogWriter
                );

        doThrow(new IllegalStateException(
                "Simulated final success audit failure"
        ))
                .when(target)
                .write(argThat(command ->
                        command != null
                                && command.tenantId()
                                == fixture.tenantId()
                                && "TOOL_EXECUTION_SUCCEEDED"
                                .equals(command.action())
                ));

        assertThrows(
                IllegalStateException.class,
                () -> executeService.execute(
                        context(fixture, execution)
                )
        );

        ToolExecutionDatabaseRow executionRow =
                readExecution(
                        fixture.tenantId(),
                        execution.toolExecutionId()
                );

        assertEquals("FAILED", executionRow.status());

        assertEquals(0L, countTickets(fixture.tenantId()));
        assertEquals(
                2L,
                countMessages(
                        fixture.tenantId(),
                        fixture.conversationId()
                )
        );

        ConversationSnapshot conversation =
                readConversationSnapshot(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        assertEquals("ACTIVE", conversation.status());
        assertEquals(3L, conversation.nextMessageSequence());
        assertEquals(0, conversation.version());

        assertEquals(
                0L,
                countAudits(
                        fixture.tenantId(),
                        "TICKET_CREATED"
                )
        );
        assertEquals(
                0L,
                countAudits(
                        fixture.tenantId(),
                        "TOOL_MESSAGE_WRITTEN"
                )
        );
        assertEquals(
                0L,
                countAudits(
                        fixture.tenantId(),
                        "TOOL_EXECUTION_SUCCEEDED"
                )
        );
        assertEquals(
                0L,
                countAudits(
                        fixture.tenantId(),
                        "CONVERSATION_TOOL_CONTINUATION_PREPARED"
                )
        );
        assertEquals(
                1L,
                countAudits(
                        fixture.tenantId(),
                        "TOOL_EXECUTION_STARTED"
                )
        );
        assertEquals(
                1L,
                countAudits(
                        fixture.tenantId(),
                        "CONVERSATION_TOOL_CALL_COMPLETED"
                )
        );
        assertEquals(
                1L,
                countAudits(
                        fixture.tenantId(),
                        "TOOL_EXECUTION_FAILED"
                )
        );
    }

    @Test
    void shouldAllowOnlyOneConcurrentTicketCreation()
            throws Exception {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        RegisteredExecution execution =
                registerCreateTicket(
                        fixture,
                        "call-race",
                        validInput(),
                        false
                );

        completeFirstRound(
                fixture,
                execution,
                validInput()
        );

        AgentToolExecutionContext context =
                context(fixture, execution);

        ExecutorService executor =
                Executors.newFixedThreadPool(8);

        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Attempt>> futures = new ArrayList<>();

        for (int i = 0; i < 8; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();

                if (!start.await(30, TimeUnit.SECONDS)) {
                    return new Attempt(
                            null,
                            new IllegalStateException(
                                    "start latch timed out"
                            )
                    );
                }

                try {
                    return new Attempt(
                            executeService.execute(context),
                            null
                    );
                } catch (Throwable error) {
                    return new Attempt(null, error);
                }
            }));
        }

        assertTrue(ready.await(30, TimeUnit.SECONDS));
        start.countDown();

        try {
            List<Attempt> attempts = new ArrayList<>();

            for (Future<Attempt> future : futures) {
                attempts.add(
                        future.get(30, TimeUnit.SECONDS)
                );
            }

            long freshCount = attempts.stream()
                    .filter(attempt ->
                            attempt.result() != null
                                    && !attempt.result()
                                    .replayed()
                    )
                    .count();

            assertEquals(1L, freshCount);

            for (Attempt attempt : attempts) {
                if (attempt.error() == null) {
                    assertNotNull(attempt.result());
                } else {
                    assertInstanceOf(
                            ToolExecutionInProgressException.class,
                            attempt.error()
                    );
                }
            }

            assertEquals(
                    1L,
                    countTickets(fixture.tenantId())
            );
            assertEquals(
                    4L,
                    countMessages(
                            fixture.tenantId(),
                            fixture.conversationId()
                    )
            );

            ToolExecutionDatabaseRow executionRow =
                    readExecution(
                            fixture.tenantId(),
                            execution.toolExecutionId()
                    );

            assertEquals("SUCCEEDED", executionRow.status());

            ConversationSnapshot conversation =
                    readConversationSnapshot(
                            fixture.tenantId(),
                            fixture.conversationId()
                    );

            assertEquals("ACTIVE", conversation.status());
            assertEquals(
                    5L,
                    conversation.nextMessageSequence()
            );
            assertEquals(1, conversation.version());

            for (String action : List.of(
                    "CONVERSATION_TOOL_CALL_COMPLETED",
                    "TOOL_EXECUTION_STARTED",
                    "TICKET_CREATED",
                    "TOOL_MESSAGE_WRITTEN",
                    "CONVERSATION_TOOL_CONTINUATION_PREPARED",
                    "TOOL_EXECUTION_SUCCEEDED"
            )) {
                assertEquals(
                        1L,
                        countAudits(
                                fixture.tenantId(),
                                action
                        ),
                        "Expected exactly one audit: " + action
                );
            }

            assertEquals(
                    0L,
                    countAudits(
                            fixture.tenantId(),
                            "TOOL_EXECUTION_FAILED"
                    )
            );
        } finally {
            start.countDown();

            for (Future<?> future : futures) {
                future.cancel(true);
            }

            executor.shutdownNow();

            assertTrue(
                    executor.awaitTermination(
                            30,
                            TimeUnit.SECONDS
                    )
            );
        }
    }

    @Test
    void shouldHideForeignOwnerAndTenantWithoutMutation()
            throws Exception {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        RegisteredExecution execution =
                registerCreateTicket(
                        fixture,
                        "call-isolation",
                        validInput(),
                        false
                );

        long otherUserId = insertAdditionalUser(fixture);

        AgentToolExecutionContext foreignOwner =
                new AgentToolExecutionContext(
                        fixture.tenantId(),
                        otherUserId,
                        fixture.conversationId(),
                        fixture.agentId(),
                        fixture.assistantMessageId(),
                        execution.toolExecutionId(),
                        execution.toolCallId()
                );

        assertThrows(
                ConversationNotFoundException.class,
                () -> executeService.execute(foreignOwner)
        );

        assertUnchanged(fixture, execution);

        Fixture foreignTenant = insertFixture();

        AgentToolExecutionContext foreignTenantContext =
                new AgentToolExecutionContext(
                        foreignTenant.tenantId(),
                        foreignTenant.userId(),
                        fixture.conversationId(),
                        foreignTenant.agentId(),
                        fixture.assistantMessageId(),
                        execution.toolExecutionId(),
                        execution.toolCallId()
                );

        assertThrows(
                ConversationNotFoundException.class,
                () -> executeService.execute(
                        foreignTenantContext
                )
        );

        assertUnchanged(fixture, execution);
    }

    @Test
    void shouldRejectApprovalRequiredExecutionWithoutBusinessWrites()
            throws Exception {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        RegisteredExecution execution =
                registerCreateTicket(
                        fixture,
                        "call-approval",
                        validInput(),
                        true
                );

        assertThrows(
                ToolExecutionApprovalRequiredException.class,
                () -> executeService.execute(
                        context(fixture, execution)
                )
        );

        ToolExecutionDatabaseRow executionRow =
                readExecution(
                        fixture.tenantId(),
                        execution.toolExecutionId()
                );

        assertEquals("WAITING_APPROVAL", executionRow.status());
        assertNull(executionRow.startedAt());

        assertEquals(0L, countTickets(fixture.tenantId()));
        assertEquals(
                2L,
                countMessages(
                        fixture.tenantId(),
                        fixture.conversationId()
                )
        );

        ConversationSnapshot conversation =
                readConversationSnapshot(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        assertEquals("ACTIVE", conversation.status());
        assertEquals(3L, conversation.nextMessageSequence());
        assertEquals(0, conversation.version());

        assertEquals(
                0L,
                countAudits(
                        fixture.tenantId(),
                        "TOOL_EXECUTION_STARTED"
                )
        );
        assertEquals(
                0L,
                countAudits(
                        fixture.tenantId(),
                        "TOOL_EXECUTION_FAILED"
                )
        );
        assertEquals(
                0L,
                countAudits(
                        fixture.tenantId(),
                        "TOOL_EXECUTION_SUCCEEDED"
                )
        );
    }

    @Test
    void shouldRollbackFailedStateWhenFailureAuditFails()
            throws Exception {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        JsonNode input = objectMapper.readTree(
                """
                {
                    "title": "Server down",
                    "description": "Cannot connect.",
                    "priority": "HIGH",
                    "tenantId": 999
                }
                """
        );

        RegisteredExecution execution =
                registerCreateTicket(
                        fixture,
                        "call-fail-audit",
                        input,
                        false
                );

        AuditLogWriter target =
                AopTestUtils.getUltimateTargetObject(
                        auditLogWriter
                );

        doThrow(new IllegalStateException(
                "Simulated failure audit failure"
        ))
                .when(target)
                .write(argThat(command ->
                        command != null
                                && command.tenantId()
                                == fixture.tenantId()
                                && "TOOL_EXECUTION_FAILED"
                                .equals(command.action())
                ));

        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () -> executeService.execute(
                                context(fixture, execution)
                        )
                );

        assertEquals(
                "Simulated failure audit failure",
                thrown.getMessage()
        );
        assertEquals(1, thrown.getSuppressed().length);
        assertInstanceOf(
                IllegalArgumentException.class,
                thrown.getSuppressed()[0]
        );

        ToolExecutionDatabaseRow executionRow =
                readExecution(
                        fixture.tenantId(),
                        execution.toolExecutionId()
                );

        assertEquals("PENDING", executionRow.status());
        assertNull(executionRow.errorCode());
        assertNull(executionRow.errorMessage());
        assertNull(executionRow.completedAt());
        assertNull(executionRow.startedAt());

        assertEquals(
                0L,
                countAudits(
                        fixture.tenantId(),
                        "TOOL_EXECUTION_FAILED"
                )
        );

        assertEquals(0L, countTickets(fixture.tenantId()));
        assertEquals(
                2L,
                countMessages(
                        fixture.tenantId(),
                        fixture.conversationId()
                )
        );

        ConversationSnapshot conversation =
                readConversationSnapshot(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        assertEquals("ACTIVE", conversation.status());
        assertEquals(3L, conversation.nextMessageSequence());
        assertEquals(0, conversation.version());
    }

    @Test
    void shouldRejectWhenRequestMessageIsNotCompletedJson()
            throws Exception {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        RegisteredExecution execution =
                registerCreateTicket(
                        fixture,
                        "call-not-json",
                        validInput(),
                        false
                );

        jdbcTemplate.update(
                """
                UPDATE messages
                SET status = 'COMPLETED'
                WHERE id = ?
                """,
                fixture.assistantMessageId()
        );

        assertThrows(
                IllegalStateException.class,
                () -> executeService.execute(
                        context(fixture, execution)
                )
        );

        ToolExecutionDatabaseRow executionRow =
                readExecution(
                        fixture.tenantId(),
                        execution.toolExecutionId()
                );

        assertEquals("FAILED", executionRow.status());

        assertEquals(0L, countTickets(fixture.tenantId()));
        assertEquals(
                2L,
                countMessages(
                        fixture.tenantId(),
                        fixture.conversationId()
                )
        );

        ConversationSnapshot conversation =
                readConversationSnapshot(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        assertEquals("ACTIVE", conversation.status());
        assertEquals(3L, conversation.nextMessageSequence());
        assertEquals(0, conversation.version());

        assertEquals(
                1L,
                countAudits(
                        fixture.tenantId(),
                        "TOOL_EXECUTION_STARTED"
                )
        );
        assertEquals(
                1L,
                countAudits(
                        fixture.tenantId(),
                        "TOOL_EXECUTION_FAILED"
                )
        );
        assertEquals(
                0L,
                countAudits(
                        fixture.tenantId(),
                        "TOOL_EXECUTION_SUCCEEDED"
                )
        );
    }

    @Test
    void shouldRejectWhenRequestMessageStillCreating()
            throws Exception {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        RegisteredExecution execution =
                registerCreateTicket(
                        fixture,
                        "call-still-creating",
                        validInput(),
                        false
                );

        assertThrows(
                IllegalStateException.class,
                () -> executeService.execute(
                        context(fixture, execution)
                )
        );

        ToolExecutionDatabaseRow executionRow =
                readExecution(
                        fixture.tenantId(),
                        execution.toolExecutionId()
                );

        assertEquals("FAILED", executionRow.status());

        assertEquals(0L, countTickets(fixture.tenantId()));
        assertEquals(
                2L,
                countMessages(
                        fixture.tenantId(),
                        fixture.conversationId()
                )
        );

        ConversationSnapshot conversation =
                readConversationSnapshot(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        assertEquals("ACTIVE", conversation.status());
        assertEquals(3L, conversation.nextMessageSequence());
        assertEquals(0, conversation.version());

        assertEquals(
                1L,
                countAudits(
                        fixture.tenantId(),
                        "TOOL_EXECUTION_STARTED"
                )
        );
        assertEquals(
                1L,
                countAudits(
                        fixture.tenantId(),
                        "TOOL_EXECUTION_FAILED"
                )
        );
    }

    @Test
    void shouldRejectWhenSequenceDoesNotFollowRequest()
            throws Exception {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        RegisteredExecution execution =
                registerCreateTicket(
                        fixture,
                        "call-bad-sequence",
                        validInput(),
                        false
                );

        completeFirstRound(
                fixture,
                execution,
                validInput()
        );

        jdbcTemplate.update(
                """
                UPDATE conversations
                SET next_message_sequence = 4
                WHERE tenant_id = ?
                  AND id = ?
                """,
                fixture.tenantId(),
                fixture.conversationId()
        );

        assertThrows(
                IllegalStateException.class,
                () -> executeService.execute(
                        context(fixture, execution)
                )
        );

        ToolExecutionDatabaseRow executionRow =
                readExecution(
                        fixture.tenantId(),
                        execution.toolExecutionId()
                );

        assertEquals("FAILED", executionRow.status());

        assertEquals(0L, countTickets(fixture.tenantId()));
        assertEquals(
                2L,
                countMessages(
                        fixture.tenantId(),
                        fixture.conversationId()
                )
        );

        ConversationSnapshot conversation =
                readConversationSnapshot(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        assertEquals("ACTIVE", conversation.status());
        assertEquals(4L, conversation.nextMessageSequence());
        assertEquals(0, conversation.version());

        assertEquals(
                1L,
                countAudits(
                        fixture.tenantId(),
                        "TOOL_EXECUTION_STARTED"
                )
        );
        assertEquals(
                1L,
                countAudits(
                        fixture.tenantId(),
                        "TOOL_EXECUTION_FAILED"
                )
        );
        assertEquals(
                0L,
                countAudits(
                        fixture.tenantId(),
                        "TOOL_EXECUTION_SUCCEEDED"
                )
        );
    }

    private void completeFirstRound(
            Fixture fixture,
            RegisteredExecution execution,
            JsonNode arguments
    ) {
        Instant preparedAt = Instant.now()
                .minusSeconds(5)
                .truncatedTo(ChronoUnit.MILLIS);

        ActiveAgentRuntime agent = new ActiveAgentRuntime(
                fixture.agentId(),
                fixture.tenantId(),
                "agent-" + fixture.agentId(),
                "system-prompt-sensitive-value",
                AgentModelProvider.OPENAI,
                "gpt-5-mini",
                null
        );

        ChatModelRequest modelRequest = new ChatModelRequest(
                "gpt-5-mini",
                "system-prompt-sensitive-value",
                ChatModelOptions.defaults(),
                List.of(
                        ChatModelMessage.user("Initial message")
                ),
                List.of()
        );

        PreparedConversationTurn prepared =
                new PreparedConversationTurn(
                        fixture.tenantId(),
                        fixture.userId(),
                        fixture.conversationId(),
                        agent,
                        fixture.userMessageId(),
                        1L,
                        fixture.assistantMessageId(),
                        2L,
                        1,
                        preparedAt,
                        modelRequest
                );

        toolCallCompleteService.complete(
                prepared,
                new ChatModelToolCall(
                        execution.toolCallId(),
                        "create_ticket",
                        arguments
                ),
                new ChatTokenUsage(3, 2),
                execution.toolExecutionId()
        );
    }

    private RegisteredExecution registerCreateTicket(
            Fixture fixture,
            String toolCallId,
            JsonNode input,
            boolean approvalRequired
    ) {
        RegisterToolExecutionResult result =
                registerService.register(
                        new RegisterToolExecutionCommand(
                                fixture.conversationId(),
                                fixture.agentId(),
                                fixture.assistantMessageId(),
                                toolCallId,
                                "create_ticket",
                                input,
                                approvalRequired,
                                "trace-" + toolCallId
                        )
                );

        return new RegisteredExecution(
                result.toolExecutionId(),
                toolCallId
        );
    }

    private AgentToolExecutionContext context(
            Fixture fixture,
            RegisteredExecution execution
    ) {
        return new AgentToolExecutionContext(
                fixture.tenantId(),
                fixture.userId(),
                fixture.conversationId(),
                fixture.agentId(),
                fixture.assistantMessageId(),
                execution.toolExecutionId(),
                execution.toolCallId()
        );
    }

    private JsonNode validInput() {
        try {
            return objectMapper.readTree(
                    """
                    {
                        "title": "Server down",
                        "description": "Cannot connect.",
                        "priority": "HIGH"
                    }
                    """
            );
        } catch (JsonProcessingException exception) {
            throw new AssertionError(exception);
        }
    }

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new AssertionError(exception);
        }
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

    private void assertUnchanged(
            Fixture fixture,
            RegisteredExecution execution
    ) {
        ToolExecutionDatabaseRow executionRow =
                readExecution(
                        fixture.tenantId(),
                        execution.toolExecutionId()
                );

        assertEquals("PENDING", executionRow.status());
        assertNull(executionRow.startedAt());

        ConversationSnapshot conversation =
                readConversationSnapshot(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        assertEquals("ACTIVE", conversation.status());
        assertEquals(3L, conversation.nextMessageSequence());
        assertEquals(0, conversation.version());

        assertEquals(
                2L,
                countMessages(
                        fixture.tenantId(),
                        fixture.conversationId()
                )
        );
        assertEquals(0L, countTickets(fixture.tenantId()));

        assertEquals(
                1L,
                countAudits(
                        fixture.tenantId(),
                        "TOOL_EXECUTION_REGISTERED"
                )
        );
        assertEquals(
                0L,
                countAudits(
                        fixture.tenantId(),
                        "TOOL_EXECUTION_STARTED"
                )
        );
        assertEquals(
                0L,
                countAudits(
                        fixture.tenantId(),
                        "TOOL_EXECUTION_FAILED"
                )
        );
        assertEquals(
                0L,
                countAudits(
                        fixture.tenantId(),
                        "TOOL_EXECUTION_SUCCEEDED"
                )
        );
    }

    private Fixture insertFixture() {
        long base = FIXTURE_IDS.getAndAdd(100L);

        long tenantId = base + 1;
        long userId = base + 2;
        long agentId = base + 3;
        long conversationId = base + 4;
        long userMessageId = base + 5;
        long assistantMessageId = base + 6;
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
                "system-prompt-sensitive-value",
                "gpt-5-mini",
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
                        'ACTIVE', CURRENT_TIMESTAMP(3), 3, 0
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

        jdbcTemplate.update(
                """
                INSERT INTO messages
                    (
                        id, tenant_id, conversation_id,
                        sequence_no, `role`, content,
                        content_type, status
                    )
                VALUES
                    (?, ?, ?, 2, 'ASSISTANT', '',
                     'TEXT', 'CREATING')
                """,
                assistantMessageId,
                tenantId,
                conversationId
        );

        return new Fixture(
                tenantId,
                userId,
                agentId,
                conversationId,
                userMessageId,
                assistantMessageId,
                username
        );
    }

    private long insertAdditionalUser(Fixture fixture) {
        long userId = FIXTURE_IDS.incrementAndGet();

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
                fixture.tenantId(),
                "other" + userId,
                "Other " + userId
        );

        return userId;
    }

    private List<TicketDatabaseRow> readTickets(long tenantId) {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
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
                WHERE tenant_id = ?
                ORDER BY id
                """,
                (resultSet, rowNumber) ->
                        new TicketDatabaseRow(
                                resultSet.getLong("id"),
                                resultSet.getLong("tenant_id"),
                                resultSet.getString("ticket_no"),
                                resultSet.getString("title"),
                                resultSet.getString("description"),
                                resultSet.getString("priority"),
                                resultSet.getString("status"),
                                resultSet.getString("source"),
                                resultSet.getLong(
                                        "requester_user_id"
                                ),
                                resultSet.getObject(
                                        "assignee_user_id",
                                        Long.class
                                ),
                                resultSet.getObject(
                                        "created_by_agent_id",
                                        Long.class
                                ),
                                resultSet.getInt("version")
                        ),
                tenantId
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
                                resultSet.getString("content_type"),
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
                                resultSet.getString("metadata_json")
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
                    status,
                    next_message_sequence,
                    version
                FROM conversations
                WHERE tenant_id = ?
                  AND id = ?
                """,
                (resultSet, rowNumber) ->
                        new ConversationSnapshot(
                                resultSet.getString("status"),
                                resultSet.getLong(
                                        "next_message_sequence"
                                ),
                                resultSet.getInt("version")
                        ),
                tenantId,
                conversationId
        );
    }

    private ToolExecutionDatabaseRow readExecution(
            long tenantId,
            long executionId
    ) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    id,
                    tenant_id,
                    conversation_id,
                    agent_id,
                    request_message_id,
                    result_message_id,
                    tool_call_id,
                    tool_name,
                    idempotency_key,
                    CAST(input_json AS CHAR) AS input_json,
                    CAST(output_json AS CHAR) AS output_json,
                    status,
                    approval_required,
                    result_entity_type,
                    result_entity_id,
                    error_code,
                    error_message,
                    trace_id,
                    started_at,
                    completed_at,
                    duration_ms,
                    created_at,
                    updated_at
                FROM tool_executions
                WHERE tenant_id = ?
                  AND id = ?
                """,
                (resultSet, rowNumber) ->
                        new ToolExecutionDatabaseRow(
                                resultSet.getLong("id"),
                                resultSet.getLong("tenant_id"),
                                resultSet.getLong(
                                        "conversation_id"
                                ),
                                resultSet.getLong("agent_id"),
                                resultSet.getObject(
                                        "request_message_id",
                                        Long.class
                                ),
                                resultSet.getObject(
                                        "result_message_id",
                                        Long.class
                                ),
                                resultSet.getString(
                                        "tool_call_id"
                                ),
                                resultSet.getString("tool_name"),
                                resultSet.getString(
                                        "idempotency_key"
                                ),
                                resultSet.getString("input_json"),
                                resultSet.getString("output_json"),
                                resultSet.getString("status"),
                                resultSet.getBoolean(
                                        "approval_required"
                                ),
                                resultSet.getString(
                                        "result_entity_type"
                                ),
                                resultSet.getObject(
                                        "result_entity_id",
                                        Long.class
                                ),
                                resultSet.getString("error_code"),
                                resultSet.getString(
                                        "error_message"
                                ),
                                resultSet.getString("trace_id"),
                                toInstant(
                                        resultSet.getTimestamp(
                                                "started_at"
                                        )
                                ),
                                toInstant(
                                        resultSet.getTimestamp(
                                                "completed_at"
                                        )
                                ),
                                resultSet.getObject(
                                        "duration_ms",
                                        Long.class
                                ),
                                toInstant(
                                        resultSet.getTimestamp(
                                                "created_at"
                                        )
                                ),
                                toInstant(
                                        resultSet.getTimestamp(
                                                "updated_at"
                                        )
                                )
                        ),
                tenantId,
                executionId
        );
    }

    private List<AuditDatabaseRow> readAudits(long tenantId) {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    actor_type,
                    actor_id,
                    action,
                    resource_type,
                    resource_id,
                    tool_execution_id,
                    result,
                    trace_id,
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
                                resultSet.getObject(
                                        "tool_execution_id",
                                        Long.class
                                ),
                                resultSet.getString("result"),
                                resultSet.getString("trace_id"),
                                resultSet.getString("before_json"),
                                resultSet.getString("after_json"),
                                resultSet.getString("error_code"),
                                resultSet.getString("error_message")
                        ),
                tenantId
        );
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
            String action
    ) {
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

    private long countAudits(long tenantId) {
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

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null
                ? null
                : timestamp.toInstant();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private record Fixture(
            long tenantId,
            long userId,
            long agentId,
            long conversationId,
            long userMessageId,
            long assistantMessageId,
            String username
    ) {
    }

    private record RegisteredExecution(
            long toolExecutionId,
            String toolCallId
    ) {
    }

    private record Attempt(
            ExecuteCreateTicketToolResult result,
            Throwable error
    ) {
    }

    private record TicketDatabaseRow(
            long id,
            long tenantId,
            String ticketNo,
            String title,
            String description,
            String priority,
            String status,
            String source,
            long requesterUserId,
            Long assigneeUserId,
            Long createdByAgentId,
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

    private record ConversationSnapshot(
            String status,
            long nextMessageSequence,
            int version
    ) {
    }

    private record ToolExecutionDatabaseRow(
            long id,
            long tenantId,
            long conversationId,
            long agentId,
            Long requestMessageId,
            Long resultMessageId,
            String toolCallId,
            String toolName,
            String idempotencyKey,
            String inputJson,
            String outputJson,
            String status,
            boolean approvalRequired,
            String resultEntityType,
            Long resultEntityId,
            String errorCode,
            String errorMessage,
            String traceId,
            Instant startedAt,
            Instant completedAt,
            Long durationMs,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    private record AuditDatabaseRow(
            long id,
            String actorType,
            Long actorId,
            String action,
            String resourceType,
            Long resourceId,
            Long toolExecutionId,
            String result,
            String traceId,
            String beforeJson,
            String afterJson,
            String errorCode,
            String errorMessage
    ) {
    }
}
