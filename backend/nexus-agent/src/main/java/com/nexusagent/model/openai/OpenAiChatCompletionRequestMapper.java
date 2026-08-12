package com.nexusagent.model.openai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusagent.model.api.ChatModelRequest;

public interface OpenAiChatCompletionRequestMapper {

    ObjectNode map(ChatModelRequest request);
}