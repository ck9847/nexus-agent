package com.nexusagent.conversation.internal;

import com.nexusagent.agent.api.ActiveAgentRuntime;
import com.nexusagent.agent.domain.AgentModelProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantMessageCompletionTargetSnapshotTest {

    private static final long TENANT_ID = 202L;
    private static final long USER_ID = 101L;
    private static final long CONVERSATION_ID = 901L;
    private static final long ASSISTANT_MESSAGE_ID = 8002L;
    private static final long ASSISTANT_SEQUENCE_NO = 4L;
    private static final int CONVERSATION_VERSION = 1;

    private static final Instant PREPARED_AT =
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

    @Test
    void shouldExposeAccessorsAndImplementTarget() {
        AssistantMessageCompletionTargetSnapshot snapshot =
                snapshot();

        assertTrue(
                snapshot instanceof AssistantMessageCompletionTarget
        );

        assertEquals(TENANT_ID, snapshot.tenantId());
        assertEquals(USER_ID, snapshot.userId());
        assertEquals(CONVERSATION_ID, snapshot.conversationId());
        assertEquals(AGENT, snapshot.agent());
        assertEquals(
                ASSISTANT_MESSAGE_ID,
                snapshot.assistantMessageId()
        );
        assertEquals(
                ASSISTANT_SEQUENCE_NO,
                snapshot.assistantSequenceNo()
        );
        assertEquals(
                CONVERSATION_VERSION,
                snapshot.conversationVersion()
        );
        assertEquals(PREPARED_AT, snapshot.preparedAt());
    }

    @Test
    void shouldRejectNonPositiveTenantId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> snapshot(0L, USER_ID, CONVERSATION_ID)
        );
    }

    @Test
    void shouldRejectNonPositiveUserId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> snapshot(TENANT_ID, 0L, CONVERSATION_ID)
        );
    }

    @Test
    void shouldRejectNonPositiveConversationId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> snapshot(TENANT_ID, USER_ID, 0L)
        );
    }

    @Test
    void shouldRejectNonPositiveAssistantMessageId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AssistantMessageCompletionTargetSnapshot(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        AGENT,
                        0L,
                        ASSISTANT_SEQUENCE_NO,
                        CONVERSATION_VERSION,
                        PREPARED_AT
                )
        );
    }

    @Test
    void shouldRejectNonPositiveAssistantSequence() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AssistantMessageCompletionTargetSnapshot(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        AGENT,
                        ASSISTANT_MESSAGE_ID,
                        0L,
                        CONVERSATION_VERSION,
                        PREPARED_AT
                )
        );
    }

    @Test
    void shouldRejectNonPositiveConversationVersion() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AssistantMessageCompletionTargetSnapshot(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        AGENT,
                        ASSISTANT_MESSAGE_ID,
                        ASSISTANT_SEQUENCE_NO,
                        0,
                        PREPARED_AT
                )
        );
    }

    @Test
    void shouldRejectNullAgent() {
        assertThrows(
                NullPointerException.class,
                () -> new AssistantMessageCompletionTargetSnapshot(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        null,
                        ASSISTANT_MESSAGE_ID,
                        ASSISTANT_SEQUENCE_NO,
                        CONVERSATION_VERSION,
                        PREPARED_AT
                )
        );
    }

    @Test
    void shouldRejectNullPreparedAt() {
        assertThrows(
                NullPointerException.class,
                () -> new AssistantMessageCompletionTargetSnapshot(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        AGENT,
                        ASSISTANT_MESSAGE_ID,
                        ASSISTANT_SEQUENCE_NO,
                        CONVERSATION_VERSION,
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
                () -> new AssistantMessageCompletionTargetSnapshot(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        otherTenantAgent,
                        ASSISTANT_MESSAGE_ID,
                        ASSISTANT_SEQUENCE_NO,
                        CONVERSATION_VERSION,
                        PREPARED_AT
                )
        );
    }

    private static AssistantMessageCompletionTargetSnapshot
    snapshot() {
        return new AssistantMessageCompletionTargetSnapshot(
                TENANT_ID,
                USER_ID,
                CONVERSATION_ID,
                AGENT,
                ASSISTANT_MESSAGE_ID,
                ASSISTANT_SEQUENCE_NO,
                CONVERSATION_VERSION,
                PREPARED_AT
        );
    }

    private static AssistantMessageCompletionTargetSnapshot
    snapshot(
            long tenantId,
            long userId,
            long conversationId
    ) {
        return new AssistantMessageCompletionTargetSnapshot(
                tenantId,
                userId,
                conversationId,
                AGENT,
                ASSISTANT_MESSAGE_ID,
                ASSISTANT_SEQUENCE_NO,
                CONVERSATION_VERSION,
                PREPARED_AT
        );
    }

}
