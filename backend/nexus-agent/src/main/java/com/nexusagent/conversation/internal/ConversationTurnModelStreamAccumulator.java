package com.nexusagent.conversation.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.model.api.ChatModelException;
import com.nexusagent.model.api.ChatModelFinishReason;
import com.nexusagent.model.api.ChatModelStreamEvent;
import com.nexusagent.model.api.ChatModelStreamHandler;
import com.nexusagent.model.api.ChatModelToolCall;
import com.nexusagent.model.api.ChatTokenUsage;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

final class ConversationTurnModelStreamAccumulator
        implements ChatModelStreamHandler {

    private static final int MAX_TEXT_LENGTH = 50_000;
    private static final int MAX_CALL_ID_LENGTH = 128;
    private static final int MAX_TOOL_NAME_LENGTH = 64;
    private static final int MAX_ARGUMENT_BYTES = 65_536;

    private static final String MALFORMED_MESSAGE =
            "Chat model stream is malformed";

    private final ObjectMapper objectMapper;

    private final StringBuilder text = new StringBuilder();
    private final StringBuilder callId = new StringBuilder();
    private final StringBuilder toolName = new StringBuilder();
    private final StringBuilder arguments = new StringBuilder();

    private boolean textSeen;
    private boolean toolSeen;
    private ChatModelStreamEvent.Completed completed;

    ConversationTurnModelStreamAccumulator(
            ObjectMapper objectMapper
    ) {
        this.objectMapper =
                Objects.requireNonNull(
                        objectMapper,
                        "objectMapper must not be null"
                ).copy();

        this.objectMapper.enable(
                DeserializationFeature
                        .FAIL_ON_TRAILING_TOKENS
        );
    }

    @Override
    public void onEvent(ChatModelStreamEvent event) {
        if (event == null) {
            throw malformed();
        }

        if (event instanceof ChatModelStreamEvent.TextDelta delta) {
            onTextDelta(delta);
            return;
        }

        if (event
                instanceof ChatModelStreamEvent.ToolCallDelta delta) {
            onToolCallDelta(delta);
            return;
        }

        if (event instanceof ChatModelStreamEvent.Completed end) {
            onCompleted(end);
            return;
        }

        throw malformed();
    }

    private void onTextDelta(ChatModelStreamEvent.TextDelta delta) {
        if (completed != null || toolSeen) {
            throw malformed();
        }

        text.append(delta.text());

        if (text.length() > MAX_TEXT_LENGTH) {
            throw malformed();
        }

        textSeen = true;
    }

    private void onToolCallDelta(
            ChatModelStreamEvent.ToolCallDelta delta
    ) {
        if (completed != null || textSeen) {
            throw malformed();
        }

        if (delta.index() != 0) {
            throw malformed();
        }

        if (delta.callIdFragment() != null) {
            callId.append(delta.callIdFragment());
        }

        if (delta.nameFragment() != null) {
            toolName.append(delta.nameFragment());
        }

        if (delta.argumentsFragment() != null) {
            arguments.append(delta.argumentsFragment());
        }

        if (callId.length() > MAX_CALL_ID_LENGTH
                || toolName.length() > MAX_TOOL_NAME_LENGTH
                || arguments.toString()
                .getBytes(StandardCharsets.UTF_8).length
                > MAX_ARGUMENT_BYTES) {
            throw malformed();
        }

        toolSeen = true;
    }

    private void onCompleted(ChatModelStreamEvent.Completed end) {
        if (completed != null) {
            throw malformed();
        }

        completed = end;
    }

    ConversationTurnModelCompletion requireCompletion() {
        if (completed == null) {
            throw malformed();
        }

        if (toolSeen) {
            return requireToolCallCompletion();
        }

        if (textSeen) {
            return requireTextCompletion();
        }

        throw malformed();
    }

    private ConversationTurnModelCompletion
    requireToolCallCompletion() {
        if (completed.finishReason()
                != ChatModelFinishReason.TOOL_CALLS) {
            throw malformed();
        }

        String id = callId.toString().trim();
        String name = toolName.toString().trim();

        if (id.isEmpty()
                || id.length() > MAX_CALL_ID_LENGTH
                || name.isEmpty()
                || name.length() > MAX_TOOL_NAME_LENGTH
                || !"create_ticket".equals(name)) {
            throw malformed();
        }

        JsonNode parsedArguments = parseArguments(
                arguments.toString()
        );

        return new ConversationTurnModelCompletion.ToolCall(
                new ChatModelToolCall(
                        id,
                        name,
                        parsedArguments
                ),
                completed.usage()
        );
    }

    private ConversationTurnModelCompletion
    requireTextCompletion() {
        if (completed.finishReason()
                == ChatModelFinishReason.TOOL_CALLS) {
            throw malformed();
        }

        String content = text.toString();

        if (content.isBlank()) {
            throw malformed();
        }

        return new ConversationTurnModelCompletion.Text(
                content,
                completed.finishReason(),
                completed.usage()
        );
    }

    private JsonNode parseArguments(String raw) {
        try {
            JsonNode parsed =
                    objectMapper.readTree(raw);

            if (parsed == null
                    || !parsed.isObject()) {
                throw malformed();
            }

            return parsed;
        } catch (JsonProcessingException exception) {
            throw malformed();
        }
    }

    private static ChatModelException malformed() {
        return new ChatModelException(
                ChatModelErrorCategory.MALFORMED_RESPONSE,
                MALFORMED_MESSAGE
        );
    }
}
