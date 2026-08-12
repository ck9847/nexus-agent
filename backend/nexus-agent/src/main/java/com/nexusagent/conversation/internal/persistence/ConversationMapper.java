package com.nexusagent.conversation.internal.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.Optional;

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
                next_message_sequence,
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
                #{nextMessageSequence},
                #{version},
                #{createdAt},
                #{updatedAt}
            )
            """)
    int insert(ConversationRow row);

    @Select("""
        SELECT
            id,
            tenant_id,
            user_id,
            status,
            next_message_sequence,
            version
        FROM conversations
        WHERE id = #{conversationId}
          AND tenant_id = #{tenantId}
          AND user_id = #{userId}
        LIMIT 1
        FOR UPDATE
        """)
    Optional<ConversationAppendStateRow> findOwnedForUpdate(
            @Param("conversationId") long conversationId,
            @Param("tenantId") long tenantId,
            @Param("userId") long userId
    );

    @Update("""
        UPDATE conversations
        SET next_message_sequence =
                    next_message_sequence + 1,
            last_message_at = #{lastMessageAt},
            version = version + 1,
            updated_at = #{lastMessageAt}
        WHERE id = #{conversationId}
          AND tenant_id = #{tenantId}
          AND user_id = #{userId}
          AND status = 'ACTIVE'
          AND next_message_sequence =
                    #{expectedNextMessageSequence}
          AND version = #{expectedVersion}
        """)
    int advanceMessageSequence(
            @Param("conversationId") long conversationId,
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("expectedNextMessageSequence")
            long expectedNextMessageSequence,
            @Param("expectedVersion") int expectedVersion,
            @Param("lastMessageAt") Instant lastMessageAt
    );

    @Select("""
        SELECT
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
        FROM conversations
        WHERE id = #{conversationId}
          AND tenant_id = #{tenantId}
          AND user_id = #{userId}
        LIMIT 1
        """)
    Optional<ConversationDetailRow> findOwnedDetail(
            @Param("conversationId") long conversationId,
            @Param("tenantId") long tenantId,
            @Param("userId") long userId
    );

    @Select("""
        SELECT
            id,
            tenant_id,
            user_id,
            agent_id,
            status,
            next_message_sequence,
            version
        FROM conversations
        WHERE id = #{conversationId}
          AND tenant_id = #{tenantId}
          AND user_id = #{userId}
        LIMIT 1
        FOR UPDATE
        """)
    Optional<ConversationTurnStateRow>
    findOwnedTurnForUpdate(
            @Param("conversationId") long conversationId,
            @Param("tenantId") long tenantId,
            @Param("userId") long userId
    );

    @Update("""
        UPDATE conversations
        SET next_message_sequence =
                    next_message_sequence + 2,
            last_message_at = #{lastMessageAt},
            version = version + 1,
            updated_at = #{lastMessageAt}
        WHERE id = #{conversationId}
          AND tenant_id = #{tenantId}
          AND user_id = #{userId}
          AND status = 'ACTIVE'
          AND next_message_sequence =
                    #{expectedNextMessageSequence}
          AND version = #{expectedVersion}
        """)
    int advanceForPreparedTurn(
            @Param("conversationId") long conversationId,
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("expectedNextMessageSequence")
            long expectedNextMessageSequence,
            @Param("expectedVersion") int expectedVersion,
            @Param("lastMessageAt") java.time.Instant lastMessageAt
    );
}