package com.nexusagent.conversation.internal.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

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
}