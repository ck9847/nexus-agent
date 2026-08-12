package com.nexusagent.conversation.internal;

public interface ConversationMessageCursorCodec {

    String encode(
            ConversationMessageCursor cursor
    );

    ConversationMessageCursor decode(
            String cursor
    );
}