package com.nexusagent.conversation.internal;

import com.nexusagent.model.api.ChatModelFinishReason;
import com.nexusagent.model.api.ChatTokenUsage;

public interface CompleteConversationTurnService {

    CompletedConversationTurn complete(
            AssistantMessageCompletionTarget target,
            String assistantContent,
            ChatModelFinishReason finishReason,
            ChatTokenUsage usage
    );
}
