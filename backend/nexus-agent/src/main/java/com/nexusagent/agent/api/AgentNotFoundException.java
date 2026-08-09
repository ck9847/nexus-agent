package com.nexusagent.agent.api;

public final class AgentNotFoundException
        extends RuntimeException {

    public AgentNotFoundException() {
        super("Agent not found");
    }
}