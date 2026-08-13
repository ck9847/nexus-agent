package com.nexusagent.tool.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentToolExecutionContextTest {

    private static final long TENANT_ID = 202L;
    private static final long REQUESTER_USER_ID = 101L;
    private static final long CONVERSATION_ID = 901L;
    private static final long AGENT_ID = 500L;
    private static final long REQUEST_MESSAGE_ID = 1001L;
    private static final long TOOL_EXECUTION_ID = 7001L;

    @Test
    void shouldAcceptValidContextAndTrimToolCallId() {
        AgentToolExecutionContext context =
                new AgentToolExecutionContext(
                        TENANT_ID,
                        REQUESTER_USER_ID,
                        CONVERSATION_ID,
                        AGENT_ID,
                        REQUEST_MESSAGE_ID,
                        TOOL_EXECUTION_ID,
                        "  call-1  "
                );

        assertEquals(TENANT_ID, context.tenantId());
        assertEquals(
                REQUESTER_USER_ID,
                context.requesterUserId()
        );
        assertEquals(
                CONVERSATION_ID,
                context.conversationId()
        );
        assertEquals(AGENT_ID, context.agentId());
        assertEquals(
                REQUEST_MESSAGE_ID,
                context.requestMessageId()
        );
        assertEquals(
                TOOL_EXECUTION_ID,
                context.toolExecutionId()
        );
        assertEquals("call-1", context.toolCallId());
    }

    @Test
    void shouldAcceptMaximumToolCallIdLength() {
        AgentToolExecutionContext context =
                new AgentToolExecutionContext(
                        TENANT_ID,
                        REQUESTER_USER_ID,
                        CONVERSATION_ID,
                        AGENT_ID,
                        REQUEST_MESSAGE_ID,
                        TOOL_EXECUTION_ID,
                        "a".repeat(128)
                );

        assertEquals(
                128,
                context.toolCallId().length()
        );
    }

    @ParameterizedTest
    @MethodSource("nonPositiveIds")
    void shouldRejectNonPositiveIds(
            long tenantId,
            long requesterUserId,
            long conversationId,
            long agentId,
            long requestMessageId,
            long toolExecutionId
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentToolExecutionContext(
                        tenantId,
                        requesterUserId,
                        conversationId,
                        agentId,
                        requestMessageId,
                        toolExecutionId,
                        "call-1"
                )
        );
    }

    @ParameterizedTest
    @MethodSource("invalidToolCallIds")
    void shouldRejectInvalidToolCallIds(String toolCallId) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentToolExecutionContext(
                        TENANT_ID,
                        REQUESTER_USER_ID,
                        CONVERSATION_ID,
                        AGENT_ID,
                        REQUEST_MESSAGE_ID,
                        TOOL_EXECUTION_ID,
                        toolCallId
                )
        );
    }

    private static Stream<Arguments> invalidToolCallIds() {
        return Stream.of(
                Arguments.of((String) null),
                Arguments.of(""),
                Arguments.of("   "),
                Arguments.of("a".repeat(129))
        );
    }

    private static Stream<Arguments> nonPositiveIds() {
        return Stream.of(
                Arguments.of(
                        0L,
                        REQUESTER_USER_ID,
                        CONVERSATION_ID,
                        AGENT_ID,
                        REQUEST_MESSAGE_ID,
                        TOOL_EXECUTION_ID
                ),
                Arguments.of(
                        -TENANT_ID,
                        REQUESTER_USER_ID,
                        CONVERSATION_ID,
                        AGENT_ID,
                        REQUEST_MESSAGE_ID,
                        TOOL_EXECUTION_ID
                ),
                Arguments.of(
                        TENANT_ID,
                        0L,
                        CONVERSATION_ID,
                        AGENT_ID,
                        REQUEST_MESSAGE_ID,
                        TOOL_EXECUTION_ID
                ),
                Arguments.of(
                        TENANT_ID,
                        -REQUESTER_USER_ID,
                        CONVERSATION_ID,
                        AGENT_ID,
                        REQUEST_MESSAGE_ID,
                        TOOL_EXECUTION_ID
                ),
                Arguments.of(
                        TENANT_ID,
                        REQUESTER_USER_ID,
                        0L,
                        AGENT_ID,
                        REQUEST_MESSAGE_ID,
                        TOOL_EXECUTION_ID
                ),
                Arguments.of(
                        TENANT_ID,
                        REQUESTER_USER_ID,
                        -CONVERSATION_ID,
                        AGENT_ID,
                        REQUEST_MESSAGE_ID,
                        TOOL_EXECUTION_ID
                ),
                Arguments.of(
                        TENANT_ID,
                        REQUESTER_USER_ID,
                        CONVERSATION_ID,
                        0L,
                        REQUEST_MESSAGE_ID,
                        TOOL_EXECUTION_ID
                ),
                Arguments.of(
                        TENANT_ID,
                        REQUESTER_USER_ID,
                        CONVERSATION_ID,
                        -AGENT_ID,
                        REQUEST_MESSAGE_ID,
                        TOOL_EXECUTION_ID
                ),
                Arguments.of(
                        TENANT_ID,
                        REQUESTER_USER_ID,
                        CONVERSATION_ID,
                        AGENT_ID,
                        0L,
                        TOOL_EXECUTION_ID
                ),
                Arguments.of(
                        TENANT_ID,
                        REQUESTER_USER_ID,
                        CONVERSATION_ID,
                        AGENT_ID,
                        -REQUEST_MESSAGE_ID,
                        TOOL_EXECUTION_ID
                ),
                Arguments.of(
                        TENANT_ID,
                        REQUESTER_USER_ID,
                        CONVERSATION_ID,
                        AGENT_ID,
                        REQUEST_MESSAGE_ID,
                        0L
                ),
                Arguments.of(
                        TENANT_ID,
                        REQUESTER_USER_ID,
                        CONVERSATION_ID,
                        AGENT_ID,
                        REQUEST_MESSAGE_ID,
                        -TOOL_EXECUTION_ID
                )
        );
    }
}
