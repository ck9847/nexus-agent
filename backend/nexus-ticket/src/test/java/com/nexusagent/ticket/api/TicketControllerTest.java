package com.nexusagent.ticket.api;

import com.nexusagent.ticket.domain.TicketStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
}