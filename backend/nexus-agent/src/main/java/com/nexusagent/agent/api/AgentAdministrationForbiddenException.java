package com.nexusagent.agent.api;

public final class AgentAdministrationForbiddenException
        extends RuntimeException {

    public AgentAdministrationForbiddenException() {
        super(
                "Administrator role is required "
                        + "to manage agents"
        );
    }
}