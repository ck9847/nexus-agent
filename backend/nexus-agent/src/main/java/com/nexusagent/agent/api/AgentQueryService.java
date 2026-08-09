package com.nexusagent.agent.api;

public interface AgentQueryService {

    AgentDetailResponse getByCode(
            String agentCode
    );
}