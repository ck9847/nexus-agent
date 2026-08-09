package com.nexusagent.agent.internal;

import com.nexusagent.agent.domain.AgentModelConfig;

public interface AgentModelConfigJsonCodec {

    String encode(AgentModelConfig config);

    AgentModelConfig decode(String json);
}