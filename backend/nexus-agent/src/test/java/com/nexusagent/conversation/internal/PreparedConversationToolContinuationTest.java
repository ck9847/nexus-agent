package com.nexusagent.conversation.internal;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.nexusagent.agent.api.ActiveAgentRuntime;
import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.model.api.ChatModelMessage;
import com.nexusagent.model.api.ChatModelOptions;
import com.nexusagent.model.api.ChatModelRequest;
import com.nexusagent.model.api.ChatModelToolCall;
import com.nexusagent.model.api.ChatToolDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreparedConversationToolContinuationTest {

    private static final long TENANT_ID = 202L;
    private static final long USER_ID = 101L;
    private static final long CONVERSATION_ID = 901L;
    private static final long TOOL_EXECUTION_ID = 7001L;
    private static final long RESULT_MESSAGE_ID = 8001L;
    private static final long ASSISTANT_MESSAGE_ID = 8002L;

    private static final Instant NOW =
            Instant.parse("2026-08-13T10:15:30.123Z");

    private static final ActiveAgentRuntime AGENT =
            new ActiveAgentRuntime(
                    500L,
                    TENANT_ID,
                    "support-agent",
                    "You are a support agent.",
                    AgentModelProvider.OPENAI,
                    "gpt-5-mini",
                    null
            );

    private static final ChatModelToolCall TOOL_CALL =
            new ChatModelToolCall(
                    "call-1",
                    "create_ticket",
                    JsonNodeFactory.instance.objectNode()
            );

    private static final ChatModelRequest MODEL_REQUEST =
            new ChatModelRequest(
                    "gpt-5-mini",
                    "You are a support agent.",
                    ChatModelOptions.defaults(),
                    List.of(
                            ChatModelMessage.user("Initial message")
                    ),
                    List.of()
            );

    @Test
    void shouldExposeCompletionTargetAccessors() {
        PreparedConversationToolContinuation continuation =
                valid();

        assertEquals(
                TENANT_ID,
                continuation.tenantId()
        );
        assertEquals(USER_ID, continuation.userId());
        assertEquals(
                CONVERSATION_ID,
                continuation.conversationId()
        );
        assertEquals(AGENT, continuation.agent());
        assertEquals(
                ASSISTANT_MESSAGE_ID,
                continuation.assistantMessageId()
        );
        assertEquals(
                4L,
                continuation.assistantSequenceNo()
        );
        assertEquals(1, continuation.conversationVersion());
        assertEquals(NOW, continuation.preparedAt());
        assertEquals(
                TOOL_EXECUTION_ID,
                continuation.toolExecutionId()
        );
        assertEquals(
                RESULT_MESSAGE_ID,
                continuation.resultMessageId()
        );
        assertEquals(
                3L,
                continuation.resultMessageSequenceNo()
        );
        assertEquals(TOOL_CALL, continuation.toolCall());
        assertEquals(
                MODEL_REQUEST,
                continuation.modelRequest()
        );

        assertTrue(
                continuation
                        instanceof AssistantMessageCompletionTarget
        );
    }

    @Test
    void shouldRejectNonPositiveIds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PreparedConversationToolContinuation(
                        0L,
                        USER_ID,
                        CONVERSATION_ID,
                        AGENT,
                        TOOL_EXECUTION_ID,
                        RESULT_MESSAGE_ID,
                        3L,
                        ASSISTANT_MESSAGE_ID,
                        4L,
                        1,
                        NOW,
                        TOOL_CALL,
                        MODEL_REQUEST
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new PreparedConversationToolContinuation(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        AGENT,
                        0L,
                        RESULT_MESSAGE_ID,
                        3L,
                        ASSISTANT_MESSAGE_ID,
                        4L,
                        1,
                        NOW,
                        TOOL_CALL,
                        MODEL_REQUEST
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new PreparedConversationToolContinuation(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        AGENT,
                        TOOL_EXECUTION_ID,
                        RESULT_MESSAGE_ID,
                        0L,
                        ASSISTANT_MESSAGE_ID,
                        4L,
                        1,
                        NOW,
                        TOOL_CALL,
                        MODEL_REQUEST
                )
        );
    }

    @Test
    void shouldRejectDuplicateMessageIds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PreparedConversationToolContinuation(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        AGENT,
                        TOOL_EXECUTION_ID,
                        RESULT_MESSAGE_ID,
                        3L,
                        RESULT_MESSAGE_ID,
                        4L,
                        1,
                        NOW,
                        TOOL_CALL,
                        MODEL_REQUEST
                )
        );
    }

    @Test
    void shouldRejectNonConsecutiveSequences() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PreparedConversationToolContinuation(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        AGENT,
                        TOOL_EXECUTION_ID,
                        RESULT_MESSAGE_ID,
                        3L,
                        ASSISTANT_MESSAGE_ID,
                        5L,
                        1,
                        NOW,
                        TOOL_CALL,
                        MODEL_REQUEST
                )
        );
    }

    @Test
    void shouldRejectInvalidConversationVersion() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PreparedConversationToolContinuation(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        AGENT,
                        TOOL_EXECUTION_ID,
                        RESULT_MESSAGE_ID,
                        3L,
                        ASSISTANT_MESSAGE_ID,
                        4L,
                        0,
                        NOW,
                        TOOL_CALL,
                        MODEL_REQUEST
                )
        );
    }

    @Test
    void shouldRejectNullComponents() {
        assertThrows(
                NullPointerException.class,
                () -> new PreparedConversationToolContinuation(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        null,
                        TOOL_EXECUTION_ID,
                        RESULT_MESSAGE_ID,
                        3L,
                        ASSISTANT_MESSAGE_ID,
                        4L,
                        1,
                        NOW,
                        TOOL_CALL,
                        MODEL_REQUEST
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PreparedConversationToolContinuation(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        AGENT,
                        TOOL_EXECUTION_ID,
                        RESULT_MESSAGE_ID,
                        3L,
                        ASSISTANT_MESSAGE_ID,
                        4L,
                        1,
                        null,
                        TOOL_CALL,
                        MODEL_REQUEST
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PreparedConversationToolContinuation(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        AGENT,
                        TOOL_EXECUTION_ID,
                        RESULT_MESSAGE_ID,
                        3L,
                        ASSISTANT_MESSAGE_ID,
                        4L,
                        1,
                        NOW,
                        null,
                        MODEL_REQUEST
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new PreparedConversationToolContinuation(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        AGENT,
                        TOOL_EXECUTION_ID,
                        RESULT_MESSAGE_ID,
                        3L,
                        ASSISTANT_MESSAGE_ID,
                        4L,
                        1,
                        NOW,
                        TOOL_CALL,
                        null
                )
        );
    }

    @Test
    void shouldRejectAgentTenantMismatch() {
        ActiveAgentRuntime otherTenantAgent =
                new ActiveAgentRuntime(
                        500L,
                        TENANT_ID + 1,
                        "support-agent",
                        "You are a support agent.",
                        AgentModelProvider.OPENAI,
                        "gpt-5-mini",
                        null
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> new PreparedConversationToolContinuation(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        otherTenantAgent,
                        TOOL_EXECUTION_ID,
                        RESULT_MESSAGE_ID,
                        3L,
                        ASSISTANT_MESSAGE_ID,
                        4L,
                        1,
                        NOW,
                        TOOL_CALL,
                        MODEL_REQUEST
                )
        );
    }

    @Test
    void shouldRejectModelRequestWithTools() {
        ChatModelRequest requestWithTools =
                new ChatModelRequest(
                        "gpt-5-mini",
                        "You are a support agent.",
                        ChatModelOptions.defaults(),
                        List.of(
                                ChatModelMessage.user(
                                        "Initial message"
                                )
                        ),
                        List.of(
                                new ChatToolDefinition(
                                        "create_ticket",
                                        "Create a ticket",
                                        JsonNodeFactory.instance
                                                .objectNode()
                                )
                        )
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> new PreparedConversationToolContinuation(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        AGENT,
                        TOOL_EXECUTION_ID,
                        RESULT_MESSAGE_ID,
                        3L,
                        ASSISTANT_MESSAGE_ID,
                        4L,
                        1,
                        NOW,
                        TOOL_CALL,
                        requestWithTools
                )
        );
    }

    private static PreparedConversationToolContinuation valid() {
        return new PreparedConversationToolContinuation(
                TENANT_ID,
                USER_ID,
                CONVERSATION_ID,
                AGENT,
                TOOL_EXECUTION_ID,
                RESULT_MESSAGE_ID,
                3L,
                ASSISTANT_MESSAGE_ID,
                4L,
                1,
                NOW,
                TOOL_CALL,
                MODEL_REQUEST
        );
    }
}
