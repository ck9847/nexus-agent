package com.nexusagent.conversation.internal;

import com.nexusagent.model.api.ChatModelFinishReason;
import com.nexusagent.model.api.ChatTokenUsage;

public interface CompleteConversationTurnService {

    CompletedConversationTurn complete(
            PreparedConversationTurn prepared,
            String assistantContent,
            ChatModelFinishReason finishReason,
            ChatTokenUsage usage
    );
}