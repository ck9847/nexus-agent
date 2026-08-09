package com.nexusagent.conversation.api;

public interface AppendUserMessageService {

    AppendUserMessageResponse append(
            String conversationId,
            AppendUserMessageRequest request
    );
}