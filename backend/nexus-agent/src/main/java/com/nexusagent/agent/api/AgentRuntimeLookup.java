package com.nexusagent.agent.api;

public interface AgentRuntimeLookup {

    ActiveAgentRuntime requireActiveAgent(
            long tenantId,
            long agentId
    );
}