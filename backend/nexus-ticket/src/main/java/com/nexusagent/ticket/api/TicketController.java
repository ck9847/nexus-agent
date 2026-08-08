package com.nexusagent.ticket.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

    private final CreateTicketService createTicketService;

    public TicketController(
            CreateTicketService createTicketService
    ) {
        this.createTicketService = createTicketService;
    }

    @PostMapping
    public ResponseEntity<CreateTicketResponse> create(
            @Valid @RequestBody CreateTicketRequest request
    ) {
        CreateTicketResponse response =
                createTicketService.create(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }
}