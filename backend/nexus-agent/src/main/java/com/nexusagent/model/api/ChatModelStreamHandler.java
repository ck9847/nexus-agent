package com.nexusagent.model.api;

@FunctionalInterface
public interface ChatModelStreamHandler {

    void onEvent(ChatModelStreamEvent event);
}