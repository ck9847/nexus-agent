package com.nexusagent.ticket.api;

import com.nexusagent.ticket.domain.TicketStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.nexusagent.ticket.domain.TicketPriority;
import com.nexusagent.ticket.domain.TicketSource;
import com.nexusagent.ticket.domain.InvalidTicketStatusTransitionException;
import com.nexusagent.ticket.domain.TicketVersionConflictException;

import java.util.List;
import java.time.Instant;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

@WebMvcTest(TicketController.class)
class TicketControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateTicketService createTicketService;

    @MockitoBean
    private TicketQueryService ticketQueryService;

    @MockitoBean
    private ChangeTicketStatusService changeTicketStatusService;

    @Test
    void shouldCreateTicket() throws Exception {
        when(createTicketService.create(any()))
                .thenReturn(new CreateTicketResponse(
                        "901",
                        "TKT-P1",
                        TicketStatus.OPEN
                ));

        mockMvc.perform(post("/api/v1/tickets")
                        .contentType("application/json")
                        .content("""
                                {
                                  "title": "Server unavailable",
                                  "description": "Cannot connect to production.",
                                  "priority": "HIGH"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(
                        "application/json"
                ))
                .andExpect(jsonPath("$.ticketId")
                        .value("901"))
                .andExpect(jsonPath("$.ticketNo")
                        .value("TKT-P1"))
                .andExpect(jsonPath("$.status")
                        .value("OPEN"));
    }

    @Test
    void shouldRejectInvalidTicketRequest() throws Exception {
        mockMvc.perform(post("/api/v1/tickets")
                        .contentType("application/json")
                        .content("""
                                {
                                  "title": "",
                                  "description": "",
                                  "priority": null
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(createTicketService);
    }

    @Test
    void shouldReturnTicketDetails() throws Exception {
        Instant createdAt = Instant.parse(
                "2026-08-08T01:00:00Z"
        );
        Instant updatedAt = Instant.parse(
                "2026-08-08T02:00:00Z"
        );

        when(ticketQueryService.getByTicketNo(
                "TKT-P1"
        )).thenReturn(new TicketDetailResponse(
                "901",
                "TKT-P1",
                "Server unavailable",
                "Cannot connect to production.",
                TicketPriority.HIGH,
                TicketStatus.OPEN,
                TicketSource.USER,
                "101",
                null,
                null,
                0,
                createdAt,
                updatedAt,
                null
        ));

        mockMvc.perform(get(
                        "/api/v1/tickets/{ticketNo}",
                        "TKT-P1"
                ))
                .andExpect(status().isOk())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                "application/json"
                        ))
                .andExpect(jsonPath("$.ticketId")
                        .value("901"))
                .andExpect(jsonPath("$.ticketNo")
                        .value("TKT-P1"))
                .andExpect(jsonPath("$.title")
                        .value("Server unavailable"))
                .andExpect(jsonPath("$.priority")
                        .value("HIGH"))
                .andExpect(jsonPath("$.status")
                        .value("OPEN"))
                .andExpect(jsonPath("$.source")
                        .value("USER"))
                .andExpect(jsonPath("$.requesterUserId")
                        .value("101"))
                .andExpect(jsonPath("$.createdAt")
                        .value("2026-08-08T01:00:00Z"));

        verify(ticketQueryService).getByTicketNo(
                "TKT-P1"
        );
    }

    @Test
    void shouldReturnNotFoundWhenTicketIsInvisible()
            throws Exception {
        when(ticketQueryService.getByTicketNo(
                "TKT-MISSING"
        )).thenThrow(new TicketNotFoundException());

        mockMvc.perform(get(
                        "/api/v1/tickets/{ticketNo}",
                        "TKT-MISSING"
                ))
                .andExpect(status().isNotFound())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                "application/problem+json"
                        ))
                .andExpect(jsonPath("$.title")
                        .value("Ticket not found"))
                .andExpect(jsonPath("$.detail")
                        .value("Ticket not found"))
                .andExpect(jsonPath("$.errorCode")
                        .value("TICKET_NOT_FOUND"));
    }

    @Test
    void shouldListTicketsWithFiltersAndCursor()
            throws Exception {
        TicketListQuery expectedQuery =
                new TicketListQuery(
                        TicketStatus.OPEN,
                        TicketPriority.HIGH,
                        2,
                        "current-cursor"
                );

        TicketSummaryResponse summary =
                new TicketSummaryResponse(
                        "903",
                        "TKT-903",
                        "Production incident",
                        TicketPriority.HIGH,
                        TicketStatus.OPEN,
                        TicketSource.USER,
                        "101",
                        null,
                        0,
                        Instant.parse(
                                "2026-08-08T03:00:00Z"
                        ),
                        Instant.parse(
                                "2026-08-08T03:00:00Z"
                        )
                );

        when(ticketQueryService.list(
                expectedQuery
        )).thenReturn(new TicketListResponse(
                List.of(summary),
                "next-cursor",
                true
        ));

        mockMvc.perform(get("/api/v1/tickets")
                        .param("status", "OPEN")
                        .param("priority", "HIGH")
                        .param("limit", "2")
                        .param(
                                "cursor",
                                "current-cursor"
                        ))
                .andExpect(status().isOk())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                "application/json"
                        ))
                .andExpect(jsonPath("$.items.length()")
                        .value(1))
                .andExpect(jsonPath(
                        "$.items[0].ticketId"
                ).value("903"))
                .andExpect(jsonPath(
                        "$.items[0].ticketNo"
                ).value("TKT-903"))
                .andExpect(jsonPath(
                        "$.items[0].priority"
                ).value("HIGH"))
                .andExpect(jsonPath(
                        "$.items[0].status"
                ).value("OPEN"))
                .andExpect(jsonPath(
                        "$.nextCursor"
                ).value("next-cursor"))
                .andExpect(jsonPath("$.hasMore")
                        .value(true));

        verify(ticketQueryService).list(
                expectedQuery
        );
    }

    @Test
    void shouldRejectInvalidListLimit()
            throws Exception {
        mockMvc.perform(get("/api/v1/tickets")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                "application/problem+json"
                        ))
                .andExpect(jsonPath("$.title")
                        .value("Invalid ticket query"))
                .andExpect(jsonPath("$.errorCode")
                        .value("INVALID_TICKET_QUERY"))
                .andExpect(jsonPath("$.detail")
                        .value(
                                "limit must be between 1 and 100"
                        ));

        verifyNoInteractions(ticketQueryService);
    }

    @Test
    void shouldRejectInvalidListCursor()
            throws Exception {
        TicketListQuery query =
                new TicketListQuery(
                        null,
                        null,
                        20,
                        "invalid-cursor"
                );

        when(ticketQueryService.list(query))
                .thenThrow(
                        new InvalidTicketQueryException(
                                "Invalid ticket query cursor"
                        )
                );

        mockMvc.perform(get("/api/v1/tickets")
                        .param(
                                "cursor",
                                "invalid-cursor"
                        ))
                .andExpect(status().isBadRequest())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                "application/problem+json"
                        ))
                .andExpect(jsonPath("$.title")
                        .value("Invalid ticket query"))
                .andExpect(jsonPath("$.errorCode")
                        .value("INVALID_TICKET_QUERY"))
                .andExpect(jsonPath("$.detail")
                        .value(
                                "Invalid ticket query cursor"
                        ));
    }

    @Test
    void shouldChangeTicketStatus() throws Exception {
        Instant updatedAt = Instant.parse(
                "2026-08-09T07:01:00Z"
        );

        ChangeTicketStatusRequest request =
                new ChangeTicketStatusRequest(
                        TicketStatus.IN_PROGRESS,
                        0
                );

        when(changeTicketStatusService.changeStatus(
                "TKT-P1",
                request
        )).thenReturn(new ChangeTicketStatusResponse(
                "901",
                "TKT-P1",
                TicketStatus.OPEN,
                TicketStatus.IN_PROGRESS,
                1,
                null,
                updatedAt
        ));

        mockMvc.perform(patch(
                        "/api/v1/tickets/{ticketNo}/status",
                        "TKT-P1"
                )
                        .contentType("application/json")
                        .content("""
                            {
                              "targetStatus": "IN_PROGRESS",
                              "expectedVersion": 0
                            }
                            """))
                .andExpect(status().isOk())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                "application/json"
                        ))
                .andExpect(jsonPath("$.ticketId")
                        .value("901"))
                .andExpect(jsonPath("$.ticketNo")
                        .value("TKT-P1"))
                .andExpect(jsonPath("$.previousStatus")
                        .value("OPEN"))
                .andExpect(jsonPath("$.currentStatus")
                        .value("IN_PROGRESS"))
                .andExpect(jsonPath("$.version")
                        .value(1))
                .andExpect(jsonPath("$.closedAt")
                        .doesNotExist())
                .andExpect(jsonPath("$.updatedAt")
                        .value("2026-08-09T07:01:00Z"));

        verify(changeTicketStatusService).changeStatus(
                "TKT-P1",
                request
        );
    }

    @Test
    void shouldRejectInvalidStatusChangeRequest()
            throws Exception {
        mockMvc.perform(patch(
                        "/api/v1/tickets/{ticketNo}/status",
                        "TKT-P1"
                )
                        .contentType("application/json")
                        .content("""
                            {
                              "expectedVersion": -1
                            }
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                "application/problem+json"
                        ))
                .andExpect(jsonPath("$.errorCode")
                        .value("VALIDATION_FAILED"))
                .andExpect(jsonPath(
                        "$.errors.targetStatus"
                ).value("targetStatus is required"))
                .andExpect(jsonPath(
                        "$.errors.expectedVersion"
                ).value(
                        "expectedVersion must not be negative"
                ));

        verifyNoInteractions(
                changeTicketStatusService
        );
    }

    @Test
    void shouldRejectUnknownTargetStatus()
            throws Exception {
        mockMvc.perform(patch(
                        "/api/v1/tickets/{ticketNo}/status",
                        "TKT-P1"
                )
                        .contentType("application/json")
                        .content("""
                            {
                              "targetStatus": "UNKNOWN",
                              "expectedVersion": 0
                            }
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                "application/problem+json"
                        ))
                .andExpect(jsonPath("$.errorCode")
                        .value("MALFORMED_REQUEST"));

        verifyNoInteractions(
                changeTicketStatusService
        );
    }

    @Test
    void shouldReturnNotFoundWhenChangingInvisibleTicket()
            throws Exception {
        ChangeTicketStatusRequest request =
                new ChangeTicketStatusRequest(
                        TicketStatus.IN_PROGRESS,
                        0
                );

        when(changeTicketStatusService.changeStatus(
                "TKT-MISSING",
                request
        )).thenThrow(new TicketNotFoundException());

        mockMvc.perform(patch(
                        "/api/v1/tickets/{ticketNo}/status",
                        "TKT-MISSING"
                )
                        .contentType("application/json")
                        .content("""
                            {
                              "targetStatus": "IN_PROGRESS",
                              "expectedVersion": 0
                            }
                            """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode")
                        .value("TICKET_NOT_FOUND"));
    }

    @Test
    void shouldReturnConflictForInvalidStatusTransition()
            throws Exception {
        ChangeTicketStatusRequest request =
                new ChangeTicketStatusRequest(
                        TicketStatus.CLOSED,
                        0
                );

        when(changeTicketStatusService.changeStatus(
                "TKT-P1",
                request
        )).thenThrow(
                new InvalidTicketStatusTransitionException(
                        TicketStatus.OPEN,
                        TicketStatus.CLOSED
                )
        );

        mockMvc.perform(patch(
                        "/api/v1/tickets/{ticketNo}/status",
                        "TKT-P1"
                )
                        .contentType("application/json")
                        .content("""
                            {
                              "targetStatus": "CLOSED",
                              "expectedVersion": 0
                            }
                            """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode")
                        .value(
                                "INVALID_TICKET_STATUS_TRANSITION"
                        ))
                .andExpect(jsonPath("$.currentStatus")
                        .value("OPEN"))
                .andExpect(jsonPath("$.targetStatus")
                        .value("CLOSED"))
                .andExpect(jsonPath("$.detail")
                        .value(
                                "Cannot transition ticket "
                                        + "from OPEN to CLOSED"
                        ));
    }

    @Test
    void shouldReturnConflictForStaleVersion()
            throws Exception {
        ChangeTicketStatusRequest request =
                new ChangeTicketStatusRequest(
                        TicketStatus.IN_PROGRESS,
                        0
                );

        when(changeTicketStatusService.changeStatus(
                "TKT-P1",
                request
        )).thenThrow(
                new TicketVersionConflictException()
        );

        mockMvc.perform(patch(
                        "/api/v1/tickets/{ticketNo}/status",
                        "TKT-P1"
                )
                        .contentType("application/json")
                        .content("""
                            {
                              "targetStatus": "IN_PROGRESS",
                              "expectedVersion": 0
                            }
                            """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title")
                        .value("Ticket version conflict"))
                .andExpect(jsonPath("$.detail")
                        .value(
                                "Ticket was modified "
                                        + "by another request"
                        ))
                .andExpect(jsonPath("$.errorCode")
                        .value("TICKET_VERSION_CONFLICT"));
    }
}