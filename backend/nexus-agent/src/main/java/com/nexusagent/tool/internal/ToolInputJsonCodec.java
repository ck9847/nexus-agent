package com.nexusagent.tool.internal;

import com.fasterxml.jackson.databind.JsonNode;

public interface ToolInputJsonCodec {

    String encode(JsonNode input);

    JsonNode decode(String json);
}