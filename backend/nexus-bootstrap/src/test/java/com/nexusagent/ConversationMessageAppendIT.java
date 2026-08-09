package com.nexusagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.agent.api.ChangeAgentStatusRequest;
import com.nexusagent.agent.api.ChangeAgentStatusResponse;
import com.nexusagent.agent.api.CreateAgentRequest;
import com.nexusagent.agent.api.CreateAgentResponse;
import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.agent.domain.AgentStatus;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.conversation.api.AppendUserMessageRequest;
import com.nexusagent.conversation.api.AppendUserMessageResponse;
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
import org.springframework.test.context.bean.override.mockito.MockReset;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
class ConversationMessageAppendIT {

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
    void shouldRequireAuthenticationBeforeValidationAndAllowOwnerWithMemberAndAdminRoles()
            throws Exception {
        TenantSession tenant =
                bootstrapAndLogin(
                        "conversation-message-auth-acme"
                );

        CreatedAgent agent =
                createActiveAgent(
                        tenant,
                        "conversation-message-auth-agent"
                );

        CreatedConversation conversation =
                createConversation(
                        tenant,
                        agent,
                        "Auth conversation",
                        "Initial message"
                );

        // 无 Token 必须在校验之前被拦截，返回 401 而不是 400。
        ResponseEntity<String> unauthorized =
                appendMessage(
                        null,
                        conversation.id(),
                        "{}",
                        String.class
                );

        assertEquals(
                HttpStatus.UNAUTHORIZED,
                unauthorized.getStatusCode()
        );

        String memberToken =
                issueMemberToken(tenant);

        // MEMBER + 空 body：必须通过认证，走到参数校验。
        ResponseEntity<String> memberInvalid =
                appendMessage(
                        memberToken,
                        conversation.id(),
                        "{}",
                        String.class
                );

        assertProblem(
                memberInvalid,
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED"
        );

        ResponseEntity<AppendUserMessageResponse>
                memberEntity =
                appendMessage(
                        memberToken,
                        conversation.id(),
                        new AppendUserMessageRequest(
                                "  Member message  "
                        ),
                        AppendUserMessageResponse.class
                );

        assertEquals(
                HttpStatus.CREATED,
                memberEntity.getStatusCode()
        );

        AppendUserMessageResponse memberAppend =
                requireBody(memberEntity);

        assertAppendedMessage(
                memberAppend,
                conversation,
                "Member message",
                2L,
                1
        );

        ResponseEntity<AppendUserMessageResponse>
                adminEntity =
                appendMessage(
                        tenant.adminAccessToken(),
                        conversation.id(),
                        new AppendUserMessageRequest(
                                "  Admin message  "
                        ),
                        AppendUserMessageResponse.class
                );

        assertEquals(
                HttpStatus.CREATED,
                adminEntity.getStatusCode()
        );

        AppendUserMessageResponse adminAppend =
                requireBody(adminEntity);

        assertAppendedMessage(
                adminAppend,
                conversation,
                "Admin message",
                3L,
                2
        );

        ConversationSnapshot snapshot =
                readConversationSnapshot(
                        tenant.tenantId(),
                        conversation.id()
                );

        assertAll(
                () -> assertEquals(
                        3L,
                        countMessages(tenant.tenantId())
                ),
                () -> assertEquals(
                        List.of(1L, 2L, 3L),
                        readMessageSequences(
                                tenant.tenantId(),
                                conversation.id()
                        )
                ),
                () -> assertEquals(
                        4L,
                        snapshot.nextMessageSequence()
                ),
                () -> assertEquals(
                        2,
                        snapshot.version()
                ),
                () -> assertEquals(
                        2L,
                        countAppendAudits(
                                tenant.tenantId()
                        )
                )
        );
    }

    @Test
    void shouldPersistExactMessageAdvanceConversationAndSafeSixKeyAudit()
            throws Exception {
        TenantSession tenant =
                bootstrapAndLogin(
                        "conversation-append-persist-acme"
                );

        CreatedAgent agent =
                createActiveAgent(
                        tenant,
                        "conversation-append-persist-agent"
                );

        CreatedConversation conversation =
                createConversation(
                        tenant,
                        agent,
                        "Persist conversation",
                        "Initial message"
                );

        ResponseEntity<AppendUserMessageResponse>
                entity =
                appendMessage(
                        tenant.adminAccessToken(),
                        conversation.id(),
                        new AppendUserMessageRequest(
                                "  customer-payment-secret-must-not-enter-audit  "
                        ),
                        AppendUserMessageResponse.class
                );

        assertEquals(
                HttpStatus.CREATED,
                entity.getStatusCode()
        );

        AppendUserMessageResponse response =
                requireBody(entity);

        long messageId = Long.parseLong(
                response.message().messageId()
        );

        assertAll(
                () -> assertTrue(
                        response.message().messageId()
                                .matches("\\d+")
                ),
                () -> assertTrue(messageId > 0),
                () -> assertEquals(
                        "customer-payment-secret-must-not-enter-audit",
                        response.message().content()
                ),
                () -> assertEquals(
                        2L,
                        response.message().sequenceNo()
                ),
                () -> assertEquals(
                        1,
                        response.conversationVersion()
                ),
                () -> assertEquals(
                        MessageRole.USER,
                        response.message().role()
                ),
                () -> assertEquals(
                        MessageContentType.TEXT,
                        response.message().contentType()
                ),
                () -> assertEquals(
                        MessageStatus.COMPLETED,
                        response.message().status()
                ),
                () -> assertEquals(
                        response.lastMessageAt(),
                        response.message().createdAt()
                )
        );

        List<MessageDatabaseRow> messageRows =
                readMessageRows(
                        tenant.tenantId(),
                        conversation.id()
                );

        assertEquals(
                new MessageDatabaseRow(
                        messageId,
                        tenant.tenantId(),
                        conversation.id(),
                        2L,
                        "USER",
                        "customer-payment-secret-must-not-enter-audit",
                        "TEXT",
                        "COMPLETED",
                        null,
                        null,
                        null,
                        null,
                        response.lastMessageAt()
                ),
                messageRows.get(1)
        );

        assertEquals(2, messageRows.size());

        ConversationSnapshot snapshot =
                readConversationSnapshot(
                        tenant.tenantId(),
                        conversation.id()
                );

        assertAll(
                () -> assertEquals(
                        "ACTIVE",
                        snapshot.status()
                ),
                () -> assertEquals(
                        3L,
                        snapshot.nextMessageSequence()
                ),
                () -> assertEquals(
                        1,
                        snapshot.version()
                ),
                () -> assertEquals(
                        response.lastMessageAt(),
                        snapshot.lastMessageAt()
                ),
                () -> assertEquals(
                        response.lastMessageAt(),
                        snapshot.updatedAt()
                )
        );

        AppendAuditDatabaseRow audit =
                readAppendAudit(
                        tenant.tenantId(),
                        messageId
                );

        JsonNode expectedAfter =
                objectMapper.readTree(
                        """
                        {
                          "conversationId": "%d",
                          "sequenceNo": 2,
                          "role": "USER",
                          "contentType": "TEXT",
                          "status": "COMPLETED",
                          "conversationVersion": 1
                        }
                        """.formatted(
                                conversation.id()
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
                        Long.valueOf(tenant.adminUserId()),
                        audit.actorId()
                ),
                () -> assertEquals(
                        "CONVERSATION_MESSAGE_APPENDED",
                        audit.action()
                ),
                () -> assertEquals(
                        "MESSAGE",
                        audit.resourceType()
                ),
                () -> assertEquals(
                        Long.valueOf(messageId),
                        audit.resourceId()
                ),
                () -> assertEquals(
                        "SUCCESS",
                        audit.result()
                ),
                () -> assertNull(audit.beforeJson()),
                () -> assertEquals(
                        expectedAfter,
                        objectMapper.readTree(
                                audit.afterJson()
                        )
                ),
                () -> assertEquals(
                        6,
                        audit.afterKeyCount()
                ),
                () -> assertFalse(
                        audit.afterJson().contains(
                                "customer-payment-secret"
                        )
                ),
                () -> assertNull(audit.errorCode()),
                () -> assertNull(audit.errorMessage()),
                () -> assertNotNull(audit.createdAt())
        );
    }

    @Test
    void shouldHideMissingCrossTenantAndDifferentOwnerConversations()
            throws Exception {
        TenantSession owner =
                bootstrapAndLogin(
                        "conversation-owner-acme"
                );

        CreatedAgent agent =
                createActiveAgent(
                        owner,
                        "conversation-owner-agent"
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
                        "conversation-outsider-acme"
                );

        IssuedAccessToken differentUserToken =
                accessTokenIssuer.issue(
                        new TokenSubject(
                                owner.adminUserId()
                                        + 999_999L,
                                owner.tenantId(),
                                "different-user",
                                List.of("MEMBER")
                        )
                );

        ResponseEntity<String> missing =
                appendMessage(
                        owner.adminAccessToken(),
                        999_000_000_000L,
                        new AppendUserMessageRequest(
                                "Hello"
                        ),
                        String.class
                );

        ResponseEntity<String> crossTenant =
                appendMessage(
                        outsider.adminAccessToken(),
                        conversation.id(),
                        new AppendUserMessageRequest(
                                "Hello"
                        ),
                        String.class
                );

        ResponseEntity<String> differentUser =
                appendMessage(
                        differentUserToken.value(),
                        conversation.id(),
                        new AppendUserMessageRequest(
                                "Hello"
                        ),
                        String.class
                );

        assertHiddenConversation(missing);
        assertHiddenConversation(crossTenant);
        assertHiddenConversation(differentUser);

        ConversationSnapshot snapshot =
                readConversationSnapshot(
                        owner.tenantId(),
                        conversation.id()
                );

        assertAll(
                () -> assertEquals(
                        List.of(1L),
                        readMessageSequences(
                                owner.tenantId(),
                                conversation.id()
                        )
                ),
                () -> assertEquals(
                        2L,
                        snapshot.nextMessageSequence()
                ),
                () -> assertEquals(
                        0,
                        snapshot.version()
                ),
                () -> assertEquals(
                        0L,
                        countAppendAudits(
                                owner.tenantId()
                        )
                )
        );
    }

    @Test
    void shouldRejectCompletedAndArchivedConversationsWithoutWriting()
            throws Exception {
        TenantSession tenant =
                bootstrapAndLogin(
                        "conversation-notactive-acme"
                );

        CreatedAgent agent =
                createActiveAgent(
                        tenant,
                        "conversation-notactive-agent"
                );

        for (ConversationStatus status :
                List.of(
                        ConversationStatus.COMPLETED,
                        ConversationStatus.ARCHIVED
                )) {
            CreatedConversation conversation =
                    createConversation(
                            tenant,
                            agent,
                            status + " conversation",
                            "Initial message"
                    );

            jdbcTemplate.update(
                    """
                    UPDATE conversations
                    SET status = ?
                    WHERE tenant_id = ?
                      AND id = ?
                    """,
                    status.name(),
                    tenant.tenantId(),
                    conversation.id()
            );

            ConversationSnapshot before =
                    readConversationSnapshot(
                            tenant.tenantId(),
                            conversation.id()
                    );

            long messagesBefore =
                    countConversationMessages(
                            tenant.tenantId(),
                            conversation.id()
                    );

            ResponseEntity<String> response =
                    appendMessage(
                            tenant.adminAccessToken(),
                            conversation.id(),
                            new AppendUserMessageRequest(
                                    "Must be rejected"
                            ),
                            String.class
                    );

            JsonNode problem =
                    assertProblem(
                            response,
                            HttpStatus.CONFLICT,
                            "CONVERSATION_NOT_ACTIVE"
                    );

            assertEquals(
                    status.name(),
                    problem.path("currentStatus").asText()
            );

            ConversationSnapshot after =
                    readConversationSnapshot(
                            tenant.tenantId(),
                            conversation.id()
                    );

            assertAll(
                    () -> assertEquals(
                            messagesBefore,
                            countConversationMessages(
                                    tenant.tenantId(),
                                    conversation.id()
                            )
                    ),
                    () -> assertEquals(
                            before.nextMessageSequence(),
                            after.nextMessageSequence()
                    ),
                    () -> assertEquals(
                            before.version(),
                            after.version()
                    ),
                    () -> assertEquals(
                            before.lastMessageAt(),
                            after.lastMessageAt()
                    ),
                    () -> assertEquals(
                            before.updatedAt(),
                            after.updatedAt()
                    ),
                    () -> assertEquals(
                            0L,
                            countAppendAudits(
                                    tenant.tenantId()
                            )
                    )
            );
        }
    }

    @Test
    void shouldSerializeConcurrentAppendsIntoContinuousUniqueSequences()
            throws Exception {
        TenantSession tenant =
                bootstrapAndLogin(
                        "conversation-concurrent-acme"
                );

        CreatedAgent agent =
                createActiveAgent(
                        tenant,
                        "conversation-concurrent-agent"
                );

        CreatedConversation conversation =
                createConversation(
                        tenant,
                        agent,
                        "Concurrent conversation",
                        "Initial message"
                );

        int requestCount = 8;

        ExecutorService executor =
                Executors.newFixedThreadPool(requestCount);

        CountDownLatch ready =
                new CountDownLatch(requestCount);

        CountDownLatch start =
                new CountDownLatch(1);

        List<Future<ResponseEntity<AppendUserMessageResponse>>>
                futures = new ArrayList<>();

        try {
            for (int index = 0;
                 index < requestCount;
                 index++) {
                int messageIndex = index;

                futures.add(executor.submit(() -> {
                    ready.countDown();

                    start.await();

                    return appendMessage(
                            tenant.adminAccessToken(),
                            conversation.id(),
                            new AppendUserMessageRequest(
                                    "Concurrent message "
                                            + messageIndex
                            ),
                            AppendUserMessageResponse.class
                    );
                }));
            }

            ready.await(30, TimeUnit.SECONDS);
            start.countDown();

            List<ResponseEntity<AppendUserMessageResponse>>
                    responses = new ArrayList<>();

            for (Future<
                    ResponseEntity<
                            AppendUserMessageResponse
                            >> future : futures) {
                responses.add(
                        future.get(30, TimeUnit.SECONDS)
                );
            }

            assertTrue(
                    responses.stream().allMatch(response ->
                            response.getStatusCode()
                                    == HttpStatus.CREATED
                    )
            );

            List<Long> messageIds =
                    responses.stream()
                            .map(ConversationMessageAppendIT::requireBody)
                            .map(response ->
                                    Long.parseLong(
                                            response.message()
                                                    .messageId()
                                    )
                            )
                            .sorted()
                            .toList();

            assertEquals(
                    messageIds.size(),
                    messageIds.stream()
                            .distinct()
                            .count()
            );

            // 不能按 Future 返回顺序判断 sequence，
            // 线程完成顺序不等于获得数据库锁的顺序。
            List<Long> sequences =
                    responses.stream()
                            .map(ConversationMessageAppendIT::requireBody)
                            .map(response ->
                                    response.message()
                                            .sequenceNo()
                            )
                            .sorted()
                            .toList();

            assertEquals(
                    List.of(
                            2L, 3L, 4L, 5L,
                            6L, 7L, 8L, 9L
                    ),
                    sequences
            );

            List<Integer> versions =
                    responses.stream()
                            .map(ConversationMessageAppendIT::requireBody)
                            .map(response ->
                                    response.conversationVersion()
                            )
                            .sorted()
                            .toList();

            assertEquals(
                    List.of(
                            1, 2, 3, 4,
                            5, 6, 7, 8
                    ),
                    versions
            );

            assertEquals(
                    List.of(
                            1L, 2L, 3L, 4L,
                            5L, 6L, 7L, 8L, 9L
                    ),
                    readMessageSequences(
                            tenant.tenantId(),
                            conversation.id()
                    )
            );

            ConversationSnapshot snapshot =
                    readConversationSnapshot(
                            tenant.tenantId(),
                            conversation.id()
                    );

            assertAll(
                    () -> assertEquals(
                            9L,
                            countConversationMessages(
                                    tenant.tenantId(),
                                    conversation.id()
                            )
                    ),
                    () -> assertEquals(
                            10L,
                            snapshot.nextMessageSequence()
                    ),
                    () -> assertEquals(
                            8,
                            snapshot.version()
                    ),
                    () -> assertEquals(
                            8L,
                            countAppendAudits(
                                    tenant.tenantId()
                            )
                    )
            );
        } finally {
            start.countDown();

            futures.forEach(future ->
                    future.cancel(true)
            );

            executor.shutdownNow();

            assertTrue(
                    executor.awaitTermination(
                            10,
                            TimeUnit.SECONDS
                    )
            );
        }
    }

    @Test
    void shouldRollbackMessageCounterVersionAndAuditWhenAuditWriteFails() {
        TenantSession tenant =
                bootstrapAndLogin(
                        "conversation-append-rollback-acme"
                );

        CreatedAgent agent =
                createActiveAgent(
                        tenant,
                        "conversation-append-rollback-agent"
                );

        CreatedConversation conversation =
                createConversation(
                        tenant,
                        agent,
                        "Rollback conversation",
                        "Initial message"
                );

        ConversationSnapshot before =
                readConversationSnapshot(
                        tenant.tenantId(),
                        conversation.id()
                );

        long messagesBefore =
                countConversationMessages(
                        tenant.tenantId(),
                        conversation.id()
                );

        assertEquals(
                1L,
                messagesBefore,
                "conversation must contain only the "
                        + "initial message"
        );

        long auditsBefore =
                countAuditLogs(tenant.tenantId());

        /*
         * 必须先完成全部初始化并读取快照，
         * 再安装审计异常桩，避免拦截初始化审计。
         */
        AuditLogWriter target =
                AopTestUtils.getUltimateTargetObject(
                        auditLogWriter
                );

        doThrow(new IllegalStateException(
                "Simulated append audit failure"
        ))
                .when(target)
                .write(argThat(command ->
                        command != null
                                && command.tenantId()
                                == tenant.tenantId()
                                && "CONVERSATION_MESSAGE_APPENDED"
                                .equals(command.action())
                                && "MESSAGE".equals(
                                command.resourceType()
                        )
                ));

        ResponseEntity<String> response =
                appendMessage(
                        tenant.adminAccessToken(),
                        conversation.id(),
                        new AppendUserMessageRequest(
                                "This message must roll back"
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
                        && "CONVERSATION_MESSAGE_APPENDED"
                        .equals(command.action())
                        && "MESSAGE".equals(
                        command.resourceType()
                )
        ));

        ConversationSnapshot after =
                readConversationSnapshot(
                        tenant.tenantId(),
                        conversation.id()
                );

        assertAll(
                () -> assertEquals(
                        messagesBefore,
                        countConversationMessages(
                                tenant.tenantId(),
                                conversation.id()
                        )
                ),
                () -> assertEquals(
                        before.nextMessageSequence(),
                        after.nextMessageSequence()
                ),
                () -> assertEquals(
                        before.version(),
                        after.version()
                ),
                () -> assertEquals(
                        before.lastMessageAt(),
                        after.lastMessageAt()
                ),
                () -> assertEquals(
                        before.updatedAt(),
                        after.updatedAt()
                ),
                () -> assertEquals(
                        0L,
                        countAppendAudits(
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
                                resultSet.getString(
                                        "status"
                                ),
                                resultSet.getTimestamp(
                                        "last_message_at"
                                ).toInstant(),
                                resultSet.getLong(
                                        "next_message_sequence"
                                ),
                                resultSet.getInt(
                                        "version"
                                ),
                                resultSet.getTimestamp(
                                        "updated_at"
                                ).toInstant()
                        ),
                tenantId,
                conversationId
        );
    }

    private List<MessageDatabaseRow> readMessageRows(
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
                        AS metadata_json,
                    created_at
                FROM messages
                WHERE tenant_id = ?
                  AND conversation_id = ?
                ORDER BY sequence_no
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
                                resultSet.getString(
                                        "content"
                                ),
                                resultSet.getString(
                                        "content_type"
                                ),
                                resultSet.getString(
                                        "status"
                                ),
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
                conversationId
        );
    }

    private List<Long> readMessageSequences(
            long tenantId,
            long conversationId
    ) {
        return jdbcTemplate.query(
                """
                SELECT sequence_no
                FROM messages
                WHERE tenant_id = ?
                  AND conversation_id = ?
                ORDER BY sequence_no
                """,
                (resultSet, rowNumber) ->
                        resultSet.getLong(
                                "sequence_no"
                        ),
                tenantId,
                conversationId
        );
    }

    private AppendAuditDatabaseRow readAppendAudit(
            long tenantId,
            long messageId
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
                    result,
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
                  AND action = 'CONVERSATION_MESSAGE_APPENDED'
                  AND resource_type = 'MESSAGE'
                  AND resource_id = ?
                """,
                (resultSet, rowNumber) ->
                        new AppendAuditDatabaseRow(
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
                                resultSet.getString(
                                        "result"
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
                messageId
        );
    }

    private long countMessages(long tenantId) {
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

    private long countAppendAudits(long tenantId) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_logs
                WHERE tenant_id = ?
                  AND action = 'CONVERSATION_MESSAGE_APPENDED'
                """,
                Long.class,
                tenantId
        );

        return count == null ? 0L : count;
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

    private <T> ResponseEntity<T> appendMessage(
            String accessToken,
            long conversationId,
            Object request,
            Class<T> responseType
    ) {
        HttpHeaders headers =
                accessToken == null
                        ? jsonHeaders()
                        : bearerHeaders(accessToken);

        return restTemplate.exchange(
                "/api/v1/conversations/"
                        + "{conversationId}/messages",
                HttpMethod.POST,
                new HttpEntity<>(
                        request,
                        headers
                ),
                responseType,
                conversationId
        );
    }

    private CreatedConversation createConversation(
            TenantSession tenant,
            CreatedAgent agent,
            String title,
            String initialMessage
    ) {
        ResponseEntity<CreateConversationResponse>
                response =
                postConversation(
                        tenant.adminAccessToken(),
                        new CreateConversationRequest(
                                agent.code(),
                                title,
                                initialMessage
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

    private void assertHiddenConversation(
            ResponseEntity<String> response
    ) throws Exception {
        JsonNode problem =
                assertProblem(
                        response,
                        HttpStatus.NOT_FOUND,
                        "CONVERSATION_NOT_FOUND"
                );

        assertAll(
                () -> assertEquals(
                        "Conversation not found",
                        problem.path("title").asText()
                ),
                () -> assertEquals(
                        "Conversation not found",
                        problem.path("detail").asText()
                ),
                () -> assertFalse(
                        problem.has("conversationId")
                ),
                () -> assertFalse(
                        problem.has("tenantId")
                ),
                () -> assertFalse(
                        problem.has("userId")
                )
        );
    }

    private static void assertAppendedMessage(
            AppendUserMessageResponse response,
            CreatedConversation conversation,
            String expectedContent,
            long expectedSequenceNo,
            int expectedVersion
    ) {
        assertAll(
                () -> assertEquals(
                        Long.toString(conversation.id()),
                        response.conversationId()
                ),
                () -> assertEquals(
                        expectedVersion,
                        response.conversationVersion()
                ),
                () -> assertEquals(
                        response.lastMessageAt(),
                        response.message().createdAt()
                ),
                () -> assertTrue(
                        Long.parseLong(
                                response.message()
                                        .messageId()
                        ) > 0
                ),
                () -> assertEquals(
                        expectedSequenceNo,
                        response.message().sequenceNo()
                ),
                () -> assertEquals(
                        MessageRole.USER,
                        response.message().role()
                ),
                () -> assertEquals(
                        expectedContent,
                        response.message().content()
                ),
                () -> assertEquals(
                        MessageContentType.TEXT,
                        response.message().contentType()
                ),
                () -> assertEquals(
                        MessageStatus.COMPLETED,
                        response.message().status()
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
            String metadataJson,
            Instant createdAt
    ) {
    }

    private record AppendAuditDatabaseRow(
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
            int afterKeyCount,
            String errorCode,
            String errorMessage,
            Instant createdAt
    ) {
    }
}
