package com.nexusagent.model.api;

public enum ChatModelFinishReason {
    STOP,
    TOOL_CALLS,
    LENGTH,
    CONTENT_FILTER,
    OTHER
}