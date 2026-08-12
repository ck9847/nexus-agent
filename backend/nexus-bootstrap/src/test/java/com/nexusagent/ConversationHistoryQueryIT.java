package com.nexusagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.agent.api.ChangeAgentStatusRequest;
import com.nexusagent.agent.api.ChangeAgentStatusResponse;
import com.nexusagent.agent.api.CreateAgentRequest;
import com.nexusagent.agent.api.CreateAgentResponse;
import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.agent.domain.AgentStatus;
import com.nexusagent.common.id.IdGenerator;
import com.nexusagent.conversation.api.AppendUserMessageRequest;
import com.nexusagent.conversation.api.AppendUserMessageResponse;
import com.nexusagent.conversation.api.ConversationDetailResponse;
import com.nexusagent.conversation.api.ConversationMessageResponse;
import com.nexusagent.conversation.api.ConversationMessagesResponse;
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
import org.springframework.web.util.UriUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest(
        webEnvironment =
                SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = """
                nexus.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=
                """
)
class ConversationHistoryQueryIT {

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

    @Autowired
    private IdGenerator idGenerator;

    /*
     * 业务服务使用的 Clock 是宿主机的时钟；
     * JDBC 直写会话时间戳必须用同一个时钟，
     * 否则 MySQL 容器时钟漂移会让
     * updated_at < last_message_at 校验失败。
     */
    @Autowired
    private Clock clock;

    @BeforeEach
    void configurePatchSupport() {
        restTemplate.getRestTemplate()
                .setRequestFactory(
                        new JdkClientHttpRequestFactory()
                );
    }

    /*
     * 场景一：认证与详情。
     * 无 Token 一律 401；owner 以 MEMBER / ADMIN
     * 角色都能读取详情；详情只暴露公开字段，
     * 不返回 tenantId / userId / nextMessageSequence。
     */
    @Test
    void shouldRequireAuthenticationAndAllowOwnerToReadDetails()
            throws Exception {
        TenantSession tenant =
                bootstrapAndLogin(
                        "conversation-history-detail-acme"
                );

        CreatedAgent agent =
                createActiveAgent(
                        tenant,
                        "conversation-history-detail-agent"
                );

        CreatedConversation conversation =
                createConversation(
                        tenant,
                        agent,
                        "Detail conversation",
                        "Initial message"
                );

        ResponseEntity<String> detailNoToken =
                getConversation(
                        null,
                        conversation.id(),
                        String.class
                );

        assertEquals(
                HttpStatus.UNAUTHORIZED,
                detailNoToken.getStatusCode()
        );

        ResponseEntity<String> messagesNoToken =
                getMessages(
                        null,
                        conversation.id(),
                        null,
                        null,
                        String.class
                );

        assertEquals(
                HttpStatus.UNAUTHORIZED,
                messagesNoToken.getStatusCode()
        );

        String memberToken =
                issueMemberToken(tenant);

        ResponseEntity<ConversationDetailResponse>
                memberDetail =
                getConversation(
                        memberToken,
                        conversation.id(),
                        ConversationDetailResponse.class
                );

        assertEquals(
                HttpStatus.OK,
                memberDetail.getStatusCode()
        );

        assertEquals(
                new ConversationDetailResponse(
                        Long.toString(
                                conversation.id()
                        ),
                        Long.toString(agent.id()),
                        "Detail conversation",
                        ConversationStatus.ACTIVE,
                        conversation
                                .initialLastMessageAt(),
                        0,
                        conversation
                                .initialLastMessageAt(),
                        conversation
                                .initialLastMessageAt()
                ),
                requireBody(memberDetail)
        );

        ResponseEntity<String> adminDetail =
                getConversation(
                        tenant.adminAccessToken(),
                        conversation.id(),
                        String.class
                );

        assertEquals(
                HttpStatus.OK,
                adminDetail.getStatusCode()
        );

        JsonNode adminJson =
                objectMapper.readTree(
                        requireBody(adminDetail)
                );

        assertAll(
                () -> assertFalse(
                        adminJson.has("tenantId")
                ),
                () -> assertFalse(
                        adminJson.has("userId")
                ),
                () -> assertFalse(
                        adminJson.has(
                                "nextMessageSequence"
                        )
                )
        );
    }

    /*
     * 场景二：IDOR 隐身。
     * 不存在 / 跨租户 / 同租户不同用户 的会话，
     * 对详情和消息两个查询端点都返回 404，
     * 不泄露资源是否存在。
     */
    @Test
    void shouldHideMissingCrossTenantAndDifferentOwnerConversations()
            throws Exception {
        TenantSession owner =
                bootstrapAndLogin(
                        "conversation-history-hide-acme"
                );

        CreatedAgent agent =
                createActiveAgent(
                        owner,
                        "conversation-history-hide-agent"
                );

        CreatedConversation conversation =
                createConversation(
                        owner,
                        agent,
                        "Hidden conversation",
                        "Initial message"
                );

        TenantSession outsider =
                bootstrapAndLogin(
                        "conversation-history-hide-outsider"
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

        assertHiddenDetail(
                owner.adminAccessToken(),
                999_000_000_000L
        );
        assertHiddenMessages(
                owner.adminAccessToken(),
                999_000_000_000L
        );

        assertHiddenDetail(
                outsider.adminAccessToken(),
                conversation.id()
        );
        assertHiddenMessages(
                outsider.adminAccessToken(),
                conversation.id()
        );

        assertHiddenDetail(
                differentUserToken.value(),
                conversation.id()
        );
        assertHiddenMessages(
                differentUserToken.value(),
                conversation.id()
        );
    }

    /*
     * 场景三：三页真实分页。
     * 7 条消息，limit=3：第一页 [5,6,7]，第二页
     * [2,3,4]，第三页 [1]；合并排序必须等于
     * [1..7]，messageId 全部唯一，不重不漏。
     */
    @Test
    void shouldPageMessagesWithoutDuplicatesOrGaps()
            throws Exception {
        TenantSession tenant =
                bootstrapAndLogin(
                        "conversation-history-paging-acme"
                );

        CreatedAgent agent =
                createActiveAgent(
                        tenant,
                        "conversation-history-paging-agent"
                );

        CreatedConversation conversation =
                createConversation(
                        tenant,
                        agent,
                        "Paging conversation",
                        "Message 1"
                );

        for (int index = 2; index <= 7; index++) {
            appendMessage(
                    tenant.adminAccessToken(),
                    conversation.id(),
                    new AppendUserMessageRequest(
                            "Message " + index
                    ),
                    AppendUserMessageResponse.class
            );
        }

        ResponseEntity<ConversationMessagesResponse>
                page1Entity =
                getMessages(
                        tenant.adminAccessToken(),
                        conversation.id(),
                        3,
                        null,
                        ConversationMessagesResponse.class
                );

        assertEquals(
                HttpStatus.OK,
                page1Entity.getStatusCode()
        );

        ConversationMessagesResponse page1 =
                requireBody(page1Entity);

        assertAll(
                () -> assertEquals(
                        List.of(5L, 6L, 7L),
                        sequences(page1)
                ),
                () -> assertTrue(page1.hasMore()),
                () -> assertNotNull(
                        page1.nextCursor()
                )
        );

        ConversationMessagesResponse page2 =
                requireBody(
                        getMessages(
                                tenant.adminAccessToken(),
                                conversation.id(),
                                3,
                                page1.nextCursor(),
                                ConversationMessagesResponse.class
                        )
                );

        assertAll(
                () -> assertEquals(
                        List.of(2L, 3L, 4L),
                        sequences(page2)
                ),
                () -> assertTrue(page2.hasMore()),
                () -> assertNotNull(
                        page2.nextCursor()
                )
        );

        ConversationMessagesResponse page3 =
                requireBody(
                        getMessages(
                                tenant.adminAccessToken(),
                                conversation.id(),
                                3,
                                page2.nextCursor(),
                                ConversationMessagesResponse.class
                        )
                );

        assertAll(
                () -> assertEquals(
                        List.of(1L),
                        sequences(page3)
                ),
                () -> assertFalse(page3.hasMore()),
                () -> assertNull(page3.nextCursor())
        );

        List<Long> mergedSequences =
                mergeSorted(
                        sequences(page1),
                        sequences(page2),
                        sequences(page3)
                );

        List<Long> mergedIds =
                mergeSorted(
                        messageIds(page1),
                        messageIds(page2),
                        messageIds(page3)
                );

        assertAll(
                () -> assertEquals(
                        List.of(
                                1L, 2L, 3L, 4L,
                                5L, 6L, 7L
                        ),
                        mergedSequences
                ),
                () -> assertEquals(
                        mergedIds.size(),
                        mergedIds.stream()
                                .distinct()
                                .count()
                )
        );
    }

    /*
     * 场景四：cursor 绑定 conversation。
     * A 的 cursor 用在 B 上必须 400，而不是 404，
     * 因为 B 先通过 owner 校验，再发现 cursor 不属于 B。
     */
    @Test
    void shouldRejectCursorUsedWithAnotherConversation()
            throws Exception {
        TenantSession tenant =
                bootstrapAndLogin(
                        "conversation-history-cursor-acme"
                );

        CreatedAgent agent =
                createActiveAgent(
                        tenant,
                        "conversation-history-cursor-agent"
                );

        CreatedConversation conversationA =
                createConversation(
                        tenant,
                        agent,
                        "Conversation A",
                        "A-1"
                );

        for (int index = 2; index <= 4; index++) {
            appendMessage(
                    tenant.adminAccessToken(),
                    conversationA.id(),
                    new AppendUserMessageRequest(
                            "A-" + index
                    ),
                    AppendUserMessageResponse.class
            );
        }

        ConversationMessagesResponse pageA =
                requireBody(
                        getMessages(
                                tenant.adminAccessToken(),
                                conversationA.id(),
                                3,
                                null,
                                ConversationMessagesResponse.class
                        )
                );

        assertTrue(pageA.hasMore());

        String cursorForA = pageA.nextCursor();
        assertNotNull(cursorForA);

        CreatedConversation conversationB =
                createConversation(
                        tenant,
                        agent,
                        "Conversation B",
                        "B-1"
                );

        ResponseEntity<String> response =
                getMessages(
                        tenant.adminAccessToken(),
                        conversationB.id(),
                        3,
                        cursorForA,
                        String.class
                );

        JsonNode problem =
                assertProblem(
                        response,
                        HttpStatus.BAD_REQUEST,
                        "INVALID_CONVERSATION_QUERY"
                );

        assertAll(
                () -> assertEquals(
                        "Invalid conversation query",
                        problem.path("title").asText()
                ),
                () -> assertEquals(
                        "Invalid conversation message cursor",
                        problem.path("detail").asText()
                )
        );
    }

    /*
     * 场景五：分页快照稳定。
     * 拿到第一页 cursor 后再追加新消息，
     * 用旧 cursor 继续翻页仍然得到 [2,3,4]，
     * 新消息不会让旧页重复或漏数据。
     */
    @Test
    void shouldKeepOlderPaginationStableWhenNewMessageArrives()
            throws Exception {
        TenantSession tenant =
                bootstrapAndLogin(
                        "conversation-history-snapshot-acme"
                );

        CreatedAgent agent =
                createActiveAgent(
                        tenant,
                        "conversation-history-snapshot-agent"
                );

        CreatedConversation conversation =
                createConversation(
                        tenant,
                        agent,
                        "Snapshot conversation",
                        "Message 1"
                );

        for (int index = 2; index <= 7; index++) {
            appendMessage(
                    tenant.adminAccessToken(),
                    conversation.id(),
                    new AppendUserMessageRequest(
                            "Message " + index
                    ),
                    AppendUserMessageResponse.class
            );
        }

        ConversationMessagesResponse page1 =
                requireBody(
                        getMessages(
                                tenant.adminAccessToken(),
                                conversation.id(),
                                3,
                                null,
                                ConversationMessagesResponse.class
                        )
                );

        assertEquals(
                List.of(5L, 6L, 7L),
                sequences(page1)
        );

        String oldCursor = page1.nextCursor();
        assertNotNull(oldCursor);

        appendMessage(
                tenant.adminAccessToken(),
                conversation.id(),
                new AppendUserMessageRequest(
                        "Message 8"
                ),
                AppendUserMessageResponse.class
        );

        ConversationMessagesResponse page2 =
                requireBody(
                        getMessages(
                                tenant.adminAccessToken(),
                                conversation.id(),
                                3,
                                oldCursor,
                                ConversationMessagesResponse.class
                        )
                );

        /*
         * 旧 cursor 只约束 sequence_no < 5；
         * 新追加的 seq8 不影响更早的分页。
         * 页面内容仍为 [2,3,4]，不重不漏。
         * （hasMore 仍为 true：seq5 之下本来就有
         *   1,2,3,4 共 4 条，与新消息无关。）
         */
        assertEquals(
                List.of(2L, 3L, 4L),
                sequences(page2)
        );
    }

    /*
     * 场景六：SYSTEM / TOOL 不公开。
     * 通过 JDBC 直接插入 SYSTEM、TOOL 消息并推进
     * conversation 计数器后，查询端点只返回公开的
     * USER 消息，响应正文不包含内部秘密内容。
     */
    @Test
    void shouldExcludeSystemAndToolMessagesFromPublicTranscript()
            throws Exception {
        TenantSession tenant =
                bootstrapAndLogin(
                        "conversation-history-roles-acme"
                );

        CreatedAgent agent =
                createActiveAgent(
                        tenant,
                        "conversation-history-roles-agent"
                );

        CreatedConversation conversation =
                createConversation(
                        tenant,
                        agent,
                        "Roles conversation",
                        "Public hello"
                );

        insertInternalMessage(
                idGenerator.nextId(),
                tenant.tenantId(),
                conversation.id(),
                2L,
                "SYSTEM",
                "system-internal-secret"
        );

        insertInternalMessage(
                idGenerator.nextId(),
                tenant.tenantId(),
                conversation.id(),
                3L,
                "TOOL",
                "tool-internal-secret"
        );

        /*
         * 时间戳必须与业务写入同一时钟（宿主机），
         * 不能用 CURRENT_TIMESTAMP(3)（容器时钟），
         * 否则 updated_at 可能早于 created_at。
         */
        Timestamp now =
                new Timestamp(clock.millis());

        jdbcTemplate.update(
                """
                UPDATE conversations
                SET next_message_sequence = 4,
                    version = 2,
                    last_message_at = ?,
                    updated_at = ?
                WHERE tenant_id = ?
                  AND id = ?
                """,
                now,
                now,
                tenant.tenantId(),
                conversation.id()
        );

        ResponseEntity<String> response =
                getMessages(
                        tenant.adminAccessToken(),
                        conversation.id(),
                        null,
                        null,
                        String.class
                );

        assertEquals(
                HttpStatus.OK,
                response.getStatusCode()
        );

        String body = requireBody(response);

        ConversationMessagesResponse messages =
                objectMapper.readValue(
                        body,
                        ConversationMessagesResponse.class
                );

        assertAll(
                () -> assertEquals(
                        1,
                        messages.items().size()
                ),
                () -> {
                    ConversationMessageResponse only =
                            messages.items().get(0);

                    assertAll(
                            () -> assertEquals(
                                    1L,
                                    only.sequenceNo()
                            ),
                            () -> assertEquals(
                                    MessageRole.USER,
                                    only.role()
                            ),
                            () -> assertEquals(
                                    MessageContentType.TEXT,
                                    only.contentType()
                            ),
                            () -> assertEquals(
                                    MessageStatus.COMPLETED,
                                    only.status()
                            ),
                            () -> assertEquals(
                                    "Public hello",
                                    only.content()
                            )
                    );
                },
                () -> assertFalse(
                        body.contains(
                                "system-internal-secret"
                        )
                ),
                () -> assertFalse(
                        body.contains(
                                "tool-internal-secret"
                        )
                )
        );
    }

    /*
     * 场景七：COMPLETED / ARCHIVED 可读取。
     * 只有追加消息被禁止；详情与消息查询
     * 不受会话状态限制。
     */
    @Test
    void shouldAllowReadingCompletedAndArchivedConversations()
            throws Exception {
        TenantSession tenant =
                bootstrapAndLogin(
                        "conversation-history-status-acme"
                );

        CreatedAgent agent =
                createActiveAgent(
                        tenant,
                        "conversation-history-status-agent"
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

            appendMessage(
                    tenant.adminAccessToken(),
                    conversation.id(),
                    new AppendUserMessageRequest(
                            "Still readable"
                    ),
                    AppendUserMessageResponse.class
            );

            /*
             * 显式写入 updated_at（宿主机时钟）避免
             * ON UPDATE CURRENT_TIMESTAMP 把容器时钟
             * 写进会话，导致 updated_at 早于 last_message_at。
             */
            Timestamp now =
                    new Timestamp(clock.millis());

            jdbcTemplate.update(
                    """
                    UPDATE conversations
                    SET status = ?,
                        updated_at = ?
                    WHERE tenant_id = ?
                      AND id = ?
                    """,
                    status.name(),
                    now,
                    tenant.tenantId(),
                    conversation.id()
            );

            ResponseEntity<ConversationDetailResponse>
                    detail =
                    getConversation(
                            tenant.adminAccessToken(),
                            conversation.id(),
                            ConversationDetailResponse.class
                    );

            assertEquals(
                    HttpStatus.OK,
                    detail.getStatusCode()
            );

            assertEquals(
                    status,
                    requireBody(detail).status()
            );

            ResponseEntity<ConversationMessagesResponse>
                    messages =
                    getMessages(
                            tenant.adminAccessToken(),
                            conversation.id(),
                            null,
                            null,
                            ConversationMessagesResponse.class
                    );

            assertEquals(
                    HttpStatus.OK,
                    messages.getStatusCode()
            );

            assertEquals(
                    2,
                    requireBody(messages).items().size()
            );
        }
    }

    /*
     * 场景八：查询零写入。
     * 连续执行详情、第一页、下一页查询前后，
     * conversation 的快照字段、消息数、审计数
     * 必须完全一致，证明 readOnly 事务无隐藏写入。
     */
    @Test
    void shouldNotMutateConversationMessagesOrAuditDuringQueries()
            throws Exception {
        TenantSession tenant =
                bootstrapAndLogin(
                        "conversation-history-readonly-acme"
                );

        CreatedAgent agent =
                createActiveAgent(
                        tenant,
                        "conversation-history-readonly-agent"
                );

        CreatedConversation conversation =
                createConversation(
                        tenant,
                        agent,
                        "Read only conversation",
                        "One"
                );

        appendMessage(
                tenant.adminAccessToken(),
                conversation.id(),
                new AppendUserMessageRequest("Two"),
                AppendUserMessageResponse.class
        );

        appendMessage(
                tenant.adminAccessToken(),
                conversation.id(),
                new AppendUserMessageRequest("Three"),
                AppendUserMessageResponse.class
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

        long auditsBefore =
                countAuditLogs(tenant.tenantId());

        ResponseEntity<ConversationDetailResponse>
                detail =
                getConversation(
                        tenant.adminAccessToken(),
                        conversation.id(),
                        ConversationDetailResponse.class
                );

        assertEquals(
                HttpStatus.OK,
                detail.getStatusCode()
        );

        ConversationMessagesResponse page1 =
                requireBody(
                        getMessages(
                                tenant.adminAccessToken(),
                                conversation.id(),
                                2,
                                null,
                                ConversationMessagesResponse.class
                        )
                );

        assertAll(
                () -> assertEquals(
                        2,
                        page1.items().size()
                ),
                () -> assertTrue(page1.hasMore())
        );

        ConversationMessagesResponse page2 =
                requireBody(
                        getMessages(
                                tenant.adminAccessToken(),
                                conversation.id(),
                                2,
                                page1.nextCursor(),
                                ConversationMessagesResponse.class
                        )
                );

        assertAll(
                () -> assertEquals(
                        1,
                        page2.items().size()
                ),
                () -> assertFalse(page2.hasMore())
        );

        ConversationSnapshot after =
                readConversationSnapshot(
                        tenant.tenantId(),
                        conversation.id()
                );

        long messagesAfter =
                countConversationMessages(
                        tenant.tenantId(),
                        conversation.id()
                );

        long auditsAfter =
                countAuditLogs(tenant.tenantId());

        assertAll(
                () -> assertEquals(
                        before.status(),
                        after.status()
                ),
                () -> assertEquals(
                        before.lastMessageAt(),
                        after.lastMessageAt()
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
                        before.updatedAt(),
                        after.updatedAt()
                ),
                () -> assertEquals(
                        messagesBefore,
                        messagesAfter
                ),
                () -> assertEquals(
                        auditsBefore,
                        auditsAfter
                )
        );
    }

    /*
     * 场景九：非法查询。
     * limit=0 / 101 / 非数字，cursor=畸形 / 超长，
     * 一律 400 INVALID_CONVERSATION_QUERY；
     * 响应正文不能回显原始超长 cursor。
     */
    @Test
    void shouldRejectMalformedCursorAndInvalidLimits()
            throws Exception {
        TenantSession tenant =
                bootstrapAndLogin(
                        "conversation-history-invalid-acme"
                );

        CreatedAgent agent =
                createActiveAgent(
                        tenant,
                        "conversation-history-invalid-agent"
                );

        CreatedConversation conversation =
                createConversation(
                        tenant,
                        agent,
                        "Invalid query conversation",
                        "Hello"
                );

        String token =
                tenant.adminAccessToken();

        assertInvalidConversationQuery(
                getMessages(
                        token,
                        conversation.id(),
                        0,
                        null,
                        String.class
                )
        );

        assertInvalidConversationQuery(
                getMessages(
                        token,
                        conversation.id(),
                        101,
                        null,
                        String.class
                )
        );

        ResponseEntity<String> nonNumericLimit =
                restTemplate.exchange(
                        "/api/v1/conversations/"
                                + conversation.id()
                                + "/messages?limit=abc",
                        HttpMethod.GET,
                        new HttpEntity<>(
                                bearerHeaders(token)
                        ),
                        String.class
                );

        assertInvalidConversationQuery(
                nonNumericLimit
        );

        assertInvalidConversationQuery(
                getMessages(
                        token,
                        conversation.id(),
                        null,
                        "malformed",
                        String.class
                )
        );

        String overLongCursor =
                "c".repeat(257);

        ResponseEntity<String> tooLongCursor =
                getMessages(
                        token,
                        conversation.id(),
                        null,
                        overLongCursor,
                        String.class
                );

        assertInvalidConversationQuery(
                tooLongCursor
        );

        assertFalse(
                requireBody(tooLongCursor)
                        .contains(overLongCursor)
        );
    }

    /*
     * 场景十：Conversation 不存在优先于 cursor 错误。
     * 对不可见会话（不存在 / 跨租户 / 非所有者）
     * 使用畸形 cursor，必须返回 404 而不是 400，
     * 真实验证 ownership → cursor 的校验顺序。
     */
    @Test
    void shouldReturnNotFoundBeforeValidatingCursorForInvisibleConversation()
            throws Exception {
        TenantSession owner =
                bootstrapAndLogin(
                        "conversation-history-notfound-acme"
                );

        CreatedAgent agent =
                createActiveAgent(
                        owner,
                        "conversation-history-notfound-agent"
                );

        CreatedConversation conversation =
                createConversation(
                        owner,
                        agent,
                        "Hidden conversation",
                        "Hello"
                );

        TenantSession outsider =
                bootstrapAndLogin(
                        "conversation-history-notfound-outsider"
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

        assertNotFoundWithMalformedCursor(
                owner.adminAccessToken(),
                999_000_000_000L
        );

        assertNotFoundWithMalformedCursor(
                outsider.adminAccessToken(),
                conversation.id()
        );

        assertNotFoundWithMalformedCursor(
                differentUserToken.value(),
                conversation.id()
        );
    }

    private void assertHiddenDetail(
            String accessToken,
            long conversationId
    ) throws Exception {
        JsonNode problem =
                assertProblem(
                        getConversation(
                                accessToken,
                                conversationId,
                                String.class
                        ),
                        HttpStatus.NOT_FOUND,
                        "CONVERSATION_NOT_FOUND"
                );

        assertHiddenProblem(problem);
    }

    private void assertHiddenMessages(
            String accessToken,
            long conversationId
    ) throws Exception {
        JsonNode problem =
                assertProblem(
                        getMessages(
                                accessToken,
                                conversationId,
                                null,
                                null,
                                String.class
                        ),
                        HttpStatus.NOT_FOUND,
                        "CONVERSATION_NOT_FOUND"
                );

        assertHiddenProblem(problem);
    }

    private void assertHiddenProblem(
            JsonNode problem
    ) {
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

    private void assertNotFoundWithMalformedCursor(
            String accessToken,
            long conversationId
    ) throws Exception {
        JsonNode problem =
                assertProblem(
                        getMessages(
                                accessToken,
                                conversationId,
                                null,
                                "malformed",
                                String.class
                        ),
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
                )
        );
    }

    private void assertInvalidConversationQuery(
            ResponseEntity<String> response
    ) throws Exception {
        JsonNode problem =
                assertProblem(
                        response,
                        HttpStatus.BAD_REQUEST,
                        "INVALID_CONVERSATION_QUERY"
                );

        assertEquals(
                "Invalid conversation query",
                problem.path("title").asText()
        );
    }

    private <T> ResponseEntity<T> getConversation(
            String accessToken,
            long conversationId,
            Class<T> responseType
    ) {
        HttpHeaders headers =
                accessToken == null
                        ? new HttpHeaders()
                        : bearerHeaders(accessToken);

        return restTemplate.exchange(
                "/api/v1/conversations/{conversationId}",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                responseType,
                conversationId
        );
    }

    private <T> ResponseEntity<T> getMessages(
            String accessToken,
            long conversationId,
            Integer limit,
            String cursor,
            Class<T> responseType
    ) {
        HttpHeaders headers =
                accessToken == null
                        ? new HttpHeaders()
                        : bearerHeaders(accessToken);

        StringBuilder uri = new StringBuilder(
                "/api/v1/conversations/"
                        + conversationId
                        + "/messages"
        );

        boolean hasQuery = false;

        if (limit != null) {
            uri.append(hasQuery ? "&" : "?");
            uri.append("limit=").append(limit);
            hasQuery = true;
        }

        if (cursor != null) {
            uri.append(hasQuery ? "&" : "?");
            uri.append("cursor=")
                    .append(UriUtils.encodeQueryParam(
                            cursor,
                            StandardCharsets.UTF_8
                    ));
        }

        return restTemplate.exchange(
                uri.toString(),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                responseType
        );
    }

    private static List<Long> sequences(
            ConversationMessagesResponse response
    ) {
        return response.items().stream()
                .map(
                        ConversationMessageResponse
                                ::sequenceNo
                )
                .toList();
    }

    private static List<Long> messageIds(
            ConversationMessagesResponse response
    ) {
        return response.items().stream()
                .map(item ->
                        Long.parseLong(
                                item.messageId()
                        )
                )
                .toList();
    }

    private static <T extends Comparable<? super T>>
    List<T> mergeSorted(
            List<T> first,
            List<T> second,
            List<T> third
    ) {
        return Stream.concat(
                Stream.concat(
                        first.stream(),
                        second.stream()
                ),
                third.stream()
        ).sorted().toList();
    }

    private void insertInternalMessage(
            long id,
            long tenantId,
            long conversationId,
            long sequenceNo,
            String role,
            String content
    ) {
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
                    status,
                    created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, 'TEXT', 'COMPLETED',
                        CURRENT_TIMESTAMP(3))
                """,
                id,
                tenantId,
                conversationId,
                sequenceNo,
                role,
                content
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
}
