package com.nexusagent.model.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.model.api.ChatModelException;
import com.nexusagent.model.api.ChatModelFinishReason;
import com.nexusagent.model.api.ChatModelStreamEvent;
import com.nexusagent.model.api.ChatModelStreamHandler;
import com.nexusagent.model.api.ChatTokenUsage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

public final class JacksonOpenAiChatCompletionStreamDecoder
        implements OpenAiChatCompletionStreamDecoder {

    private static final int MAX_EVENT_CHARACTERS =
            1_000_000;

    private final ObjectMapper objectMapper;

    public JacksonOpenAiChatCompletionStreamDecoder(
            ObjectMapper objectMapper
    ) {
        this.objectMapper = Objects.requireNonNull(
                objectMapper,
                "objectMapper must not be null"
        );
    }

    @Override
    public void decode(
            InputStream input,
            ChatModelStreamHandler handler
    ) {
        Objects.requireNonNull(
                input,
                "input must not be null"
        );
        Objects.requireNonNull(
                handler,
                "handler must not be null"
        );

        StreamState state = new StreamState();

        try {
            boolean completed = readEvents(
                    input,
                    handler,
                    state
            );

            if (!completed) {
                throw new ChatModelException(
                        ChatModelErrorCategory
                                .STREAM_INTERRUPTED,
                        "Chat model stream ended unexpectedly"
                );
            }
        } catch (ChatModelException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ChatModelException(
                    ChatModelErrorCategory
                            .STREAM_INTERRUPTED,
                    "Chat model stream was interrupted",
                    null,
                    exception
            );
        }
    }

    private boolean readEvents(
            InputStream input,
            ChatModelStreamHandler handler,
            StreamState state
    ) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        input,
                        StandardCharsets.UTF_8
                )
        );

        StringBuilder data = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    if (processEvent(
                            data.toString(),
                            handler,
                            state
                    )) {
                        return true;
                    }

                    data.setLength(0);
                }

                continue;
            }

            if (line.startsWith(":")) {
                continue;
            }

            int colon = line.indexOf(':');

            String field = colon < 0
                    ? line
                    : line.substring(0, colon);

            String value = colon < 0
                    ? ""
                    : line.substring(colon + 1);

            if (value.startsWith(" ")) {
                value = value.substring(1);
            }

            if ("data".equals(field)) {
                appendData(data, value);
            }
        }

        if (data.length() > 0) {
            return processEvent(
                    data.toString(),
                    handler,
                    state
            );
        }

        return false;
    }

    private static void appendData(
            StringBuilder data,
            String value
    ) {
        int additionalCharacters =
                value.length()
                        + (data.isEmpty() ? 0 : 1);

        if (data.length() + additionalCharacters
                > MAX_EVENT_CHARACTERS) {
            throw malformed(
                    "Chat model stream event is too large"
            );
        }

        if (!data.isEmpty()) {
            data.append('\n');
        }

        data.append(value);
    }

    private boolean processEvent(
            String data,
            ChatModelStreamHandler handler,
            StreamState state
    ) {
        if ("[DONE]".equals(data.trim())) {
            state.complete(handler);
            return true;
        }

        JsonNode root;

        try {
            root = objectMapper.readTree(data);
        } catch (JsonProcessingException exception) {
            throw new ChatModelException(
                    ChatModelErrorCategory
                            .MALFORMED_RESPONSE,
                    "Chat model provider returned malformed JSON",
                    null,
                    exception
            );
        }

        if (root == null || !root.isObject()) {
            throw malformed(
                    "Chat model provider returned an invalid event"
            );
        }

        if (root.hasNonNull("error")) {
            throw new ChatModelException(
                    ChatModelErrorCategory
                            .PROVIDER_UNAVAILABLE,
                    "Chat model provider returned an error"
            );
        }

        processChoices(root, handler, state);
        processUsage(root, state);

        return false;
    }

    private void processChoices(
            JsonNode root,
            ChatModelStreamHandler handler,
            StreamState state
    ) {
        JsonNode choices = root.get("choices");

        if (choices == null || !choices.isArray()) {
            throw malformed(
                    "Chat model event must contain choices"
            );
        }

        if (choices.size() > 1) {
            throw malformed(
                    "Chat model event contains multiple choices"
            );
        }

        if (choices.isEmpty()) {
            return;
        }

        JsonNode choice = choices.get(0);

        if (!choice.isObject()
                || choice.path("index").asInt(-1) != 0) {
            throw malformed(
                    "Chat model event contains an invalid choice"
            );
        }

        JsonNode delta = choice.get("delta");

        if (delta == null || !delta.isObject()) {
            throw malformed(
                    "Chat model choice must contain a delta"
            );
        }

        processContent(delta, handler);
        processToolCalls(delta, handler);

        String finishReason =
                optionalText(choice, "finish_reason");

        if (finishReason != null) {
            state.recordFinishReason(
                    mapFinishReason(finishReason)
            );
        }
    }

    private static void processContent(
            JsonNode delta,
            ChatModelStreamHandler handler
    ) {
        JsonNode content = delta.get("content");

        if (content == null || content.isNull()) {
            return;
        }

        if (!content.isTextual()) {
            throw malformed(
                    "Chat model content delta must be text"
            );
        }

        if (!content.textValue().isEmpty()) {
            handler.onEvent(
                    new ChatModelStreamEvent.TextDelta(
                            content.textValue()
                    )
            );
        }
    }

    private static void processToolCalls(
            JsonNode delta,
            ChatModelStreamHandler handler
    ) {
        JsonNode toolCalls = delta.get("tool_calls");

        if (toolCalls == null || toolCalls.isNull()) {
            return;
        }

        if (!toolCalls.isArray()) {
            throw malformed(
                    "Chat model tool calls must be an array"
            );
        }

        for (JsonNode toolCall : toolCalls) {
            if (!toolCall.isObject()) {
                throw malformed(
                        "Chat model tool call delta "
                                + "must be an object"
                );
            }

            JsonNode indexNode = toolCall.get("index");

            if (indexNode == null
                    || !indexNode.isIntegralNumber()
                    || !indexNode.canConvertToInt()
                    || indexNode.intValue() < 0) {
                throw malformed(
                        "Chat model tool call index is invalid"
                );
            }

            String type = optionalText(
                    toolCall,
                    "type"
            );

            if (type != null
                    && !"function".equals(type)) {
                throw malformed(
                        "Unsupported chat model tool call type"
                );
            }

            String id = emptyToNull(
                    optionalText(toolCall, "id")
            );

            String name = null;
            String arguments = null;

            JsonNode function = toolCall.get("function");

            if (function != null && !function.isNull()) {
                if (!function.isObject()) {
                    throw malformed(
                            "Chat model tool function "
                                    + "must be an object"
                    );
                }

                name = emptyToNull(
                        optionalText(function, "name")
                );
                arguments = emptyToNull(
                        optionalText(function, "arguments")
                );
            }

            if (id != null
                    || name != null
                    || arguments != null) {
                handler.onEvent(
                        new ChatModelStreamEvent
                                .ToolCallDelta(
                                indexNode.intValue(),
                                id,
                                name,
                                arguments
                        )
                );
            }
        }
    }

    private static void processUsage(
            JsonNode root,
            StreamState state
    ) {
        JsonNode usage = root.get("usage");

        if (usage == null || usage.isNull()) {
            return;
        }

        if (!usage.isObject()) {
            throw malformed(
                    "Chat model usage must be an object"
            );
        }

        int promptTokens = requireTokenCount(
                usage,
                "prompt_tokens"
        );

        int completionTokens = requireTokenCount(
                usage,
                "completion_tokens"
        );

        state.recordUsage(new ChatTokenUsage(
                promptTokens,
                completionTokens
        ));
    }

    private static int requireTokenCount(
            JsonNode usage,
            String field
    ) {
        JsonNode value = usage.get(field);

        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToInt()
                || value.intValue() < 0) {
            throw malformed(
                    "Chat model usage contains "
                            + "an invalid token count"
            );
        }

        return value.intValue();
    }

    private static String optionalText(
            JsonNode parent,
            String field
    ) {
        JsonNode value = parent.get(field);

        if (value == null || value.isNull()) {
            return null;
        }

        if (!value.isTextual()) {
            throw malformed(
                    "Chat model event contains "
                            + "an invalid text field"
            );
        }

        return value.textValue();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty()
                ? null
                : value;
    }

    private static ChatModelFinishReason mapFinishReason(
            String value
    ) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "stop" -> ChatModelFinishReason.STOP;
            case "tool_calls", "function_call" ->
                    ChatModelFinishReason.TOOL_CALLS;
            case "length" ->
                    ChatModelFinishReason.LENGTH;
            case "content_filter" ->
                    ChatModelFinishReason.CONTENT_FILTER;
            default -> ChatModelFinishReason.OTHER;
        };
    }

    private static ChatModelException malformed(
            String safeMessage
    ) {
        return new ChatModelException(
                ChatModelErrorCategory.MALFORMED_RESPONSE,
                safeMessage
        );
    }

    private static final class StreamState {

        private ChatModelFinishReason finishReason;
        private ChatTokenUsage usage;

        private void recordFinishReason(
                ChatModelFinishReason value
        ) {
            if (finishReason != null) {
                throw malformed(
                        "Chat model stream contains "
                                + "multiple finish reasons"
                );
            }

            finishReason = value;
        }

        private void recordUsage(ChatTokenUsage value) {
            if (usage != null) {
                throw malformed(
                        "Chat model stream contains "
                                + "multiple usage records"
                );
            }

            usage = value;
        }

        private void complete(
                ChatModelStreamHandler handler
        ) {
            if (finishReason == null || usage == null) {
                throw malformed(
                        "Chat model stream completed "
                                + "without finish reason or usage"
                );
            }

            handler.onEvent(
                    new ChatModelStreamEvent.Completed(
                            finishReason,
                            usage
                    )
            );
        }
    }
}