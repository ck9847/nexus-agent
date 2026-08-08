package com.nexusagent.ticket.internal.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

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
}