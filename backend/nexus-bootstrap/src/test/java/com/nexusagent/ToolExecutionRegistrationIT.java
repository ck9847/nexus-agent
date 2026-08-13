package com.nexusagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import com.nexusagent.conversation.api.ConversationNotFoundException;
import com.nexusagent.tool.api.RegisterToolExecutionCommand;
import com.nexusagent.tool.api.RegisterToolExecutionResult;
import com.nexusagent.tool.api.RegisterToolExecutionService;
import com.nexusagent.tool.api.ToolExecutionIdempotencyConflictException;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
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
class ToolExecutionRegistrationIT {

    private static final AtomicLong FIXTURE_IDS =
            new AtomicLong(200_000L);

    private static final Set<String> EXPECTED_AUDIT_KEYS =
            Set.of(
                    "conversationId",
                    "agentId",
                    "requestedByUserId",
                    "requestMessageId",
                    "toolCallId",
                    "toolName",
                    "status",
                    "approvalRequired"
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
    private RegisterToolExecutionService registerService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean(reset = MockReset.AFTER)
    private CurrentActorProvider currentActorProvider;

    @MockitoSpyBean(reset = MockReset.AFTER)
    private AuditLogWriter auditLogWriter;

    @Test
    void shouldPersistInitialStatusesAndSafeAgentAudits()
            throws Exception {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        JsonNode input = objectMapper.readTree(
                "{\"op\":\"read\"}"
        );

        RegisterToolExecutionResult pending =
                registerService.register(command(
                        fixture,
                        "call-pending",
                        "search",
                        input,
                        false,
                        "trace-1"
                ));

        RegisterToolExecutionResult waiting =
                registerService.register(command(
                        fixture,
                        "call-waiting",
                        "search",
                        input,
                        true,
                        "trace-2"
                ));

        assertTrue(pending.newlyCreated());
        assertTrue(waiting.newlyCreated());

        ToolExecutionDatabaseRow pendingRow =
                readExecution(
                        fixture.tenantId(),
                        pending.toolExecutionId()
                );

        assertEquals(
                fixture.tenantId(),
                pendingRow.tenantId()
        );
        assertEquals(
                fixture.conversationId(),
                pendingRow.conversationId()
        );
        assertEquals(
                fixture.agentId(),
                pendingRow.agentId()
        );
        assertEquals(
                fixture.assistantMessageId(),
                pendingRow.requestMessageId()
        );
        assertNull(pendingRow.resultMessageId());
        assertEquals(
                "call-pending",
                pendingRow.toolCallId()
        );
        assertEquals("search", pendingRow.toolName());
        assertEquals(
                pending.idempotencyKey(),
                pendingRow.idempotencyKey()
        );
        assertEquals(
                input,
                parseJson(pendingRow.inputJson())
        );
        assertNull(pendingRow.outputJson());
        assertEquals("PENDING", pendingRow.status());
        assertFalse(pendingRow.approvalRequired());
        assertNull(pendingRow.resultEntityType());
        assertNull(pendingRow.resultEntityId());
        assertNull(pendingRow.errorCode());
        assertNull(pendingRow.errorMessage());
        assertEquals("trace-1", pendingRow.traceId());
        assertNull(pendingRow.startedAt());
        assertNull(pendingRow.completedAt());
        assertNull(pendingRow.durationMs());
        assertEquals(
                pendingRow.createdAt(),
                pendingRow.updatedAt()
        );

        ToolExecutionDatabaseRow waitingRow =
                readExecution(
                        fixture.tenantId(),
                        waiting.toolExecutionId()
                );

        assertEquals(
                "call-waiting",
                waitingRow.toolCallId()
        );
        assertEquals(
                "WAITING_APPROVAL",
                waitingRow.status()
        );
        assertTrue(waitingRow.approvalRequired());
        assertEquals("trace-2", waitingRow.traceId());
        assertEquals(
                input,
                parseJson(waitingRow.inputJson())
        );
        assertNull(waitingRow.outputJson());
        assertNull(waitingRow.resultEntityType());
        assertNull(waitingRow.resultEntityId());
        assertNull(waitingRow.errorCode());
        assertNull(waitingRow.errorMessage());
        assertNull(waitingRow.startedAt());
        assertNull(waitingRow.completedAt());
        assertNull(waitingRow.durationMs());
        assertEquals(
                waitingRow.createdAt(),
                waitingRow.updatedAt()
        );

        assertEquals(
                2L,
                countExecutions(fixture.tenantId())
        );

        List<AuditDatabaseRow> audits =
                readRegistrationAudits(fixture.tenantId());

        assertEquals(2, audits.size());

        List<Long> auditResourceIds = audits.stream()
                .map(AuditDatabaseRow::resourceId)
                .toList();

        assertTrue(
                auditResourceIds.contains(
                        pending.toolExecutionId()
                )
        );
        assertTrue(
                auditResourceIds.contains(
                        waiting.toolExecutionId()
                )
        );

        for (AuditDatabaseRow audit : audits) {
            assertEquals("AGENT", audit.actorType());
            assertEquals(
                    fixture.agentId(),
                    audit.actorId()
            );
            assertEquals(
                    "TOOL_EXECUTION_REGISTERED",
                    audit.action()
            );
            assertEquals(
                    "TOOL_EXECUTION",
                    audit.resourceType()
            );
            assertNotNull(audit.resourceId());
            assertEquals(
                    audit.resourceId(),
                    audit.toolExecutionId()
            );
            assertEquals("SUCCESS", audit.result());

            JsonNode after = parseJson(audit.afterJson());

            Set<String> keys = new LinkedHashSet<>();
            after.fieldNames().forEachRemaining(keys::add);

            assertEquals(EXPECTED_AUDIT_KEYS, keys);

            String auditText = audit.afterJson();
            assertFalse(auditText.contains("input"));
            assertFalse(auditText.contains("title"));
            assertFalse(auditText.contains("description"));
            assertFalse(auditText.contains("secret"));
        }
    }

    @Test
    void shouldReplayCanonicalEquivalentInputWithoutDuplicateOrTraceOverwrite()
            throws Exception {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        JsonNode firstInput = objectMapper.readTree(
                "{\"b\":2,\"a\":{\"z\":1,\"y\":2}}"
        );
        JsonNode secondInput = objectMapper.readTree(
                "{\"a\":{\"y\":2,\"z\":1},\"b\":2}"
        );

        RegisterToolExecutionCommand firstCommand =
                command(
                        fixture,
                        "call-replay",
                        "search",
                        firstInput,
                        false,
                        "trace-1"
                );

        RegisterToolExecutionCommand secondCommand =
                command(
                        fixture,
                        "call-replay",
                        "search",
                        secondInput,
                        false,
                        "trace-2"
                );

        RegisterToolExecutionResult first =
                registerService.register(firstCommand);

        RegisterToolExecutionResult second =
                registerService.register(secondCommand);

        assertTrue(first.newlyCreated());
        assertFalse(second.newlyCreated());

        assertEquals(
                first.toolExecutionId(),
                second.toolExecutionId()
        );
        assertEquals(
                first.idempotencyKey(),
                second.idempotencyKey()
        );
        assertEquals(
                first.status(),
                second.status()
        );
        assertEquals(
                first.createdAt(),
                second.createdAt()
        );

        assertEquals(
                1L,
                countExecutions(fixture.tenantId())
        );
        assertEquals(
                1L,
                countRegistrationAudits(fixture.tenantId())
        );

        ToolExecutionDatabaseRow row =
                readExecution(
                        fixture.tenantId(),
                        first.toolExecutionId()
                );

        assertEquals("trace-1", row.traceId());

        assertTrue(
                firstInput.equals(
                        parseJson(row.inputJson())
                )
        );
        assertTrue(secondInput.equals(firstInput));
    }

    @Test
    void shouldReplayHistoricallyAfterMessageCompletionAndConversationArchive() {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        RegisterToolExecutionCommand command =
                command(
                        fixture,
                        "call-history",
                        "search",
                        input(),
                        false,
                        "trace-1"
                );

        RegisterToolExecutionResult first =
                registerService.register(command);

        assertTrue(first.newlyCreated());

        jdbcTemplate.update(
                """
                UPDATE messages
                SET status = 'COMPLETED'
                WHERE id = ?
                """,
                fixture.assistantMessageId()
        );

        jdbcTemplate.update(
                """
                UPDATE conversations
                SET status = 'ARCHIVED'
                WHERE id = ?
                """,
                fixture.conversationId()
        );

        RegisterToolExecutionResult replay =
                registerService.register(command);

        assertFalse(replay.newlyCreated());
        assertEquals(
                first.toolExecutionId(),
                replay.toolExecutionId()
        );
        assertEquals(
                first.idempotencyKey(),
                replay.idempotencyKey()
        );
        assertEquals(first.status(), replay.status());
        assertEquals(
                first.createdAt(),
                replay.createdAt()
        );

        assertEquals(
                1L,
                countExecutions(fixture.tenantId())
        );
        assertEquals(
                1L,
                countRegistrationAudits(fixture.tenantId())
        );
    }

    @Test
    void shouldConvergeConcurrentEquivalentRegistrationsToOneRowAndAudit()
            throws Exception {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        RegisterToolExecutionCommand command =
                command(
                        fixture,
                        "call-race",
                        "search",
                        input(),
                        false,
                        "trace-1"
                );

        ExecutorService executor =
                Executors.newFixedThreadPool(8);

        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<RegisterToolExecutionResult>> futures =
                new ArrayList<>();

        for (int i = 0; i < 8; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();

                if (!start.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "start latch timed out"
                    );
                }

                return registerService.register(command);
            }));
        }

        assertTrue(ready.await(30, TimeUnit.SECONDS));
        start.countDown();

        try {
            List<RegisterToolExecutionResult> results =
                    new ArrayList<>();

            for (Future<RegisterToolExecutionResult> future
                    : futures) {
                results.add(
                        future.get(30, TimeUnit.SECONDS)
                );
            }

            List<Long> distinctIds = results.stream()
                    .map(
                            RegisterToolExecutionResult
                                    ::toolExecutionId
                    )
                    .distinct()
                    .toList();

            assertEquals(1, distinctIds.size());

            long newlyCreatedCount = results.stream()
                    .filter(
                            RegisterToolExecutionResult
                                    ::newlyCreated
                    )
                    .count();

            assertEquals(1L, newlyCreatedCount);

            assertEquals(
                    1L,
                    countExecutions(fixture.tenantId())
            );
            assertEquals(
                    1L,
                    countRegistrationAudits(fixture.tenantId())
            );
        } finally {
            start.countDown();

            for (Future<RegisterToolExecutionResult> future
                    : futures) {
                future.cancel(true);
            }

            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldLetOneConcurrentInputWinAndRejectTheOtherAsConflict()
            throws Exception {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        JsonNode readInput = objectMapper.readTree(
                "{\"op\":\"read\"}"
        );
        JsonNode writeInput = objectMapper.readTree(
                "{\"op\":\"write\"}"
        );

        RegisterToolExecutionCommand readCommand =
                command(
                        fixture,
                        "call-conflict",
                        "search",
                        readInput,
                        false,
                        "trace-1"
                );

        RegisterToolExecutionCommand writeCommand =
                command(
                        fixture,
                        "call-conflict",
                        "search",
                        writeInput,
                        false,
                        "trace-1"
                );

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<Attempt> readFuture = executor.submit(
                () -> attempt(ready, start, readCommand)
        );
        Future<Attempt> writeFuture = executor.submit(
                () -> attempt(ready, start, writeCommand)
        );

        assertTrue(ready.await(30, TimeUnit.SECONDS));
        start.countDown();

        try {
            Attempt readAttempt =
                    readFuture.get(30, TimeUnit.SECONDS);
            Attempt writeAttempt =
                    writeFuture.get(30, TimeUnit.SECONDS);

            Attempt winner =
                    readAttempt.result() != null
                            ? readAttempt
                            : writeAttempt;
            Attempt loser =
                    winner == readAttempt
                            ? writeAttempt
                            : readAttempt;

            assertNotNull(winner.result());
            assertTrue(winner.result().newlyCreated());
            assertNull(winner.error());

            assertNull(loser.result());
            assertInstanceOf(
                    ToolExecutionIdempotencyConflictException.class,
                    loser.error()
            );

            assertEquals(
                    1L,
                    countExecutions(fixture.tenantId())
            );
            assertEquals(
                    1L,
                    countRegistrationAudits(fixture.tenantId())
            );

            ToolExecutionDatabaseRow row =
                    readExecution(
                            fixture.tenantId(),
                            winner.result().toolExecutionId()
                    );

            JsonNode winnerInput =
                    winner == readAttempt
                            ? readInput
                            : writeInput;
            JsonNode loserInput =
                    winner == readAttempt
                            ? writeInput
                            : readInput;

            assertTrue(
                    winnerInput.equals(
                            parseJson(row.inputJson())
                    )
            );
            assertFalse(
                    loserInput.equals(
                            parseJson(row.inputJson())
                    )
            );
        } finally {
            start.countDown();
            readFuture.cancel(true);
            writeFuture.cancel(true);
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldTreatToolCallIdsAsCaseSensitive() {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        RegisterToolExecutionResult upper =
                registerService.register(command(
                        fixture,
                        "call_A",
                        "search",
                        input(),
                        false,
                        "trace-1"
                ));

        RegisterToolExecutionResult lower =
                registerService.register(command(
                        fixture,
                        "call_a",
                        "search",
                        input(),
                        false,
                        "trace-1"
                ));

        assertTrue(upper.newlyCreated());
        assertTrue(lower.newlyCreated());

        assertNotEquals(
                upper.toolExecutionId(),
                lower.toolExecutionId()
        );
        assertNotEquals(
                upper.idempotencyKey(),
                lower.idempotencyKey()
        );

        assertEquals(
                2L,
                countExecutions(fixture.tenantId())
        );
        assertEquals(
                2L,
                countRegistrationAudits(fixture.tenantId())
        );
        assertEquals(
                1L,
                countExecutionsByCallId(
                        fixture.tenantId(),
                        "call_A"
                )
        );
        assertEquals(
                1L,
                countExecutionsByCallId(
                        fixture.tenantId(),
                        "call_a"
                )
        );
    }

    @Test
    void shouldRejectScopeForOtherUserInSameTenant() {
        Fixture fixture = insertFixture();

        long otherUserId = insertAdditionalUser(fixture);

        when(currentActorProvider.requireCurrentActor())
                .thenReturn(new CurrentActor(
                        otherUserId,
                        fixture.tenantId(),
                        "other" + otherUserId,
                        Set.of("MEMBER")
                ));

        assertScopeRejected(
                fixture,
                command(
                        fixture,
                        "call-1",
                        "search",
                        input(),
                        false,
                        "trace-1"
                )
        );
    }

    @Test
    void shouldRejectScopeForForeignTenant() {
        Fixture fixture = insertFixture();
        Fixture foreign = insertFixture();
        mockCurrentActor(foreign);

        assertScopeRejected(
                fixture,
                command(
                        fixture,
                        "call-1",
                        "search",
                        input(),
                        false,
                        "trace-1"
                )
        );
    }

    @Test
    void shouldRejectScopeForForeignAgentId() {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        assertScopeRejected(
                fixture,
                new RegisterToolExecutionCommand(
                        fixture.conversationId(),
                        fixture.agentId() + 1L,
                        fixture.assistantMessageId(),
                        "call-1",
                        "search",
                        input(),
                        false,
                        "trace-1"
                )
        );
    }

    @Test
    void shouldRejectScopeForUserMessageAsRequest() {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        assertScopeRejected(
                fixture,
                new RegisterToolExecutionCommand(
                        fixture.conversationId(),
                        fixture.agentId(),
                        fixture.userMessageId(),
                        "call-1",
                        "search",
                        input(),
                        false,
                        "trace-1"
                )
        );
    }

    @Test
    void shouldRejectScopeForAssistantMessageFromOtherConversation() {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        long auxiliaryAssistantId =
                insertAuxiliaryAssistantMessage(fixture);

        assertScopeRejected(
                fixture,
                new RegisterToolExecutionCommand(
                        fixture.conversationId(),
                        fixture.agentId(),
                        auxiliaryAssistantId,
                        "call-1",
                        "search",
                        input(),
                        false,
                        "trace-1"
                )
        );
    }

    @Test
    void shouldRejectScopeAfterAssistantCompleted() {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        jdbcTemplate.update(
                """
                UPDATE messages
                SET status = 'COMPLETED'
                WHERE id = ?
                """,
                fixture.assistantMessageId()
        );

        assertScopeRejected(
                fixture,
                command(
                        fixture,
                        "call-1",
                        "search",
                        input(),
                        false,
                        "trace-1"
                )
        );
    }

    @Test
    void shouldRejectScopeAfterConversationArchived() {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        jdbcTemplate.update(
                """
                UPDATE conversations
                SET status = 'ARCHIVED'
                WHERE id = ?
                """,
                fixture.conversationId()
        );

        assertScopeRejected(
                fixture,
                command(
                        fixture,
                        "call-1",
                        "search",
                        input(),
                        false,
                        "trace-1"
                )
        );
    }

    @Test
    void shouldRollbackExecutionWhenAuditWriteFails() {
        Fixture fixture = insertFixture();
        mockCurrentActor(fixture);

        ConversationSnapshot before =
                readConversationSnapshot(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        AuditLogWriter target =
                AopTestUtils.getUltimateTargetObject(
                        auditLogWriter
                );

        doThrow(new IllegalStateException(
                "Simulated registration audit failure"
        ))
                .when(target)
                .write(argThat(command ->
                        command != null
                                && command.tenantId()
                                == fixture.tenantId()
                                && "TOOL_EXECUTION_REGISTERED"
                                .equals(command.action())
                                && "TOOL_EXECUTION"
                                .equals(command.resourceType())
                ));

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> registerService.register(
                                command(
                                        fixture,
                                        "call-1",
                                        "search",
                                        input(),
                                        false,
                                        "trace-1"
                                )
                        )
                );

        assertEquals(
                "Simulated registration audit failure",
                exception.getMessage()
        );

        assertEquals(
                0L,
                countExecutions(fixture.tenantId())
        );
        assertEquals(
                0L,
                countRegistrationAudits(fixture.tenantId())
        );
        assertEquals(
                "CREATING",
                assistantStatus(fixture)
        );

        ConversationSnapshot after =
                readConversationSnapshot(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        assertEquals("ACTIVE", after.status());
        assertEquals(
                before.nextMessageSequence(),
                after.nextMessageSequence()
        );
        assertEquals(before.version(), after.version());
    }

    private void assertScopeRejected(
            Fixture fixture,
            RegisterToolExecutionCommand command
    ) {
        ConversationSnapshot conversationBefore =
                readConversationSnapshot(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        List<String> messageStatusesBefore =
                readMessageStatuses(
                        fixture.tenantId(),
                        fixture.conversationId()
                );

        assertThrows(
                ConversationNotFoundException.class,
                () -> registerService.register(command)
        );

        assertEquals(
                0L,
                countExecutions(fixture.tenantId())
        );
        assertEquals(
                0L,
                countRegistrationAudits(fixture.tenantId())
        );

        assertEquals(
                conversationBefore,
                readConversationSnapshot(
                        fixture.tenantId(),
                        fixture.conversationId()
                )
        );
        assertEquals(
                messageStatusesBefore,
                readMessageStatuses(
                        fixture.tenantId(),
                        fixture.conversationId()
                )
        );
    }

    private Attempt attempt(
            CountDownLatch ready,
            CountDownLatch start,
            RegisterToolExecutionCommand command
    ) {
        ready.countDown();

        try {
            if (!start.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "start latch timed out"
                );
            }

            try {
                return new Attempt(
                        registerService.register(command),
                        null
                );
            } catch (Throwable error) {
                return new Attempt(null, error);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Attempt(null, interrupted);
        }
    }

    private RegisterToolExecutionCommand command(
            Fixture fixture,
            String toolCallId,
            String toolName,
            JsonNode input,
            boolean approvalRequired,
            String traceId
    ) {
        return new RegisterToolExecutionCommand(
                fixture.conversationId(),
                fixture.agentId(),
                fixture.assistantMessageId(),
                toolCallId,
                toolName,
                input,
                approvalRequired,
                traceId
        );
    }

    private JsonNode input() {
        try {
            return objectMapper.readTree(
                    "{\"op\":\"read\"}"
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

    private long insertAuxiliaryAssistantMessage(Fixture fixture) {
        long conversationId =
                FIXTURE_IDS.incrementAndGet();
        long userMessageId =
                FIXTURE_IDS.incrementAndGet();
        long assistantMessageId =
                FIXTURE_IDS.incrementAndGet();

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
                        ?, ?, ?, ?, 'Auxiliary conversation',
                        'ACTIVE', CURRENT_TIMESTAMP(3), 3, 0
                    )
                """,
                conversationId,
                fixture.tenantId(),
                fixture.userId(),
                fixture.agentId()
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
                    (?, ?, ?, 1, 'USER', 'Auxiliary message',
                     'TEXT', 'COMPLETED')
                """,
                userMessageId,
                fixture.tenantId(),
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
                fixture.tenantId(),
                conversationId
        );

        return assistantMessageId;
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
                                resultSet.getString(
                                        "tool_name"
                                ),
                                resultSet.getString(
                                        "idempotency_key"
                                ),
                                resultSet.getString(
                                        "input_json"
                                ),
                                resultSet.getString(
                                        "output_json"
                                ),
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

    private List<AuditDatabaseRow> readRegistrationAudits(
            long tenantId
    ) {
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
                  AND action = 'TOOL_EXECUTION_REGISTERED'
                ORDER BY id
                """,
                (resultSet, rowNumber) ->
                        new AuditDatabaseRow(
                                resultSet.getLong("id"),
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
                                resultSet.getString("trace_id"),
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

    private List<String> readMessageStatuses(
            long tenantId,
            long conversationId
    ) {
        return jdbcTemplate.queryForList(
                """
                SELECT status
                FROM messages
                WHERE tenant_id = ?
                  AND conversation_id = ?
                ORDER BY sequence_no
                """,
                String.class,
                tenantId,
                conversationId
        );
    }

    private String assistantStatus(Fixture fixture) {
        return jdbcTemplate.queryForObject(
                """
                SELECT status
                FROM messages
                WHERE id = ?
                """,
                String.class,
                fixture.assistantMessageId()
        );
    }

    private long countExecutions(long tenantId) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM tool_executions
                WHERE tenant_id = ?
                """,
                Long.class,
                tenantId
        );

        return count == null ? 0L : count;
    }

    private long countExecutionsByCallId(
            long tenantId,
            String toolCallId
    ) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM tool_executions
                WHERE tenant_id = ?
                  AND tool_call_id = ?
                """,
                Long.class,
                tenantId,
                toolCallId
        );

        return count == null ? 0L : count;
    }

    private long countRegistrationAudits(long tenantId) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_logs
                WHERE tenant_id = ?
                  AND action = 'TOOL_EXECUTION_REGISTERED'
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

    private record ConversationSnapshot(
            String status,
            long nextMessageSequence,
            int version
    ) {
    }

    private record Attempt(
            RegisterToolExecutionResult result,
            Throwable error
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
