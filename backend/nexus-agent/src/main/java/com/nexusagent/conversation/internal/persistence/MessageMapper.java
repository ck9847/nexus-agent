package com.nexusagent.conversation.internal.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

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
}