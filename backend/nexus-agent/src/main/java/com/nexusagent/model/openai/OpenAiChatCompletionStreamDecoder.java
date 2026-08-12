package com.nexusagent.model.openai;

import com.nexusagent.model.api.ChatModelStreamHandler;

import java.io.InputStream;

public interface OpenAiChatCompletionStreamDecoder {

    void decode(
            InputStream input,
            ChatModelStreamHandler handler
    );
}