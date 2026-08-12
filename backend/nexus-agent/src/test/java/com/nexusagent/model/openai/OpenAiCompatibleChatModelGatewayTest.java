package com.nexusagent.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.model.api.ChatModelException;
import com.nexusagent.model.api.ChatModelMessage;
import com.nexusagent.model.api.ChatModelOptions;
import com.nexusagent.model.api.ChatModelRequest;
import com.nexusagent.model.api.ChatModelStreamEvent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleChatModelGatewayTest {

    private static final String SSE = "text/event-stream";

    private static final String TEXT_SSE = """
            data: {"choices":[{"index":0,"delta":{"content":"Hello"}}]}

            data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: {"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":2}}

            data: [DONE]
            """;

    private static final String TOOL_CALL_SSE = """
            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"search","arguments":""}}]}}]}

            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"q\\":\\"a\\"}"}}]}}]}

            data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

            data: {"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":2}}

            data: [DONE]
            """;

    private final List<RequestRecord> requests = new ArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpServer server;
    private OpenAiCompatibleChatModelGateway gateway;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(
                new InetSocketAddress("localhost", 0),
                0
        );
        server.start();
        gateway = gateway("http://localhost:"
                + server.getAddress().getPort()
                + "/v1");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void shouldSendPostToChatCompletionsPath() {
        sseContext("/v1/chat/completions", TEXT_SSE);

        stream();

        assertEquals("POST", requests.get(0).method);
        assertEquals(
                "/v1/chat/completions",
                requests.get(0).path
        );
    }

    @Test
    void shouldSendAuthorizationBearerHeader() {
        sseContext("/v1/chat/completions", TEXT_SSE);

        stream();

        assertEquals(
                "Bearer test-api-key",
                requests.get(0).authorization
        );
    }

    @Test
    void shouldSendJsonContentTypeAndSseAccept() {
        sseContext("/v1/chat/completions", TEXT_SSE);

        stream();

        assertTrue(
                requests.get(0).contentType
                        .startsWith("application/json")
        );
        assertTrue(
                requests.get(0).accept
                        .contains("text/event-stream")
        );
    }

    @Test
    void shouldSendRequestBodyWithModelMessagesStream() throws Exception {
        sseContext("/v1/chat/completions", TEXT_SSE);

        stream();

        JsonNode body = objectMapper.readTree(
                requests.get(0).body
        );

        assertEquals("gpt-5", body.path("model").asText());
        assertTrue(body.path("stream").asBoolean());
        assertTrue(body.path("messages").isArray());
        assertTrue(body.path("messages").size() >= 1);
    }

    @Test
    void shouldEmitTextDeltasFromSse() {
        sseContext("/v1/chat/completions", TEXT_SSE);

        List<ChatModelStreamEvent> events = stream();

        ChatModelStreamEvent.TextDelta delta =
                assertInstanceOf(
                        ChatModelStreamEvent.TextDelta.class,
                        events.get(0)
                );
        assertEquals("Hello", delta.text());

        assertInstanceOf(
                ChatModelStreamEvent.Completed.class,
                events.get(1)
        );
    }

    @Test
    void shouldEmitToolCallDeltasFromSse() {
        sseContext("/v1/chat/completions", TOOL_CALL_SSE);

        List<ChatModelStreamEvent> events = stream();

        ChatModelStreamEvent.ToolCallDelta first =
                assertInstanceOf(
                        ChatModelStreamEvent.ToolCallDelta.class,
                        events.get(0)
                );
        assertEquals(0, first.index());
        assertEquals("call_1", first.callIdFragment());
        assertEquals("search", first.nameFragment());
        assertNull(first.argumentsFragment());

        ChatModelStreamEvent.ToolCallDelta second =
                assertInstanceOf(
                        ChatModelStreamEvent.ToolCallDelta.class,
                        events.get(1)
                );
        assertEquals(0, second.index());
        assertNull(second.callIdFragment());
        assertNull(second.nameFragment());
        assertEquals("{\"q\":\"a\"}", second.argumentsFragment());

        assertInstanceOf(
                ChatModelStreamEvent.Completed.class,
                events.get(2)
        );
    }

    @Test
    void shouldClassify401AsAuthentication() {
        respondContext(
                "/v1/chat/completions",
                401,
                "application/json",
                "{\"error\":{\"message\":\"invalid api key\"}}"
        );

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                this::stream
        );

        assertEquals(
                ChatModelErrorCategory.AUTHENTICATION,
                exception.category()
        );
        assertFalse(exception.retryable());
        assertFalse(
                exception.getMessage().contains("invalid")
        );
    }

    @Test
    void shouldClassify429AsRateLimit() {
        respondContext(
                "/v1/chat/completions",
                429,
                "application/json",
                "{\"error\":{\"message\":\"rate limited\"}}"
        );

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                this::stream
        );

        assertEquals(
                ChatModelErrorCategory.RATE_LIMIT,
                exception.category()
        );
        assertTrue(exception.retryable());
    }

    @Test
    void shouldClassify503AsProviderUnavailable() {
        respondContext(
                "/v1/chat/completions",
                503,
                "application/json",
                "{\"error\":{\"message\":\"unavailable\"}}"
        );

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                this::stream
        );

        assertEquals(
                ChatModelErrorCategory.PROVIDER_UNAVAILABLE,
                exception.category()
        );
        assertTrue(exception.retryable());
    }

    @Test
    void shouldRejectNonSseContentType() {
        respondContext(
                "/v1/chat/completions",
                200,
                "application/json",
                "{\"choices\":[]}"
        );

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                this::stream
        );

        assertEquals(
                ChatModelErrorCategory.MALFORMED_RESPONSE,
                exception.category()
        );
        assertEquals(
                "Chat model provider did not return an SSE stream",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectMalformedSseJson() {
        sseContext(
                "/v1/chat/completions",
                "data: not-json\n\n"
        );

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                this::stream
        );

        assertEquals(
                ChatModelErrorCategory.MALFORMED_RESPONSE,
                exception.category()
        );
    }

    @Test
    void shouldReportStreamInterruptedWhenDoneMissing() {
        sseContext(
                "/v1/chat/completions",
                """
                        data: {"choices":[{"index":0,"delta":{"content":"Hello"}}]}

                        data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                        data: {"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":2}}
                        """
        );

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                this::stream
        );

        assertEquals(
                ChatModelErrorCategory.STREAM_INTERRUPTED,
                exception.category()
        );
        assertTrue(exception.retryable());
    }

    @Test
    void shouldReportInterruptedConnection() {
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    byte[] partial = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"}}]}\n\n"
                            .getBytes(StandardCharsets.UTF_8);

                    exchange.getResponseHeaders()
                            .set("Content-Type", SSE);
                    exchange.sendResponseHeaders(
                            200,
                            partial.length + 10_000
                    );

                    try (OutputStream out =
                                 exchange.getResponseBody()) {
                        out.write(partial);
                    }
                }
        );

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                this::stream
        );

        assertEquals(
                ChatModelErrorCategory.CONNECTION,
                exception.category()
        );
        assertTrue(exception.retryable());
    }

    @Test
    void shouldNotFollowRedirects() {
        AtomicInteger redirectHits = new AtomicInteger();

        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    exchange.getResponseHeaders()
                            .set("Location", "/redirected");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                }
        );

        server.createContext(
                "/redirected",
                exchange -> {
                    redirectHits.incrementAndGet();
                    respond(exchange, 200, SSE, TEXT_SSE);
                }
        );

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                this::stream
        );

        assertEquals(
                ChatModelErrorCategory.MALFORMED_RESPONSE,
                exception.category()
        );
        assertEquals(0, redirectHits.get());
    }

    @Test
    void shouldNotExposeApiKeyInException() {
        respondContext(
                "/v1/chat/completions",
                401,
                "application/json",
                "{\"error\":{\"message\":\"test-api-key invalid\"}}"
        );

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                this::stream
        );

        assertFalse(
                exception.getMessage().contains("test-api-key")
        );
        assertFalse(
                exception.getMessage().contains(
                        "test-api-key invalid"
                )
        );
    }

    @Test
    void shouldClassifyConnectionFailureWithoutRealInternet() {
        OpenAiCompatibleChatModelGateway unreachable =
                gateway("http://127.0.0.1:1/v1");

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> unreachable.stream(
                        request(),
                        event -> {
                        }
                )
        );

        assertEquals(
                ChatModelErrorCategory.CONNECTION,
                exception.category()
        );
        assertTrue(exception.retryable());
        assertFalse(
                exception.getMessage().contains("test-api-key")
        );
    }

    private void sseContext(String path, String body) {
        respondContext(path, 200, SSE, body);
    }

    private void respondContext(
            String path,
            int status,
            String contentType,
            String body
    ) {
        server.createContext(
                path,
                exchange -> {
                    record(exchange);
                    respond(exchange, status, contentType, body);
                }
        );
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            String contentType,
            String body
    ) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        if (contentType != null) {
            exchange.getResponseHeaders()
                    .set("Content-Type", contentType);
        }

        exchange.sendResponseHeaders(status, bytes.length);

        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void record(HttpExchange exchange)
            throws IOException {
        RequestRecord record = new RequestRecord();

        record.method = exchange.getRequestMethod();
        record.path = exchange.getRequestURI().getPath();
        record.authorization = exchange.getRequestHeaders()
                .getFirst("Authorization");
        record.contentType = exchange.getRequestHeaders()
                .getFirst("Content-Type");
        record.accept = exchange.getRequestHeaders()
                .getFirst("Accept");
        record.body = new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8
        );

        requests.add(record);
    }

    private List<ChatModelStreamEvent> stream() {
        List<ChatModelStreamEvent> events = new ArrayList<>();
        gateway.stream(request(), events::add);
        return events;
    }

    private static ChatModelRequest request() {
        return new ChatModelRequest(
                "gpt-5",
                "You are a support agent.",
                ChatModelOptions.defaults(),
                List.of(ChatModelMessage.user("Hello")),
                List.of()
        );
    }

    private static OpenAiCompatibleChatModelGateway gateway(
            String baseUrl
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(10));

        RestClient restClient = RestClient.builder()
                .requestFactory(factory)
                .build();

        OpenAiCompatibleProperties properties =
                new OpenAiCompatibleProperties(
                        true,
                        URI.create(baseUrl),
                        "test-api-key",
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(10),
                        8192
                );

        return new OpenAiCompatibleChatModelGateway(
                restClient,
                properties,
                new JacksonOpenAiChatCompletionRequestMapper(
                        new ObjectMapper()
                ),
                new JacksonOpenAiChatCompletionStreamDecoder(
                        new ObjectMapper()
                ),
                new OpenAiCompatibleErrorMapper()
        );
    }

    private static final class RequestRecord {
        String method;
        String path;
        String authorization;
        String contentType;
        String accept;
        String body;
    }
}
