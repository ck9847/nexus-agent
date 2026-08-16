package com.nexusagent.model.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.model.api.ChatModelGateway;
import com.nexusagent.model.internal.ChatModelGatewayRegistry;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAiCompatibleConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            OpenAiCompatibleConfiguration.class,
                            ObjectMapperConfig.class,
                            ChatModelGatewayRegistry.class
                    )
                    .withBean(
                            CircuitBreakerRegistry.class,
                            CircuitBreakerRegistry::ofDefaults
                    );

    @Test
    void shouldNotRegisterGatewayWhenDisabled() {
        contextRunner
                .withPropertyValues(
                        "nexus.model.openai.enabled=false"
                )
                .run(context -> {
                    assertThat(context)
                            .doesNotHaveBean(
                                    ChatModelGateway.class
                            );
                    assertThat(context)
                            .doesNotHaveBean(RestClient.class);
                });
    }

    @Test
    void shouldCreateGatewayWhenEnabledWithKey() {
        contextRunner
                .withPropertyValues(
                        "nexus.model.openai.enabled=true",
                        "nexus.model.openai.api-key=test-key"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(
                            ChatModelGateway.class
                    );

                    ChatModelGateway gateway =
                            context.getBean(
                                    ChatModelGateway.class
                            );

                    assertEquals(
                            AgentModelProvider.OPENAI,
                            gateway.provider()
                    );
                });
    }

    @Test
    void shouldFailToStartWhenEnabledWithoutKey() {
        contextRunner
                .withPropertyValues(
                        "nexus.model.openai.enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(
                                    IllegalArgumentException
                                            .class
                            );
                });
    }

    @Test
    void shouldExposeOpenAiGatewayThroughRegistry() {
        contextRunner
                .withPropertyValues(
                        "nexus.model.openai.enabled=true",
                        "nexus.model.openai.api-key=test-key"
                )
                .run(context -> {
                    ChatModelGatewayRegistry registry =
                            context.getBean(
                                    ChatModelGatewayRegistry.class
                            );

                    ChatModelGateway gateway =
                            registry.requireGateway(
                                    AgentModelProvider.OPENAI
                            );

                    assertEquals(
                            AgentModelProvider.OPENAI,
                            gateway.provider()
                    );
                });
    }

    @Test
    void shouldNotFollowRedirects() throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress("localhost", 0),
                0
        );

        server.createContext(
                "/redirect-me",
                exchange -> {
                    exchange.getResponseHeaders()
                            .set("Location", "/target");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                }
        );

        server.createContext(
                "/target",
                exchange -> {
                    byte[] body = "ok".getBytes(
                            StandardCharsets.UTF_8
                    );
                    exchange.sendResponseHeaders(
                            200,
                            body.length
                    );
                    try (OutputStream out =
                                 exchange.getResponseBody()) {
                        out.write(body);
                    }
                }
        );

        server.start();

        try {
            int port = server.getAddress().getPort();

            contextRunner
                    .withPropertyValues(
                            "nexus.model.openai.enabled=true",
                            "nexus.model.openai.api-key=test-key"
                    )
                    .run(context -> {
                        RestClient restClient =
                                context.getBean(RestClient.class);

                        ResponseEntity<Void> response =
                                restClient.get()
                                        .uri("http://localhost:"
                                                + port
                                                + "/redirect-me")
                                        .retrieve()
                                        .toBodilessEntity();

                        assertEquals(
                                302,
                                response.getStatusCode().value()
                        );
                    });
        } finally {
            server.stop(0);
        }
    }

    @Configuration
    static class ObjectMapperConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
