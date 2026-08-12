package com.nexusagent.model.api;

import com.nexusagent.agent.domain.AgentModelProvider;

public interface ChatModelGateway {

    AgentModelProvider provider();

    /**
     * 同步消费供应商的流式响应。
     *
     * 返回前必须发送且只能发送一个 Completed 事件。
     * 网络或供应商错误通过 ChatModelException 抛出。
     */
    void stream(
            ChatModelRequest request,
            ChatModelStreamHandler handler
    );
}