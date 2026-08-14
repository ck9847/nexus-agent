package com.nexusagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 验证生产运行时的健康探针暴露策略：
 * <ul>
 *   <li>匿名可访问 {@code /actuator/health} 及其 liveness/readiness 子路径；</li>
 *   <li>其它 Actuator 端点不对匿名用户开放（{@code /actuator/env}）；</li>
 *   <li>未列入 exposure 的端点不被公开暴露（{@code /actuator/metrics}）。</li>
 * </ul>
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "nexus.security.jwt.secret="
                        + "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0"
                        + "NTY3ODlhYmNkZWY=",
                "nexus.model.openai.enabled=false"
        }
)
class ProductionRuntimeIT {

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
    private ObjectMapper objectMapper;

    @Test
    void shouldExposeHealthProbesAnonymously() {
        assertHealthUp("/actuator/health");
        assertHealthUp("/actuator/health/liveness");
        assertHealthUp("/actuator/health/readiness");
    }

    @Test
    void shouldNotExposeSensitiveActuatorEndpoints() {
        // /actuator/env 不对匿名用户开放。
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                get("/actuator/env").getStatusCode()
        );

        // /actuator/metrics 未被公开暴露：安全过滤器在
        // Actuator 暴露检查之前即拦截，匿名请求同样不可达。
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                get("/actuator/metrics").getStatusCode()
        );
    }

    private void assertHealthUp(String path) {
        ResponseEntity<String> response = get(path);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        String body = response.getBody();
        assertNotNull(body);

        try {
            JsonNode root = objectMapper.readTree(body);

            assertEquals(
                    "UP",
                    root.get("status").asText()
            );
        } catch (Exception exception) {
            throw new AssertionError(
                    "Health response was not valid JSON: " + body,
                    exception
            );
        }
    }

    private ResponseEntity<String> get(String path) {
        return restTemplate.getForEntity(path, String.class);
    }
}
