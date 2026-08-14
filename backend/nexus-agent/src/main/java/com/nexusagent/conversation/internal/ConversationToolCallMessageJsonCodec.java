package com.nexusagent.conversation.internal;

import com.nexusagent.model.api.ChatModelToolCall;

public interface ConversationToolCallMessageJsonCodec {

    String encode(ChatModelToolCall toolCall);

    ChatModelToolCall decode(String json);
}
