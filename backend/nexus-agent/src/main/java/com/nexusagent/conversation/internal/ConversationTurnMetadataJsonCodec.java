package com.nexusagent.conversation.internal;

import java.util.Map;

public interface ConversationTurnMetadataJsonCodec {

    String encode(Map<String, ?> metadata);
}