package com.nexusagent;

import com.nexusagent.conversation.api.StreamConversationTurnRequest;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端验证会话轮次流式端点的安全规则与装配。
 *
 * <p>覆盖第八批的 POST matcher
 * {@code /api/v1/conversations/{conversationId}/turns:stream}：
 * 无 Token 必须在进入 Controller 之前被拦为 401。
 *
 * <p>认证后的 MEMBER / ADMIN 都能到达 Controller；对不存在的
 * 会话，worker 在 prepare 阶段失败并转为安全 {@code error}
 * 事件 —— HTTP 仍是 200（SseEmitter 已返回），只在 SSE 流上
 * 暴露 {@code CONVERSATION_NOT_FOUND}。该路径同时隐式验证了
 * 第六、七批：真实 {@code conversationTurnStreamExecutor} bean
 * 从 yaml 配置装配，且 {@code DelegatingSecurityContextAsyncTaskExecutor}
 * 把请求线程的 JWT {@code SecurityContext} 传播给了 worker
 * （否则 prepare 无法解析租户，会以 INTERNAL_ERROR 结束）。
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest(
        webEnvironment =
                SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = """
                nexus.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=
                """
)
class ConversationTurnStreamingSecurityIT {

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
    private AccessTokenIssuer accessTokenIssuer;

    @BeforeEach
    void configureRequestFactory() {
        restTemplate.getRestTemplate()
                .setRequestFactory(
                        new JdkClientHttpRequestFactory()
                );
    }

    @Test
    void shouldRequireAuthenticationBeforeStreaming() {
        ResponseEntity<String> unauthorized =
                streamTurn(
                        null,
                        999_000_000_000L,
                        "Hello",
                        String.class
                );

        assertEquals(
                HttpStatus.UNAUTHORIZED,
                unauthorized.getStatusCode()
        );
    }

    @Test
    void shouldAllowAuthenticatedMemberAndAdminAndStreamErrorForMissingConversation()
            throws Exception {
        TenantSession tenant =
                bootstrapAndLogin(
                        "conversation-turn-stream-auth-acme"
                );

        assertStreamErrorForMissingConversation(
                issueMemberToken(tenant)
        );
        assertStreamErrorForMissingConversation(
                tenant.adminAccessToken()
        );
    }

    private void assertStreamErrorForMissingConversation(
            String accessToken
    ) throws Exception {
        ResponseEntity<String> streamed =
                streamTurn(
                        accessToken,
                        999_000_000_000L,
                        "Hello",
                        String.class
                );

        assertEquals(
                HttpStatus.OK,
                streamed.getStatusCode()
        );

        assertAll(
                () -> assertTrue(
                        streamed.getHeaders()
                                .getContentType()
                                .isCompatibleWith(
                                        MediaType.TEXT_EVENT_STREAM
                                )
                ),
                () -> assertTrue(
                        requireBody(streamed)
                                .contains("event:error")
                ),
                () -> assertTrue(
                        requireBody(streamed)
                                .contains("CONVERSATION_NOT_FOUND")
                ),
                () -> assertTrue(
                        requireBody(streamed)
                                .contains("Conversation not found")
                ),
                () -> assertFalse(
                        requireBody(streamed)
                                .contains("event:started")
                ),
                () -> assertFalse(
                        requireBody(streamed)
                                .contains("event:delta")
                )
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
}
