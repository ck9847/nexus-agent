package com.nexusagent.model.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusagent.model.api.ChatModelMessage;
import com.nexusagent.model.api.ChatModelOptions;
import com.nexusagent.model.api.ChatModelRequest;
import com.nexusagent.model.api.ChatModelToolCall;
import com.nexusagent.model.api.ChatToolDefinition;

import java.util.Objects;

public final class JacksonOpenAiChatCompletionRequestMapper
        implements OpenAiChatCompletionRequestMapper {

    private final ObjectMapper objectMapper;

    public JacksonOpenAiChatCompletionRequestMapper(
            ObjectMapper objectMapper
    ) {
        this.objectMapper = Objects.requireNonNull(
                objectMapper,
                "objectMapper must not be null"
        );
    }

    @Override
    public ObjectNode map(ChatModelRequest request) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        ObjectNode root = objectMapper.createObjectNode();

        root.put("model", request.modelName());
        root.put("stream", true);

        root.putObject("stream_options")
                .put("include_usage", true);

        appendOptions(root, request.options());
        appendMessages(root, request);
        appendTools(root, request);

        return root;
    }

    private void appendOptions(
            ObjectNode root,
            ChatModelOptions options
    ) {
        if (options.temperature() != null) {
            root.put(
                    "temperature",
                    options.temperature()
            );
        }

        if (options.topP() != null) {
            root.put("top_p", options.topP());
        }

        if (options.maxOutputTokens() != null) {
            root.put(
                    "max_completion_tokens",
                    options.maxOutputTokens()
            );
        }
    }

    private void appendMessages(
            ObjectNode root,
            ChatModelRequest request
    ) {
        ArrayNode messages = root.putArray("messages");

        ObjectNode developerMessage =
                messages.addObject();

        developerMessage.put("role", "developer");
        developerMessage.put(
                "content",
                request.systemPrompt()
        );

        for (ChatModelMessage message
                : request.messages()) {
            messages.add(mapMessage(message));
        }
    }

    private ObjectNode mapMessage(
            ChatModelMessage message
    ) {
        ObjectNode result =
                objectMapper.createObjectNode();

        switch (message.role()) {
            case USER -> {
                result.put("role", "user");
                result.put("content", message.content());
            }
            case ASSISTANT -> {
                result.put("role", "assistant");

                if (message.content() == null) {
                    result.putNull("content");
                } else {
                    result.put(
                            "content",
                            message.content()
                    );
                }

                if (!message.toolCalls().isEmpty()) {
                    appendToolCalls(
                            result,
                            message
                    );
                }
            }
            case TOOL -> {
                result.put("role", "tool");
                result.put("content", message.content());
                result.put(
                        "tool_call_id",
                        message.toolCallId()
                );
            }
        }

        return result;
    }

    private void appendToolCalls(
            ObjectNode messageJson,
            ChatModelMessage message
    ) {
        ArrayNode toolCalls =
                messageJson.putArray("tool_calls");

        for (ChatModelToolCall call
                : message.toolCalls()) {
            ObjectNode callJson =
                    toolCalls.addObject();

            callJson.put("id", call.id());
            callJson.put("type", "function");

            ObjectNode function =
                    callJson.putObject("function");

            function.put("name", call.name());
            function.put(
                    "arguments",
                    call.arguments().toString()
            );
        }
    }

    private void appendTools(
            ObjectNode root,
            ChatModelRequest request
    ) {
        if (request.tools().isEmpty()) {
            return;
        }

        ArrayNode tools = root.putArray("tools");

        for (ChatToolDefinition tool
                : request.tools()) {
            ObjectNode toolJson = tools.addObject();

            toolJson.put("type", "function");

            ObjectNode function =
                    toolJson.putObject("function");

            function.put("name", tool.name());
            function.put(
                    "description",
                    tool.description()
            );
            function.set(
                    "parameters",
                    tool.inputSchema()
            );
        }

        root.put("tool_choice", "auto");
    }
}