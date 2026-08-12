package com.nexusagent.conversation.api;

import com.nexusagent.agent.api.AgentNotFoundException;
import com.nexusagent.conversation.domain.ConversationStatus;
import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.model.api.ChatModelException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationTurnSseErrorsTest {

    @Test
    void shouldMapConversationNotFound() {
        ConversationTurnSseError error =
                ConversationTurnSseErrors.from(
                        new ConversationNotFoundException()
                );

        assertEquals(
                "CONVERSATION_NOT_FOUND",
                error.errorCode()
        );
        assertEquals(
                "Conversation not found",
                error.message()
        );
        assertFalse(error.retryable());
    }

    @Test
    void shouldMapConversationNotActive() {
        ConversationTurnSseError error =
                ConversationTurnSseErrors.from(
                        new ConversationNotActiveException(
                                ConversationStatus.ARCHIVED
                        )
                );

        assertEquals(
                "CONVERSATION_NOT_ACTIVE",
                error.errorCode()
        );
        assertEquals(
                "Conversation is not active",
                error.message()
        );
        assertFalse(error.retryable());
    }

    @Test
    void shouldMapConversationTurnInProgress() {
        ConversationTurnSseError error =
                ConversationTurnSseErrors.from(
                        new ConversationTurnInProgressException()
                );

        assertEquals(
                "CONVERSATION_TURN_IN_PROGRESS",
                error.errorCode()
        );
        assertEquals(
                "A conversation turn is already in progress",
                error.message()
        );
        assertTrue(error.retryable());
    }

    @Test
    void shouldMapAgentNotFound() {
        ConversationTurnSseError error =
                ConversationTurnSseErrors.from(
                        new AgentNotFoundException()
                );

        assertEquals(
                "ACTIVE_AGENT_NOT_FOUND",
                error.errorCode()
        );
        assertEquals(
                "Active Agent not found",
                error.message()
        );
        assertFalse(error.retryable());
    }

    @Test
    void shouldMapChatModelErrorWithCategoryAndRetryable() {
        ChatModelException failure =
                new ChatModelException(
                        ChatModelErrorCategory.RATE_LIMIT,
                        "provider-secret-must-not-leak",
                        429,
                        null
                );

        ConversationTurnSseError error =
                ConversationTurnSseErrors.from(failure);

        assertEquals(
                "CHAT_MODEL_RATE_LIMIT",
                error.errorCode()
        );
        assertEquals(
                "Chat model turn failed",
                error.message()
        );
        assertTrue(error.retryable());
    }

    @Test
    void shouldMapChatModelMalformedResponseAsNonRetryable() {
        ChatModelException failure =
                new ChatModelException(
                        ChatModelErrorCategory.MALFORMED_RESPONSE,
                        "malformed"
                );

        ConversationTurnSseError error =
                ConversationTurnSseErrors.from(failure);

        assertEquals(
                "CHAT_MODEL_MALFORMED_RESPONSE",
                error.errorCode()
        );
        assertEquals(
                "Chat model turn failed",
                error.message()
        );
        assertFalse(error.retryable());
    }

    @Test
    void shouldMapIllegalArgument() {
        ConversationTurnSseError error =
                ConversationTurnSseErrors.from(
                        new IllegalArgumentException(
                                "invalid"
                        )
                );

        assertEquals(
                "INVALID_ARGUMENT",
                error.errorCode()
        );
        assertEquals(
                "Invalid conversation turn request",
                error.message()
        );
        assertFalse(error.retryable());
    }

    @Test
    void shouldMapUnknownFailureToInternalError() {
        ConversationTurnSseError error =
                ConversationTurnSseErrors.from(
                        new IllegalStateException("boom")
                );

        assertEquals(
                "INTERNAL_ERROR",
                error.errorCode()
        );
        assertEquals(
                "Conversation turn failed",
                error.message()
        );
        assertFalse(error.retryable());
    }

    @Test
    void shouldNeverLeakThrowableMessageToClient() {
        ConversationTurnSseError chatModelError =
                ConversationTurnSseErrors.from(
                        new ChatModelException(
                                ChatModelErrorCategory.RATE_LIMIT,
                                "rate-secret"
                        )
                );

        assertFalse(
                chatModelError.message()
                        .contains("rate-secret")
        );

        ConversationTurnSseError internalError =
                ConversationTurnSseErrors.from(
                        new IllegalStateException(
                                "internal-secret"
                        )
                );

        assertFalse(
                internalError.message()
                        .contains("internal-secret")
        );
    }

    @Test
    void shouldRejectNullFailure() {
        assertThrows(
                NullPointerException.class,
                () -> ConversationTurnSseErrors.from(null)
        );
    }
}
