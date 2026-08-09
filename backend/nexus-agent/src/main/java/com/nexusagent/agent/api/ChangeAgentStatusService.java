package com.nexusagent.agent.api;

public interface ChangeAgentStatusService {

    ChangeAgentStatusResponse changeStatus(
            String agentCode,
            ChangeAgentStatusRequest request
    );
}