package com.nexusagent.ticket.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nexusagent.ticket.domain.TicketPriority;
import com.nexusagent.ticket.domain.TicketStatus;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

    private final CreateTicketService createTicketService;
    private final TicketQueryService ticketQueryService;

    public TicketController(
            CreateTicketService createTicketService,
            TicketQueryService ticketQueryService
    ) {
        this.createTicketService = createTicketService;
        this.ticketQueryService = ticketQueryService;
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

    @GetMapping
    public TicketListResponse list(
            @RequestParam(
                    name = "status",
                    required = false
            )
            TicketStatus status,
            @RequestParam(
                    name = "priority",
                    required = false
            )
            TicketPriority priority,
            @RequestParam(
                    name = "limit",
                    defaultValue = "20"
            )
            int limit,
            @RequestParam(
                    name = "cursor",
                    required = false
            )
            String cursor
    ) {
        return ticketQueryService.list(
                new TicketListQuery(
                        status,
                        priority,
                        limit,
                        cursor
                )
        );
    }

    @GetMapping("/{ticketNo}")
    public TicketDetailResponse getByTicketNo(
            @PathVariable("ticketNo") String ticketNo
    ) {
        return ticketQueryService.getByTicketNo(
                ticketNo
        );
    }
}