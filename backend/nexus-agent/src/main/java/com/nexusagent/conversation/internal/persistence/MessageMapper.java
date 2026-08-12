package com.nexusagent.conversation.internal.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}