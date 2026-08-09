package com.nexusagent.agent.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {

    private final CreateAgentService createAgentService;

    public AgentController(
            CreateAgentService createAgentService
    ) {
        this.createAgentService = createAgentService;
    }

    @PostMapping
    public ResponseEntity<CreateAgentResponse> create(
            @Valid @RequestBody
            CreateAgentRequest request
    ) {
        CreateAgentResponse response =
                createAgentService.create(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }
}