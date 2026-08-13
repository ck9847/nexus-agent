package com.nexusagent.tool.api;

public interface RegisterToolExecutionService {

    RegisterToolExecutionResult register(
            RegisterToolExecutionCommand command
    );
}