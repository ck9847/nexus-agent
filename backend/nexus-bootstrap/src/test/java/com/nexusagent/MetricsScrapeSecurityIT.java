package com.nexusagent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexusagent.identity.spi.AccessTokenIssuer;
import com.nexusagent.identity.spi.IssuedAccessToken;
import com.nexusagent.identity.spi.TokenSubject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability
        .AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prometheus 指标抓取专用机器身份的端到端安全矩阵：
 * 未认证 401、错误 Basic 密码 401、正确 metrics 用户 200、
 * metrics 用户无法访问业务 API、MEMBER JWT 403、
 * ADMIN JWT 保留访问能力；响应与日志绝不泄漏密码或
 * Authorization header 值。
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
@AutoConfigureObservability
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "nexus.security.jwt.secret="
                        + "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0"
                        + "NTY3ODlhYmNkZWY=",
                "nexus.model.openai.enabled=false",
                "nexus.observability.metrics-scrape.enabled=true",
                "nexus.observability.metrics-scrape"
                        + ".username=prometheus",
                "nexus.observability.metrics-scrape.password="
                        + "metrics-scrape-it-password-0123456789abcdef"
        }
)
class MetricsScrapeSecurityIT {

    private static final String METRICS_USERNAME =
            "prometheus";

    private static final String METRICS_PASSWORD =
            "metrics-scrape-it-password-0123456789abcdef";

    private static final String PROMETHEUS_PATH =
            "/actuator/prometheus";

    private static final long TENANT_ID = 990_001L;

    private static final long USER_ID = 990_002L;

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

    @Test
    void shouldEnforceMetricsScrapeIdentityMatrix() {
        Logger root = (Logger) LoggerFactory.getLogger(
                Logger.ROOT_LOGGER_NAME
        );

        ListAppender<ILoggingEvent> appender =
                new ListAppender<>();

        appender.start();
        root.addAppender(appender);

        List<ResponseEntity<String>> responses;

        try {
            // 未认证访问 Prometheus → 401。
            ResponseEntity<String> anonymous =
                    get(PROMETHEUS_PATH, new HttpHeaders());

            assertEquals(
                    HttpStatus.UNAUTHORIZED,
                    anonymous.getStatusCode()
            );

            // 错误 Basic 密码 → 401。
            ResponseEntity<String> wrongPassword =
                    get(
                            PROMETHEUS_PATH,
                            basicHeaders(
                                    METRICS_USERNAME,
                                    "wrong-password-0123456789abcdef"
                            )
                    );

            assertEquals(
                    HttpStatus.UNAUTHORIZED,
                    wrongPassword.getStatusCode()
            );

            // 正确 metrics 用户 → 200，抓取体包含 nexus_ 指标。
            ResponseEntity<String> scraped =
                    get(
                            PROMETHEUS_PATH,
                            basicHeaders(
                                    METRICS_USERNAME,
                                    METRICS_PASSWORD
                            )
                    );

            assertEquals(HttpStatus.OK, scraped.getStatusCode());
            assertTrue(
                    requireBody(scraped).contains("nexus_"),
                    "scrape body must expose nexus_ metrics"
            );

            // metrics 用户访问业务 API → 401（主链只接受 JWT）。
            ResponseEntity<String> apiAsMetricsUser =
                    get(
                            "/api/v1/tickets",
                            basicHeaders(
                                    METRICS_USERNAME,
                                    METRICS_PASSWORD
                            )
                    );

            assertEquals(
                    HttpStatus.UNAUTHORIZED,
                    apiAsMetricsUser.getStatusCode()
            );

            // MEMBER JWT 访问 metrics → 403（已认证但无权限）。
            ResponseEntity<String> memberJwt =
                    get(
                            PROMETHEUS_PATH,
                            bearerHeaders(issueToken("MEMBER"))
                    );

            assertEquals(
                    HttpStatus.FORBIDDEN,
                    memberJwt.getStatusCode()
            );

            // ADMIN JWT 保留访问能力 → 200。
            ResponseEntity<String> adminJwt =
                    get(
                            PROMETHEUS_PATH,
                            bearerHeaders(issueToken("ADMIN"))
                    );

            assertEquals(HttpStatus.OK, adminJwt.getStatusCode());
            assertTrue(
                    requireBody(adminJwt).contains("nexus_")
            );

            responses = List.of(
                    anonymous,
                    wrongPassword,
                    scraped,
                    apiAsMetricsUser,
                    memberJwt,
                    adminJwt
            );
        } finally {
            root.detachAppender(appender);
        }

        // 响应绝不泄漏密码。
        for (ResponseEntity<String> response : responses) {
            assertResponseBodyDoesNotLeak(response);
        }

        // 日志绝不泄漏密码或 Authorization header 值。
        assertLogsDoNotLeakCredentials(appender);
    }

    private ResponseEntity<String> get(
            String path,
            HttpHeaders headers
    ) {
        return restTemplate.exchange(
                path,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
    }

    private static HttpHeaders basicHeaders(
            String username,
            String password
    ) {
        HttpHeaders headers = new HttpHeaders();

        headers.setBasicAuth(username, password);

        return headers;
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();

        headers.setBearerAuth(token);

        return headers;
    }

    private String issueToken(String role) {
        IssuedAccessToken token =
                accessTokenIssuer.issue(
                        new TokenSubject(
                                USER_ID,
                                TENANT_ID,
                                "metrics-scrape-it-user",
                                List.of(role)
                        )
                );

        return token.value();
    }

    private static void assertResponseBodyDoesNotLeak(
            ResponseEntity<String> response
    ) {
        String body = response.getBody();

        if (body != null) {
            assertFalse(
                    body.contains(METRICS_PASSWORD),
                    "response body must not leak the password"
            );
        }
    }

    private static void assertLogsDoNotLeakCredentials(
            ListAppender<ILoggingEvent> appender
    ) {
        String logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", String::concat);

        assertFalse(
                logs.contains(METRICS_PASSWORD),
                "logs must not leak the metrics scrape password"
        );

        String authorizationHeaderValue =
                "Basic " + Base64.getEncoder().encodeToString(
                        (METRICS_USERNAME + ":" + METRICS_PASSWORD)
                                .getBytes(StandardCharsets.UTF_8)
                );

        assertFalse(
                logs.contains(authorizationHeaderValue),
                "logs must not leak the Authorization header value"
        );
    }

    private static String requireBody(ResponseEntity<String> response) {
        String body = response.getBody();

        assertNotNull(body);

        return body;
    }
}
