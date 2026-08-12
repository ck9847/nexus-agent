package com.nexusagent.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusagent.model.api.ChatModelMessage;
import com.nexusagent.model.api.ChatModelOptions;
import com.nexusagent.model.api.ChatModelRequest;
import com.nexusagent.model.api.ChatModelRole;
import com.nexusagent.model.api.ChatModelToolCall;
import com.nexusagent.model.api.ChatToolDefinition;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JacksonOpenAiChatCompletionRequestMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JacksonOpenAiChatCompletionRequestMapper mapper =
            new JacksonOpenAiChatCompletionRequestMapper(
                    objectMapper
            );

    @Test
    void shouldMapModelName() {
        ObjectNode root = mapUserRequest();

        assertEquals(
                "gpt-5",
                root.get("model").asText()
        );
    }

    @Test
    void shouldMapSystemPromptAsDeveloperMessage() {
        ObjectNode root = mapUserRequest();

        JsonNode messages = root.path("messages");

        assertEquals(
                "developer",
                messages.get(0).path("role").asText()
        );
        assertEquals(
                "You are a support agent.",
                messages.get(0).path("content").asText()
        );
    }

    @Test
    void shouldMapUserMessage() {
        ObjectNode root = mapUserRequest();

        JsonNode userMessage = root.path("messages").get(1);

        assertEquals("user", userMessage.path("role").asText());
        assertEquals("Hello", userMessage.path("content").asText());
    }

    @Test
    void shouldMapAssistantMessage() {
        ChatModelMessage assistant =
                ChatModelMessage.assistant("Hi there");

        ObjectNode root = mapper.map(request(
                List.of(assistant),
                List.of()
        ));

        JsonNode assistantMessage = root.path("messages").get(1);

        assertEquals(
                "assistant",
                assistantMessage.path("role").asText()
        );
        assertEquals(
                "Hi there",
                assistantMessage.path("content").asText()
        );
    }

    @Test
    void shouldMapToolMessageWithToolCallId() {
        ChatModelMessage toolMessage = new ChatModelMessage(
                ChatModelRole.TOOL,
                "Ticket created",
                List.of(),
                "call-901"
        );

        ObjectNode root = mapper.map(request(
                List.of(toolMessage),
                List.of()
        ));

        JsonNode toolMessageJson = root.path("messages").get(1);

        assertEquals("tool", toolMessageJson.path("role").asText());
        assertEquals(
                "Ticket created",
                toolMessageJson.path("content").asText()
        );
        assertEquals(
                "call-901",
                toolMessageJson.path("tool_call_id").asText()
        );
    }

    @Test
    void shouldSetStreamTrue() {
        ObjectNode root = mapUserRequest();

        assertTrue(root.path("stream").asBoolean());
    }

    @Test
    void shouldSetStreamOptionsIncludeUsage() {
        ObjectNode root = mapUserRequest();

        assertTrue(
                root.path("stream_options")
                        .path("include_usage")
                        .asBoolean()
        );
    }

    @Test
    void shouldMapTemperature() {
        ChatModelOptions options = new ChatModelOptions(
                new BigDecimal("0.7"),
                null,
                null
        );

        ObjectNode root = mapper.map(request(
                options,
                List.of(ChatModelMessage.user("Hello")),
                List.of()
        ));

        assertEquals(
                new BigDecimal("0.7"),
                root.get("temperature").decimalValue()
        );
    }

    @Test
    void shouldMapTopP() {
        ChatModelOptions options = new ChatModelOptions(
                null,
                new BigDecimal("0.9"),
                null
        );

        ObjectNode root = mapper.map(request(
                options,
                List.of(ChatModelMessage.user("Hello")),
                List.of()
        ));

        assertEquals(
                new BigDecimal("0.9"),
                root.get("top_p").decimalValue()
        );
    }

    @Test
    void shouldMapMaxCompletionTokens() {
        ChatModelOptions options = new ChatModelOptions(
                null,
                null,
                2048
        );

        ObjectNode root = mapper.map(request(
                options,
                List.of(ChatModelMessage.user("Hello")),
                List.of()
        ));

        assertEquals(
                2048,
                root.get("max_completion_tokens").asInt()
        );
    }

    @Test
    void shouldNotEmitLegacyMaxTokensField() {
        ChatModelOptions options = new ChatModelOptions(
                null,
                null,
                2048
        );

        ObjectNode root = mapper.map(request(
                options,
                List.of(ChatModelMessage.user("Hello")),
                List.of()
        ));

        assertFalse(root.has("max_tokens"));
        assertTrue(root.has("max_completion_tokens"));
    }

    @Test
    void shouldOmitConfigFieldsWhenUnset() {
        ObjectNode root = mapUserRequest();

        assertFalse(root.has("temperature"));
        assertFalse(root.has("top_p"));
        assertFalse(root.has("max_completion_tokens"));
    }

    @Test
    void shouldOmitToolsAndToolChoiceWhenNoTools() {
        ObjectNode root = mapUserRequest();

        assertFalse(root.has("tools"));
        assertFalse(root.has("tool_choice"));
    }

    @Test
    void shouldMapToolAsFunction() {
        ObjectNode root = mapper.map(request(
                List.of(ChatModelMessage.user("Hello")),
                List.of(createTicketTool())
        ));

        JsonNode toolJson = root.path("tools").get(0);

        assertEquals(
                "function",
                toolJson.path("type").asText()
        );
        assertEquals(
                "create_ticket",
                toolJson.path("function").path("name").asText()
        );
        assertEquals(
                "Create a support ticket",
                toolJson.path("function")
                        .path("description")
                        .asText()
        );
    }

    @Test
    void shouldPlaceSchemaAtFunctionParameters() {
        ObjectNode root = mapper.map(request(
                List.of(ChatModelMessage.user("Hello")),
                List.of(createTicketTool())
        ));

        JsonNode parameters = root.path("tools")
                .get(0)
                .path("function")
                .path("parameters");

        assertEquals(
                "object",
                parameters.path("type").asText()
        );
        assertEquals(
                "string",
                parameters.path("properties")
                        .path("title")
                        .path("type")
                        .asText()
        );
        assertEquals(
                "title",
                parameters.path("required").get(0).asText()
        );
    }

    @Test
    void shouldSetToolChoiceAuto() {
        ObjectNode root = mapper.map(request(
                List.of(ChatModelMessage.user("Hello")),
                List.of(createTicketTool())
        ));

        assertEquals(
                "auto",
                root.path("tool_choice").asText()
        );
    }

    @Test
    void shouldSerializeAssistantToolCallArgumentsAsJsonString() throws Exception {
        ChatModelToolCall toolCall = new ChatModelToolCall(
                "call-901",
                "create_ticket",
                objectMapper.readTree("""
                        {
                          "title": "Database unavailable",
                          "priority": "HIGH"
                        }
                        """)
        );

        ChatModelMessage assistant = new ChatModelMessage(
                ChatModelRole.ASSISTANT,
                null,
                List.of(toolCall),
                null
        );

        ObjectNode root = mapper.map(request(
                List.of(assistant),
                List.of()
        ));

        JsonNode toolCallJson = root.path("messages")
                .get(1)
                .path("tool_calls")
                .get(0);

        assertEquals(
                "call-901",
                toolCallJson.path("id").asText()
        );
        assertEquals(
                "function",
                toolCallJson.path("type").asText()
        );
        assertEquals(
                "create_ticket",
                toolCallJson.path("function")
                        .path("name")
                        .asText()
        );

        JsonNode arguments = toolCallJson.path("function")
                .path("arguments");

        assertTrue(arguments.isTextual());

        JsonNode parsed = objectMapper.readTree(
                arguments.asText()
        );

        assertEquals(
                "Database unavailable",
                parsed.path("title").asText()
        );
        assertEquals(
                "HIGH",
                parsed.path("priority").asText()
        );
    }

    @Test
    void shouldRejectNullRequest() {
        assertThrows(
                NullPointerException.class,
                () -> mapper.map(null)
        );
    }

    private ObjectNode mapUserRequest() {
        return mapper.map(request(
                ChatModelOptions.defaults(),
                List.of(ChatModelMessage.user("Hello")),
                List.of()
        ));
    }

    private ChatModelRequest request(
            List<ChatModelMessage> messages,
            List<ChatToolDefinition> tools
    ) {
        return request(
                ChatModelOptions.defaults(),
                messages,
                tools
        );
    }

    private ChatModelRequest request(
            ChatModelOptions options,
            List<ChatModelMessage> messages,
            List<ChatToolDefinition> tools
    ) {
        return new ChatModelRequest(
                "gpt-5",
                "You are a support agent.",
                options,
                messages,
                tools
        );
    }

    private ChatToolDefinition createTicketTool() {
        try {
            return new ChatToolDefinition(
                    "create_ticket",
                    "Create a support ticket",
                    objectMapper.readTree("""
                            {
                              "type": "object",
                              "properties": {
                                "title": {
                                  "type": "string"
                                }
                              },
                              "required": ["title"]
                            }
                            """)
            );
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }
}
