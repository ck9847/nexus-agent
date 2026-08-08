package com.nexusagent.ticket.internal.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

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
}