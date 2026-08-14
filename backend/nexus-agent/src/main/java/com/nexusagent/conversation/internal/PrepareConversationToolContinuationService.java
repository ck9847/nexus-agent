package com.nexusagent.conversation.internal;

import com.nexusagent.tool.internal.ExecuteCreateTicketToolResult;

public interface PrepareConversationToolContinuationService {

    PreparedConversationToolContinuation prepare(
            PreparedConversationTurn prepared,
            CompletedConversationToolCall completedToolCall,
            ExecuteCreateTicketToolResult toolResult
    );
}
