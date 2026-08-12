package com.nexusagent.conversation.internal;

import com.nexusagent.agent.api.ActiveAgentRuntime;
import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.model.api.ChatModelMessage;
import com.nexusagent.model.api.ChatModelOptions;
import com.nexusagent.model.api.ChatModelRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreparedConversationTurnTest {

    private static final long TENANT_ID = 202L;
    private static final long USER_ID = 101L;
    private static final long CONVERSATION_ID = 901L;
    private static final long AGENT_ID = 500L;
    private static final long USER_MESSAGE_ID = 1001L;
    private static final long ASSISTANT_MESSAGE_ID = 1002L;
    private static final Instant PREPARED_AT =
            Instant.parse("2026-08-09T10:15:30.123Z");

    private static ActiveAgentRuntime agent() {
        return new ActiveAgentRuntime(
                AGENT_ID,
                TENANT_ID,
                "support-agent",
                "You are a support agent.",
                AgentModelProvider.OPENAI,
                "gpt-5",
                null
        );
    }

    private static ChatModelRequest modelRequest() {
        return new ChatModelRequest(
                "gpt-5",
                "You are a support agent.",
                ChatModelOptions.defaults(),
                List.of(ChatModelMessage.user("Hello")),
                List.of()
        );
    }

    private static PreparedConversationTurn turn() {
        return new PreparedConversationTurn(
                TENANT_ID,
                USER_ID,
                CONVERSATION_ID,
                agent(),
                USER_MESSAGE_ID,
                2L,
                ASSISTANT_MESSAGE_ID,
                3L,
                8,
                PREPARED_AT,
                modelRequest()
        );
    }

    @Test
    void shouldAcceptValidTurn() {
        PreparedConversationTurn turn = turn();

        assertEquals(TENANT_ID, turn.tenantId());
        assertEquals(USER_ID, turn.userId());
        assertEquals(CONVERSATION_ID, turn.conversationId());
        assertEquals(USER_MESSAGE_ID, turn.userMessageId());
        assertEquals(2L, turn.userSequenceNo());
        assertEquals(ASSISTANT_MESSAGE_ID, turn.assistantMessageId());
        assertEquals(3L, turn.assistantSequenceNo());
        assertEquals(8, turn.conversationVersion());
        assertEquals(PREPARED_AT, turn.preparedAt());
    }

    @ParameterizedTest
    @MethodSource("nonPositiveIds")
    void shouldRejectNonPositiveIds(
            long tenantId,
            long userId,
            long conversationId,
            long userMessageId,
            long assistantMessageId
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PreparedConversationTurn(
                        tenantId,
                        userId,
                        conversationId,
                        agent(),
                        userMessageId,
                        2L,
                        assistantMessageId,
                        3L,
                        8,
                        PREPARED_AT,
                        modelRequest()
                )
        );
    }

    @Test
    void shouldRejectIdenticalMessageIds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PreparedConversationTurn(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        agent(),
                        USER_MESSAGE_ID,
                        2L,
                        USER_MESSAGE_ID,
                        3L,
                        8,
                        PREPARED_AT,
                        modelRequest()
                )
        );
    }

    @ParameterizedTest
    @MethodSource("nonConsecutiveSequences")
    void shouldRejectNonConsecutiveSequences(
            long userSequenceNo,
            long assistantSequenceNo
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PreparedConversationTurn(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        agent(),
                        USER_MESSAGE_ID,
                        userSequenceNo,
                        ASSISTANT_MESSAGE_ID,
                        assistantSequenceNo,
                        8,
                        PREPARED_AT,
                        modelRequest()
                )
        );
    }

    @Test
    void shouldRejectNonPositiveConversationVersion() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PreparedConversationTurn(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        agent(),
                        USER_MESSAGE_ID,
                        2L,
                        ASSISTANT_MESSAGE_ID,
                        3L,
                        0,
                        PREPARED_AT,
                        modelRequest()
                )
        );
    }

    @Test
    void shouldRejectNullAgent() {
        assertThrows(
                NullPointerException.class,
                () -> new PreparedConversationTurn(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        null,
                        USER_MESSAGE_ID,
                        2L,
                        ASSISTANT_MESSAGE_ID,
                        3L,
                        8,
                        PREPARED_AT,
                        modelRequest()
                )
        );
    }

    @Test
    void shouldRejectNullPreparedAt() {
        assertThrows(
                NullPointerException.class,
                () -> new PreparedConversationTurn(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        agent(),
                        USER_MESSAGE_ID,
                        2L,
                        ASSISTANT_MESSAGE_ID,
                        3L,
                        8,
                        null,
                        modelRequest()
                )
        );
    }

    @Test
    void shouldRejectNullModelRequest() {
        assertThrows(
                NullPointerException.class,
                () -> new PreparedConversationTurn(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        agent(),
                        USER_MESSAGE_ID,
                        2L,
                        ASSISTANT_MESSAGE_ID,
                        3L,
                        8,
                        PREPARED_AT,
                        null
                )
        );
    }

    @Test
    void shouldRejectAgentTenantMismatch() {
        ActiveAgentRuntime foreignAgent =
                new ActiveAgentRuntime(
                        AGENT_ID,
                        999L,
                        "support-agent",
                        "You are a support agent.",
                        AgentModelProvider.OPENAI,
                        "gpt-5",
                        null
                );

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new PreparedConversationTurn(
                                TENANT_ID,
                                USER_ID,
                                CONVERSATION_ID,
                                foreignAgent,
                                USER_MESSAGE_ID,
                                2L,
                                ASSISTANT_MESSAGE_ID,
                                3L,
                                8,
                                PREPARED_AT,
                                modelRequest()
                        )
                );

        assertEquals(
                "Agent tenant must match turn tenant",
                exception.getMessage()
        );
    }

    private static Stream<Arguments> nonPositiveIds() {
        return Stream.of(
                Arguments.of(
                        0L,
                        USER_ID,
                        CONVERSATION_ID,
                        USER_MESSAGE_ID,
                        ASSISTANT_MESSAGE_ID
                ),
                Arguments.of(
                        TENANT_ID,
                        0L,
                        CONVERSATION_ID,
                        USER_MESSAGE_ID,
                        ASSISTANT_MESSAGE_ID
                ),
                Arguments.of(
                        TENANT_ID,
                        USER_ID,
                        0L,
                        USER_MESSAGE_ID,
                        ASSISTANT_MESSAGE_ID
                ),
                Arguments.of(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        0L,
                        ASSISTANT_MESSAGE_ID
                ),
                Arguments.of(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        USER_MESSAGE_ID,
                        0L
                )
        );
    }

    private static Stream<Arguments> nonConsecutiveSequences() {
        return Stream.of(
                Arguments.of(0L, 1L),
                Arguments.of(2L, 5L)
        );
    }
}
