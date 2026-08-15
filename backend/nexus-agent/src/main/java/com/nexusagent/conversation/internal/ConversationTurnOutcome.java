package com.nexusagent.conversation.internal;

/**
 * 单个会话轮次的最终结果分类，用于指标统计。
 */
enum ConversationTurnOutcome {

    /** 纯文本轮完整成功。 */
    COMPLETED_TEXT,

    /** 工具轮（首轮工具调用 + 续写轮）完整成功。 */
    COMPLETED_TOOL,

    /** 模型调用失败（含两轮模型）。 */
    MODEL_FAILED,

    /** 工具链失败（注册、tool-call 完成、执行、续写准备）。 */
    TOOL_FAILED,

    /** 客户端断连（STREAM_INTERRUPTED）。 */
    CLIENT_DISCONNECTED,

    /** 其它内部失败（如完成事务失败）。 */
    INTERNAL_FAILED
}
