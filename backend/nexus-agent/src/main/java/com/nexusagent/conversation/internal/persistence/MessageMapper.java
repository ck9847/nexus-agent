package com.nexusagent.conversation.internal.persistence;

import com.nexusagent.tool.internal.ToolCallRequestMessageRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

@Mapper
public interface MessageMapper {

    @Insert("""
            INSERT INTO messages
            (
                id,
                tenant_id,
                conversation_id,
                sequence_no,
                `role`,
                content,
                content_type,
                status,
                model_name,
                prompt_tokens,
                completion_tokens,
                metadata_json,
                created_at
            )
            VALUES
            (
                #{id},
                #{tenantId},
                #{conversationId},
                #{sequenceNo},
                #{role},
                #{content},
                #{contentType},
                #{status},
                #{modelName,jdbcType=VARCHAR},
                #{promptTokens,jdbcType=INTEGER},
                #{completionTokens,jdbcType=INTEGER},
                #{metadataJson,jdbcType=VARCHAR},
                #{createdAt}
            )
            """)
    int insert(MessageRow row);

    @Select("""
        <script>
        SELECT
            m.id,
            m.tenant_id,
            m.conversation_id,
            m.sequence_no,
            m.`role`,
            m.content,
            m.content_type,
            m.status,
            m.created_at
        FROM conversations AS c
        INNER JOIN messages AS m
                ON m.conversation_id = c.id
               AND m.tenant_id = c.tenant_id
        WHERE c.id = #{conversationId}
          AND c.tenant_id = #{tenantId}
          AND c.user_id = #{userId}
          AND m.tenant_id = #{tenantId}
          AND m.`role` IN ('USER', 'ASSISTANT')
        <if test="beforeSequenceNo != null">
          AND m.sequence_no
                &lt; #{beforeSequenceNo}
        </if>
        ORDER BY m.sequence_no DESC
        LIMIT #{fetchLimit}
        </script>
        """)
    List<ConversationMessageListRow>
    findOwnedMessagePage(
            @Param("conversationId") long conversationId,
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("beforeSequenceNo")
            Long beforeSequenceNo,
            @Param("fetchLimit") int fetchLimit
    );

    @Select("""
        SELECT EXISTS
        (
            SELECT 1
            FROM conversations AS c
            INNER JOIN messages AS m
                    ON m.conversation_id = c.id
                   AND m.tenant_id = c.tenant_id
            WHERE c.id = #{conversationId}
              AND c.tenant_id = #{tenantId}
              AND c.user_id = #{userId}
              AND m.`role` = 'ASSISTANT'
              AND m.status = 'CREATING'
        )
        """)
    boolean existsCreatingAssistantForOwner(
            @Param("conversationId") long conversationId,
            @Param("tenantId") long tenantId,
            @Param("userId") long userId
    );

    @Select("""
        SELECT
            m.sequence_no,
            m.`role`,
            m.content,
            m.status
        FROM conversations AS c
        INNER JOIN messages AS m
                ON m.conversation_id = c.id
               AND m.tenant_id = c.tenant_id
        WHERE c.id = #{conversationId}
          AND c.tenant_id = #{tenantId}
          AND c.user_id = #{userId}
          AND m.status = 'COMPLETED'
          AND m.`role` IN ('USER', 'ASSISTANT')
        ORDER BY m.sequence_no DESC
        LIMIT #{limit}
        """)
    java.util.List<ConversationTurnMessageRow>
    findRecentCompletedTurnMessages(
            @Param("conversationId") long conversationId,
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("limit") int limit
    );

    @Update("""
        UPDATE messages
        SET content = #{content},
            status = 'COMPLETED',
            model_name = #{modelName},
            prompt_tokens = #{promptTokens},
            completion_tokens = #{completionTokens},
            metadata_json =
                    #{metadataJson,jdbcType=VARCHAR}
        WHERE id = #{messageId}
          AND tenant_id = #{tenantId}
          AND conversation_id = #{conversationId}
          AND sequence_no = #{assistantSequenceNo}
          AND `role` = 'ASSISTANT'
          AND status = 'CREATING'
        """)
    int completeAssistantMessage(
            @Param("messageId") long messageId,
            @Param("tenantId") long tenantId,
            @Param("conversationId") long conversationId,
            @Param("assistantSequenceNo")
            long assistantSequenceNo,
            @Param("content") String content,
            @Param("modelName") String modelName,
            @Param("promptTokens") int promptTokens,
            @Param("completionTokens") int completionTokens,
            @Param("metadataJson") String metadataJson
    );

    @Update("""
        UPDATE messages
        SET content = '',
            status = 'FAILED',
            model_name = #{modelName},
            prompt_tokens = NULL,
            completion_tokens = NULL,
            metadata_json =
                    #{metadataJson,jdbcType=VARCHAR}
        WHERE id = #{messageId}
          AND tenant_id = #{tenantId}
          AND conversation_id = #{conversationId}
          AND sequence_no = #{assistantSequenceNo}
          AND `role` = 'ASSISTANT'
          AND status = 'CREATING'
        """)
    int failAssistantMessage(
            @Param("messageId") long messageId,
            @Param("tenantId") long tenantId,
            @Param("conversationId") long conversationId,
            @Param("assistantSequenceNo")
            long assistantSequenceNo,
            @Param("modelName") String modelName,
            @Param("metadataJson") String metadataJson
    );

    @Update("""
        UPDATE messages AS m
        SET m.content = #{content},
            m.content_type = 'JSON',
            m.status = 'COMPLETED',
            m.model_name = #{modelName},
            m.prompt_tokens = #{promptTokens},
            m.completion_tokens = #{completionTokens},
            m.metadata_json =
                    #{metadataJson,jdbcType=VARCHAR}
        WHERE m.id = #{messageId}
          AND m.tenant_id = #{tenantId}
          AND m.conversation_id = #{conversationId}
          AND m.sequence_no = #{assistantSequenceNo}
          AND m.`role` = 'ASSISTANT'
          AND m.status = 'CREATING'
          AND EXISTS (
              SELECT 1
              FROM tool_executions te
              WHERE te.id = #{toolExecutionId}
                AND te.tenant_id = #{tenantId}
                AND te.conversation_id = #{conversationId}
                AND te.agent_id = #{agentId}
                AND te.request_message_id = m.id
                AND te.result_message_id IS NULL
                AND te.tool_call_id = #{toolCallId}
                AND te.tool_name = #{toolName}
                AND te.status = 'PENDING'
          )
        """)
    int completeAssistantToolCallMessage(
            @Param("messageId") long messageId,
            @Param("tenantId") long tenantId,
            @Param("conversationId") long conversationId,
            @Param("agentId") long agentId,
            @Param("assistantSequenceNo")
            long assistantSequenceNo,
            @Param("content") String content,
            @Param("modelName") String modelName,
            @Param("promptTokens") int promptTokens,
            @Param("completionTokens") int completionTokens,
            @Param("metadataJson") String metadataJson,
            @Param("toolExecutionId") long toolExecutionId,
            @Param("toolCallId") String toolCallId,
            @Param("toolName") String toolName
    );

    @Select("""
        SELECT
            m.id,
            m.tenant_id,
            m.conversation_id,
            m.sequence_no,
            m.`role`,
            m.content,
            m.content_type,
            m.status,
            m.model_name,
            m.metadata_json,
            m.created_at
        FROM messages AS m
        WHERE m.id = #{messageId}
          AND m.tenant_id = #{tenantId}
          AND m.conversation_id = #{conversationId}
          AND m.`role` = 'ASSISTANT'
          AND m.status = 'COMPLETED'
          AND m.content_type = 'JSON'
        LIMIT 1
        FOR UPDATE
        """)
    Optional<ToolCallRequestMessageRow>
    findCompletedToolCallRequestForUpdate(
            @Param("messageId") long messageId,
            @Param("tenantId") long tenantId,
            @Param("conversationId") long conversationId
    );

    @Select("""
        SELECT
            m.id,
            m.tenant_id,
            m.conversation_id,
            m.sequence_no,
            m.`role`,
            m.content,
            m.content_type,
            m.status,
            m.model_name,
            m.metadata_json,
            m.created_at
        FROM messages AS m
        WHERE m.id = #{messageId}
          AND m.tenant_id = #{tenantId}
          AND m.conversation_id = #{conversationId}
        LIMIT 1
        FOR UPDATE
        """)
    Optional<ToolCallRequestMessageRow>
    findOwnedMessageByIdForUpdate(
            @Param("messageId") long messageId,
            @Param("tenantId") long tenantId,
            @Param("conversationId") long conversationId
    );

    @Select("""
        SELECT
            m.id,
            m.tenant_id,
            m.conversation_id,
            m.sequence_no,
            m.`role`,
            m.content,
            m.content_type,
            m.status,
            m.model_name,
            m.metadata_json,
            m.created_at
        FROM messages AS m
        WHERE m.tenant_id = #{tenantId}
          AND m.conversation_id = #{conversationId}
          AND m.sequence_no = #{sequenceNo}
        LIMIT 1
        FOR UPDATE
        """)
    Optional<ToolCallRequestMessageRow>
    findOwnedMessageBySequenceForUpdate(
            @Param("tenantId") long tenantId,
            @Param("conversationId") long conversationId,
            @Param("sequenceNo") long sequenceNo
    );
}