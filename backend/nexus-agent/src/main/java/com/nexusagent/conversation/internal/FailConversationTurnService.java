package com.nexusagent.conversation.internal;

import com.nexusagent.model.api.ChatModelException;

public interface FailConversationTurnService {

    void fail(
            AssistantMessageCompletionTarget target,
            ChatModelException failure
    );
}
