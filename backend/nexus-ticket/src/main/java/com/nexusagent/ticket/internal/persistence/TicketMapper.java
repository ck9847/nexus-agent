package com.nexusagent.ticket.internal.persistence;

import com.nexusagent.ticket.domain.TicketPriority;
import com.nexusagent.ticket.domain.TicketStatus;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Optional;
import java.time.Instant;
import java.util.List;

@Mapper
public interface TicketMapper {

    @Insert("""
            INSERT INTO tickets
            (
                id,
                tenant_id,
                ticket_no,
                title,
                description,
                priority,
                status,
                source,
                requester_user_id,
                assignee_user_id,
                created_by_agent_id,
                version
            )
            VALUES
            (
                #{id},
                #{tenantId},
                #{ticketNo},
                #{title},
                #{description},
                #{priority},
                #{status},
                #{source},
                #{requesterUserId},
                #{assigneeUserId},
                #{createdByAgentId},
                #{version}
            )
            """)
    int insert(TicketRow row);

    @Select("""
        SELECT
            id,
            tenant_id,
            ticket_no,
            title,
            description,
            priority,
            status,
            source,
            requester_user_id,
            assignee_user_id,
            created_by_agent_id,
            version,
            created_at,
            updated_at,
            closed_at
        FROM tickets
        WHERE tenant_id = #{tenantId}
          AND ticket_no = #{ticketNo}
        LIMIT 1
        """)
    Optional<TicketDetailRow> findDetailByTenantIdAndTicketNo(
            @Param("tenantId") long tenantId,
            @Param("ticketNo") String ticketNo
    );

    @Select("""
        <script>
        SELECT
            id,
            tenant_id,
            ticket_no,
            title,
            priority,
            status,
            source,
            requester_user_id,
            assignee_user_id,
            version,
            created_at,
            updated_at
        FROM tickets
        WHERE tenant_id = #{tenantId}

        <if test="status != null">
          AND status = #{status}
        </if>

        <if test="priority != null">
          AND priority = #{priority}
        </if>

        <if test="cursorCreatedAt != null">
          AND
          (
              created_at &lt; #{cursorCreatedAt}
              OR
              (
                  created_at = #{cursorCreatedAt}
                  AND id &lt; #{cursorTicketId}
              )
          )
        </if>

        ORDER BY created_at DESC, id DESC
        LIMIT #{fetchLimit}
        </script>
        """)
    List<TicketListRow> findPage(
            @Param("tenantId") long tenantId,
            @Param("status") TicketStatus status,
            @Param("priority") TicketPriority priority,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorTicketId") Long cursorTicketId,
            @Param("fetchLimit") int fetchLimit
    );

    @Select("""
            SELECT
                id,
                tenant_id,
                ticket_no,
                status,
                version,
                closed_at,
                updated_at
            FROM tickets
            WHERE tenant_id = #{tenantId}
              AND ticket_no = #{ticketNo}
            LIMIT 1
            """)
    Optional<TicketStatusRow>
    findStatusByTenantIdAndTicketNo(
            @Param("tenantId") long tenantId,
            @Param("ticketNo") String ticketNo
    );

    @Update("""
            UPDATE tickets
            SET status = #{targetStatus},
                version = version + 1,
                closed_at = CASE
                    WHEN #{targetStatus} = 'CLOSED'
                    THEN CURRENT_TIMESTAMP(3)
                    ELSE NULL
                END,
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE tenant_id = #{tenantId}
              AND ticket_no = #{ticketNo}
              AND status = #{currentStatus}
              AND version = #{expectedVersion}
            """)
    int updateStatus(
            @Param("tenantId") long tenantId,
            @Param("ticketNo") String ticketNo,
            @Param("currentStatus")
            TicketStatus currentStatus,
            @Param("targetStatus")
            TicketStatus targetStatus,
            @Param("expectedVersion")
            int expectedVersion
    );
}