package com.nexusagent.model.api;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatModelRequestTest {

    private static final int MAX_MESSAGES = 1_000;
    private static final int MAX_TOOLS = 64;

    private static ChatModelMessage userMessage(String content) {
        return ChatModelMessage.user(content);
    }

    private static ChatToolDefinition tool(String name) {
        return new ChatToolDefinition(
                name,
                "Tool " + name,
                JsonNodeFactory.instance.objectNode()
                        .put("type", "object")
        );
    }

    private static ChatModelRequest buildRequest(
            List<ChatModelMessage> messages,
            List<ChatToolDefinition> tools
    ) {
        return new ChatModelRequest(
                "gpt-5",
                "system prompt",
                ChatModelOptions.defaults(),
                messages,
                tools
        );
    }

    @Test
    void shouldTrimModelName() {
        ChatModelRequest request =
                new ChatModelRequest(
                        "  gpt-5  ",
                        "system prompt",
                        ChatModelOptions.defaults(),
                        List.of(userMessage("Hello")),
                        List.of()
                );

        assertEquals("gpt-5", request.modelName());
    }

    @Test
    void shouldRejectBlankModelName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChatModelRequest(
                        "   ",
                        "system prompt",
                        ChatModelOptions.defaults(),
                        List.of(userMessage("Hello")),
                        List.of()
                )
        );
    }

    @Test
    void shouldRejectEmptyMessages() {
        assertThrows(
                IllegalArgumentException.class,
                () -> buildRequest(List.of(), List.of())
        );
    }

    @Test
    void shouldRejectDuplicateToolNames() {
        List<ChatToolDefinition> tools =
                List.of(
                        tool("search_docs"),
                        tool("search_docs")
                );

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> buildRequest(
                                List.of(userMessage("Hello")),
                                tools
                        )
                );

        assertEquals(
                "tool names must be unique",
                exception.getMessage()
        );
    }

    @Test
    void shouldAcceptMaximumTools() {
        List<ChatToolDefinition> tools =
                new ArrayList<>();

        for (int index = 0;
             index < MAX_TOOLS;
             index++) {
            tools.add(tool("tool_" + index));
        }

        ChatModelRequest request =
                buildRequest(
                        List.of(userMessage("Hello")),
                        tools
                );

        assertEquals(MAX_TOOLS, request.tools().size());
    }

    @Test
    void shouldRejectTooManyTools() {
        List<ChatToolDefinition> tools =
                new ArrayList<>();

        for (int index = 0;
             index < MAX_TOOLS + 1;
             index++) {
            tools.add(tool("tool_" + index));
        }

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> buildRequest(
                                List.of(userMessage("Hello")),
                                tools
                        )
                );

        assertEquals(
                "tools must not contain more than 64 entries",
                exception.getMessage()
        );
    }

    @Test
    void shouldAcceptMaximumMessages() {
        List<ChatModelMessage> messages =
                new ArrayList<>();

        for (int index = 0;
             index < MAX_MESSAGES;
             index++) {
            messages.add(userMessage("m" + index));
        }

        ChatModelRequest request =
                buildRequest(messages, List.of());

        assertEquals(
                MAX_MESSAGES,
                request.messages().size()
        );
    }

    @Test
    void shouldRejectTooManyMessages() {
        List<ChatModelMessage> messages =
                new ArrayList<>();

        for (int index = 0;
             index < MAX_MESSAGES + 1;
             index++) {
            messages.add(userMessage("m" + index));
        }

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> buildRequest(messages, List.of())
                );

        assertEquals(
                "messages must not contain more than "
                        + "1000 entries",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectBlankSystemPrompt() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new ChatModelRequest(
                                "gpt-5",
                                "   ",
                                ChatModelOptions.defaults(),
                                List.of(userMessage("Hello")),
                                List.of()
                        )
                );

        assertEquals(
                "systemPrompt must not be blank",
                exception.getMessage()
        );
    }

    @Test
    void shouldDefensivelyCopyMessagesAndTools() {
        List<ChatModelMessage> mutableMessages =
                new ArrayList<>();
        mutableMessages.add(userMessage("Hello"));

        List<ChatToolDefinition> mutableTools =
                new ArrayList<>();
        mutableTools.add(tool("search_docs"));

        ChatModelRequest request =
                buildRequest(
                        mutableMessages,
                        mutableTools
                );

        // 修改传入的原始列表不影响内部副本。
        mutableMessages.clear();
        mutableTools.clear();

        assertEquals(1, request.messages().size());
        assertEquals(1, request.tools().size());

        // 内部副本不可修改。
        assertThrows(
                UnsupportedOperationException.class,
                () -> request.messages().add(
                        userMessage("x")
                )
        );

        assertThrows(
                UnsupportedOperationException.class,
                () -> request.tools().add(
                        tool("another_tool")
                )
        );
    }
}
