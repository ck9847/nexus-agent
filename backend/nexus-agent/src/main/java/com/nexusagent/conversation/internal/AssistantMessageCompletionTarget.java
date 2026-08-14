package com.nexusagent.conversation.internal;

import com.nexusagent.agent.api.ActiveAgentRuntime;

import java.time.Instant;

/**
 * Identifies the ASSISTANT placeholder message that a conversation
 * turn completion or failure finalizes.
 *
 * <p>Both the first-round turn ({@link PreparedConversationTurn})
 * and the post-tool continuation round
 * ({@link PreparedConversationToolContinuation}) implement this
 * contract, so the same complete/fail services can finalize either
 * placeholder without knowing which round produced it.
 */
public interface AssistantMessageCompletionTarget {

    long tenantId();

    long userId();

    long conversationId();

    ActiveAgentRuntime agent();

    long assistantMessageId();

    long assistantSequenceNo();

    int conversationVersion();

    Instant preparedAt();
}
