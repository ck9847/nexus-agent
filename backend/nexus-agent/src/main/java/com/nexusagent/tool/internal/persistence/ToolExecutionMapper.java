package com.nexusagent.tool.internal.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

@Mapper
public interface ToolExecutionMapper {

    @Select("""
        SELECT
            c.id AS conversation_id,
            c.tenant_id,
            c.user_id,
            c.agent_id,
            m.id AS request_message_id
        FROM conversations c
        JOIN agents a
          ON a.id = c.agent_id
         AND a.tenant_id = c.tenant_id
        JOIN messages m
          ON m.id = #{requestMessageId}
         AND m.tenant_id = c.tenant_id
         AND m.conversation_id = c.id
        WHERE c.id = #{conversationId}
          AND c.tenant_id = #{tenantId}
          AND c.user_id = #{userId}
          AND c.agent_id = #{agentId}
          AND c.status = 'ACTIVE'
          AND m.`role` = 'ASSISTANT'
          AND m.status = 'CREATING'
        LIMIT 1
        FOR UPDATE
        """)
    Optional<ToolExecutionRegistrationScopeRow>
    findRegistrationScopeForUpdate(
            @Param("conversationId") long conversationId,
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("agentId") long agentId,
            @Param("requestMessageId") long requestMessageId
    );

    @Insert("""
        INSERT INTO tool_executions
        (
            id,
            tenant_id,
            conversation_id,
            agent_id,
            request_message_id,
            result_message_id,
            tool_call_id,
            tool_name,
            idempotency_key,
            input_json,
            output_json,
            status,
            approval_required,
            result_entity_type,
            result_entity_id,
            error_code,
            error_message,
            trace_id,
            started_at,
            completed_at,
            duration_ms,
            created_at,
            updated_at
        )
        VALUES
        (
            #{id},
            #{tenantId},
            #{conversationId},
            #{agentId},
            #{requestMessageId,jdbcType=BIGINT},
            #{resultMessageId,jdbcType=BIGINT},
            #{toolCallId},
            #{toolName},
            #{idempotencyKey},
            #{inputJson,jdbcType=VARCHAR},
            #{outputJson,jdbcType=VARCHAR},
            #{status},
            #{approvalRequired},
            #{resultEntityType,jdbcType=VARCHAR},
            #{resultEntityId,jdbcType=BIGINT},
            #{errorCode,jdbcType=VARCHAR},
            #{errorMessage,jdbcType=VARCHAR},
            #{traceId,jdbcType=VARCHAR},
            #{startedAt,jdbcType=TIMESTAMP},
            #{completedAt,jdbcType=TIMESTAMP},
            #{durationMs,jdbcType=BIGINT},
            #{createdAt},
            #{updatedAt}
        )
        """)
    int insert(ToolExecutionRow row);

    @Select("""
        SELECT
            id
        FROM conversations
        WHERE id = #{conversationId}
          AND tenant_id = #{tenantId}
          AND user_id = #{userId}
          AND agent_id = #{agentId}
        LIMIT 1
        FOR UPDATE
        """)
    Optional<Long> lockOwnedConversationForRecovery(
            @Param("conversationId") long conversationId,
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("agentId") long agentId
    );

    @Select("""
        SELECT
            te.id,
            te.tenant_id,
            te.conversation_id,
            te.agent_id,
            te.request_message_id,
            te.result_message_id,
            te.tool_call_id,
            te.tool_name,
            te.idempotency_key,
            CAST(te.input_json AS CHAR) AS input_json,
            CAST(te.output_json AS CHAR) AS output_json,
            te.status,
            te.approval_required,
            te.result_entity_type,
            te.result_entity_id,
            te.error_code,
            te.error_message,
            te.trace_id,
            te.started_at,
            te.completed_at,
            te.duration_ms,
            te.created_at,
            te.updated_at
        FROM tool_executions AS te
        WHERE te.tenant_id = #{tenantId}
          AND te.idempotency_key = #{idempotencyKey}
        LIMIT 1
        FOR UPDATE
        """)
    Optional<ToolExecutionRow>
    findByIdempotencyKeyForUpdate(
            @Param("tenantId") long tenantId,
            @Param("idempotencyKey") String idempotencyKey
    );

    @Select("""
        SELECT
            te.id,
            te.tenant_id,
            te.conversation_id,
            te.agent_id,
            te.request_message_id,
            te.result_message_id,
            te.tool_call_id,
            te.tool_name,
            te.idempotency_key,
            CAST(te.input_json AS CHAR) AS input_json,
            CAST(te.output_json AS CHAR) AS output_json,
            te.status,
            te.approval_required,
            te.result_entity_type,
            te.result_entity_id,
            te.error_code,
            te.error_message,
            te.trace_id,
            te.started_at,
            te.completed_at,
            te.duration_ms,
            te.created_at,
            te.updated_at
        FROM tool_executions AS te
        WHERE te.tenant_id = #{tenantId}
          AND te.conversation_id = #{conversationId}
          AND te.tool_call_id = #{toolCallId}
        LIMIT 1
        FOR UPDATE
        """)
    Optional<ToolExecutionRow>
    findByConversationAndToolCallIdForUpdate(
            @Param("tenantId") long tenantId,
            @Param("conversationId") long conversationId,
            @Param("toolCallId") String toolCallId
    );
}
