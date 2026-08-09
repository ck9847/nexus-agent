package com.nexusagent.conversation.internal.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConversationMapper {

    @Insert("""
            INSERT INTO conversations
            (
                id,
                tenant_id,
                user_id,
                agent_id,
                title,
                status,
                last_message_at,
                version,
                created_at,
                updated_at
            )
            VALUES
            (
                #{id},
                #{tenantId},
                #{userId},
                #{agentId},
                #{title,jdbcType=VARCHAR},
                #{status},
                #{lastMessageAt,jdbcType=TIMESTAMP},
                #{version},
                #{createdAt},
                #{updatedAt}
            )
            """)
    int insert(ConversationRow row);
}