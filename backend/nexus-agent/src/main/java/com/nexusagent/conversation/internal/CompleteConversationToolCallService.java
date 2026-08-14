package com.nexusagent.conversation.internal;

import com.nexusagent.model.api.ChatModelToolCall;
import com.nexusagent.model.api.ChatTokenUsage;

public interface CompleteConversationToolCallService {

    CompletedConversationToolCall complete(
            PreparedConversationTurn prepared,
            ChatModelToolCall toolCall,
            ChatTokenUsage usage,
            long toolExecutionId
    );
}
