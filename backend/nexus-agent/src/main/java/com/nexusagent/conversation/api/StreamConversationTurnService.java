package com.nexusagent.conversation.api;

public interface StreamConversationTurnService {

    /**
     * 流式执行一轮会话。
     *
     * @param conversationId 会话标识（路径参数原样传入）
     * @param content 用户消息内容
     * @param idempotencyKey 客户端可选提供的轮次幂等键
     *         （{@code Idempotency-Key} 请求头）。提供时，
     *         同一 (tenant, conversation, key) 的重复请求
     *         不会重复创建工单。
     * @param handler SSE 事件处理器
     */
    void stream(
            String conversationId,
            String content,
            String idempotencyKey,
            ConversationTurnStreamHandler handler
    );
}