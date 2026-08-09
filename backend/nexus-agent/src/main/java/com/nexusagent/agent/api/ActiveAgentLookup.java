package com.nexusagent.agent.api;

public interface ActiveAgentLookup {

    ActiveAgentReference requireActiveAgent(
            long tenantId,
            String agentCode
    );
}