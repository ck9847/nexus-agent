package com.nexusagent.agent.domain;

public final class AgentVersionConflictException
        extends RuntimeException {

    public AgentVersionConflictException() {
        super("Agent was modified by another request");
    }
}