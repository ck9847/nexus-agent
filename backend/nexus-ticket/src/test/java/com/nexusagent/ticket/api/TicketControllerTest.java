package com.nexusagent.ticket.api;

import com.nexusagent.ticket.domain.TicketStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.nexusagent.ticket.domain.TicketPriority;
import com.nexusagent.ticket.domain.TicketSource;

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

@WebMvcTest(TicketController.class)
class TicketControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateTicketService createTicketService;

    @MockitoBean
    private TicketQueryService ticketQueryService;

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
}