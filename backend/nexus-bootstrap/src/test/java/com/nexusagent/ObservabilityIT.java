package com.nexusagent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.common.observability.RequestCorrelation;
import com.nexusagent.common.security.CurrentActorProvider;
import com.nexusagent.conversation.api.StreamConversationTurnRequest;
import com.nexusagent.identity.spi.AccessTokenIssuer;
import com.nexusagent.identity.spi.IssuedAccessToken;
import com.nexusagent.identity.spi.TokenSubject;
import com.nexusagent.model.api.ChatModelFinishReason;
import com.nexusagent.model.api.ChatModelGateway;
import com.nexusagent.model.api.ChatModelRequest;
import com.nexusagent.model.api.ChatModelStreamEvent;
import com.nexusagent.model.api.ChatModelStreamHandler;
import com.nexusagent.model.api.ChatTokenUsage;
import com.nexusagent.observability.ThreadLocalRequestCorrelationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability
        .AutoConfigureObservability;
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

import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 MySQL + HTTP + SSE + Prometheus 的可观测性端到端验证：
 * 关联 ID 生成/回传/替换、审计持久化、异步 worker 传播、
 * Prometheus 权限矩阵与抓取内容的去标识化。
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
@AutoConfigureObservability
@Import(ObservabilityIT.GatewayTestConfiguration.class)
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
class ObservabilityIT {

    private static final AtomicLong FIXTURE_IDS =
            new AtomicLong(600_000L);

    private static final Pattern CORRELATION_ID_PATTERN =
            Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static final String REQUEST_ID_HEADER =
            "X-Request-Id";

    private static final String TRACE_ID_HEADER =
            "X-Trace-Id";

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
    void shouldGenerateCorrelationWhenHeadersMissing() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        "/actuator/health",
                        String.class
                );

        assertEquals(HttpStatus.OK, response.getStatusCode());

        String requestId = response.getHeaders()
                .getFirst(REQUEST_ID_HEADER);
        String traceId = response.getHeaders()
                .getFirst(TRACE_ID_HEADER);

        assertNotNull(requestId);
        assertNotNull(traceId);
        assertTrue(
                CORRELATION_ID_PATTERN.matcher(requestId).matches(),
                "requestId must be valid: " + requestId
        );
        assertTrue(
                CORRELATION_ID_PATTERN.matcher(traceId).matches(),
                "traceId must be valid: " + traceId
        );

        // 缺少 traceId 时默认使用新生成的 requestId。
        assertEquals(requestId, traceId);
    }

    @Test
    void shouldEchoValidHeadersAndPersistThemInAudit() {
        Fixture fixture = insertFixture();

        gateway.enqueue(successScript("Hello"));

        ResponseEntity<String> streamed = streamTurn(
                issueToken(fixture, "MEMBER"),
                fixture.conversationId(),
                "Question",
                "req-audit-1",
                "trace-audit-1"
        );

        assertEquals(HttpStatus.OK, streamed.getStatusCode());
        assertTrue(requireBody(streamed).contains("event:completed"));

        assertEquals(
                "req-audit-1",
                streamed.getHeaders().getFirst(REQUEST_ID_HEADER)
        );
        assertEquals(
                "trace-audit-1",
                streamed.getHeaders().getFirst(TRACE_ID_HEADER)
        );

        // 数据库审计中的关联字段与响应 header 完全一致。
        List<AuditCorrelation> audits =
                readAuditCorrelations(fixture.tenantId());

        assertFalse(audits.isEmpty());

        for (AuditCorrelation audit : audits) {
            assertEquals("req-audit-1", audit.requestId());
            assertEquals("trace-audit-1", audit.traceId());
        }
    }

    @Test
    void shouldReplaceInvalidHeadersWithoutLeakingThem() {
        Fixture fixture = insertFixture();

        gateway.enqueue(successScript("Hello"));

        String invalidRequestId = "bad id!";
        String invalidTraceId = "trace bad";

        // 捕获日志：恶意串不得进入任何日志输出。
        Logger root = (Logger) LoggerFactory.getLogger(
                Logger.ROOT_LOGGER_NAME
        );

        ListAppender<ILoggingEvent> appender =
                new ListAppender<>();

        appender.start();
        root.addAppender(appender);

        ResponseEntity<String> streamed;

        try {
            streamed = streamTurn(
                    issueToken(fixture, "MEMBER"),
                    fixture.conversationId(),
                    "Question",
                    invalidRequestId,
                    invalidTraceId
            );
        } finally {
            root.detachAppender(appender);
        }

        assertEquals(HttpStatus.OK, streamed.getStatusCode());

        String responseRequestId = streamed.getHeaders()
                .getFirst(REQUEST_ID_HEADER);
        String responseTraceId = streamed.getHeaders()
                .getFirst(TRACE_ID_HEADER);

        // 非法 header 被替换为合法 ID。
        assertNotEquals(invalidRequestId, responseRequestId);
        assertNotEquals(invalidTraceId, responseTraceId);
        assertTrue(
                CORRELATION_ID_PATTERN
                        .matcher(responseRequestId)
                        .matches()
        );
        assertTrue(
                CORRELATION_ID_PATTERN
                        .matcher(responseTraceId)
                        .matches()
        );

        // 非法 traceId 被替换为采用后的 requestId。
        assertEquals(responseRequestId, responseTraceId);

        String body = requireBody(streamed);

        assertFalse(body.contains(invalidRequestId));
        assertFalse(body.contains(invalidTraceId));

        // 审计不包含恶意串。
        List<AuditCorrelation> audits =
                readAuditCorrelations(fixture.tenantId());

        for (AuditCorrelation audit : audits) {
            assertFalse(audit.requestId().contains(invalidRequestId));
            assertFalse(audit.traceId().contains(invalidTraceId));
        }

        // 日志不包含恶意串。
        String logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", String::concat);

        assertFalse(logs.contains(invalidRequestId));
        assertFalse(logs.contains(invalidTraceId));
    }

    @Test
    void shouldPropagateCorrelationToWorkerAndAudit() {
        Fixture fixture = insertFixture();

        gateway.enqueue(successScript("Hello"));

        ResponseEntity<String> streamed = streamTurn(
                issueToken(fixture, "MEMBER"),
                fixture.conversationId(),
                "Question",
                "req-worker-1",
                "trace-worker-1"
        );

        assertEquals(HttpStatus.OK, streamed.getStatusCode());
        assertTrue(requireBody(streamed).contains("event:completed"));

        assertEquals(
                "req-worker-1",
                streamed.getHeaders().getFirst(REQUEST_ID_HEADER)
        );
        assertEquals(
                "trace-worker-1",
                streamed.getHeaders().getFirst(TRACE_ID_HEADER)
        );

        // worker 线程观察到的关联必须与请求线程一致。
        assertEquals(1, gateway.correlations().size());

        RequestCorrelation workerCorrelation =
                gateway.correlations().get(0);

        assertNotNull(workerCorrelation);
        assertEquals("req-worker-1", workerCorrelation.requestId());
        assertEquals("trace-worker-1", workerCorrelation.traceId());

        // PREPARED/COMPLETED 审计中的 requestId 必须一致。
        List<AuditCorrelation> audits =
                readAuditCorrelations(fixture.tenantId());

        assertTrue(audits.stream().anyMatch(
                audit -> "CONVERSATION_TURN_PREPARED"
                        .equals(audit.action())
        ));

        for (AuditCorrelation audit : audits) {
            assertEquals("req-worker-1", audit.requestId());
            assertEquals("trace-worker-1", audit.traceId());
        }
    }

    @Test
    void shouldEnforcePrometheusPermissions() {
        Fixture fixture = insertFixture();

        // 匿名：401。
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                restTemplate.getForEntity(
                        "/actuator/prometheus",
                        String.class
                ).getStatusCode()
        );

        // MEMBER：403。
        assertEquals(
                HttpStatus.FORBIDDEN,
                getWithToken(
                        "/actuator/prometheus",
                        issueToken(fixture, "MEMBER")
                ).getStatusCode()
        );

        // ADMIN：200。
        ResponseEntity<String> adminResponse =
                getWithToken(
                        "/actuator/prometheus",
                        issueToken(fixture, "ADMIN")
                );

        assertEquals(HttpStatus.OK, adminResponse.getStatusCode());
        assertTrue(
                requireBody(adminResponse).contains("nexus_")
        );
    }

    @Test
    void shouldExposeMetricsWithoutCardinalityLeak() {
        Fixture fixture = insertFixture();

        gateway.enqueue(successScript("Hello"));

        ResponseEntity<String> streamed = streamTurn(
                issueToken(fixture, "MEMBER"),
                fixture.conversationId(),
                "Question",
                null,
                null
        );

        assertEquals(HttpStatus.OK, streamed.getStatusCode());
        assertTrue(requireBody(streamed).contains("event:completed"));

        String scrape = requireBody(
                getWithToken(
                        "/actuator/prometheus",
                        issueToken(fixture, "ADMIN")
                )
        );

        assertTrue(
                scrape.contains(
                        "nexus_conversation_turn_seconds_count"
                ),
                "turn timer must be exposed"
        );
        assertTrue(
                scrape.contains(
                        "nexus_model_call_seconds_count"
                ),
                "model call timer must be exposed"
        );
        assertTrue(
                scrape.contains(
                        "http_server_requests_seconds_bucket"
                ),
                "HTTP histogram buckets must be exposed"
        );
        assertTrue(
                scrape.contains(
                        "nexus_conversation_turn_seconds_bucket"
                ),
                "turn histogram buckets must be exposed"
        );
        assertTrue(
                scrape.contains(
                        "nexus_model_call_seconds_bucket"
                ),
                "model call histogram buckets must be exposed"
        );
        assertTrue(
                scrape.contains(
                        "nexus_sse_connections_active"
                ),
                "sse active gauge must be exposed"
        );

        // 抓取内容绝不包含租户/用户/请求级标识。
        assertFalse(scrape.contains("tenantId"));
        assertFalse(scrape.contains("conversationId"));
        assertFalse(scrape.contains("userId"));
        assertFalse(scrape.contains("requestId"));
        assertFalse(scrape.contains("traceId"));
    }

    // ---------------------------------------------------------------
    // Gateway double
    // ---------------------------------------------------------------

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

        private final Queue<GatewayScript> scripts =
                new ConcurrentLinkedQueue<>();
        private final List<RequestCorrelation> correlations =
                new CopyOnWriteArrayList<>();

        QueuedChatModelGateway(
                CurrentActorProvider currentActorProvider
        ) {
            Objects.requireNonNull(currentActorProvider);
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
            // 在 worker 线程捕获请求关联，
            // 验证异步传播后与请求线程一致。
            correlations.add(
                    ThreadLocalRequestCorrelationContext
                            .currentOrNull()
            );

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
            correlations.clear();
        }

        List<RequestCorrelation> correlations() {
            return correlations;
        }
    }

    private static GatewayScript successScript(String text) {
        return (request, handler) -> {
            handler.onEvent(
                    new ChatModelStreamEvent.TextDelta(text)
            );
            handler.onEvent(
                    new ChatModelStreamEvent.Completed(
                            ChatModelFinishReason.STOP,
                            new ChatTokenUsage(5, 7)
                    )
            );
        };
    }

    // ---------------------------------------------------------------
    // Fixture and token helpers
    // ---------------------------------------------------------------

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
                conversationId,
                username
        );
    }

    private String issueToken(Fixture fixture, String role) {
        IssuedAccessToken token =
                accessTokenIssuer.issue(
                        new TokenSubject(
                                fixture.userId(),
                                fixture.tenantId(),
                                fixture.username(),
                                List.of(role)
                        )
                );

        return token.value();
    }

    // ---------------------------------------------------------------
    // HTTP helpers
    // ---------------------------------------------------------------

    private ResponseEntity<String> streamTurn(
            String accessToken,
            long conversationId,
            String content,
            String requestId,
            String traceId
    ) {
        HttpHeaders headers = new HttpHeaders();

        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        if (requestId != null) {
            headers.set(REQUEST_ID_HEADER, requestId);
        }
        if (traceId != null) {
            headers.set(TRACE_ID_HEADER, traceId);
        }

        // 客户端 UriTemplate 变量名同样不得包含租户级标识：
        // 客户端观测的 uri 标签会原样携带模板变量名。
        return restTemplate.exchange(
                "/api/v1/conversations/{id}/turns:stream",
                HttpMethod.POST,
                new HttpEntity<>(
                        new StreamConversationTurnRequest(content),
                        headers
                ),
                String.class,
                conversationId
        );
    }

    private ResponseEntity<String> getWithToken(
            String path,
            String accessToken
    ) {
        HttpHeaders headers = new HttpHeaders();

        headers.setBearerAuth(accessToken);

        return restTemplate.exchange(
                path,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
    }

    // ---------------------------------------------------------------
    // Database helpers
    // ---------------------------------------------------------------

    private List<AuditCorrelation> readAuditCorrelations(
            long tenantId
    ) {
        return jdbcTemplate.query(
                """
                SELECT
                    action,
                    request_id,
                    trace_id
                FROM audit_logs
                WHERE tenant_id = ?
                ORDER BY id
                """,
                (resultSet, rowNumber) ->
                        new AuditCorrelation(
                                resultSet.getString("action"),
                                resultSet.getString("request_id"),
                                resultSet.getString("trace_id")
                        ),
                tenantId
        );
    }

    private static <T> T requireBody(ResponseEntity<T> response) {
        T body = response.getBody();
        assertNotNull(body);
        return body;
    }

    private record Fixture(
            long tenantId,
            long userId,
            long conversationId,
            String username
    ) {
    }

    private record AuditCorrelation(
            String action,
            String requestId,
            String traceId
    ) {
    }
}
