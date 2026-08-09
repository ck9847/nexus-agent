package com.nexusagent.agent.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {

    private final CreateAgentService createAgentService;
    private final AgentQueryService agentQueryService;
    private final ChangeAgentStatusService changeAgentStatusService;

    public AgentController(
            CreateAgentService createAgentService,
            AgentQueryService agentQueryService,
            ChangeAgentStatusService changeAgentStatusService
    ) {
        this.createAgentService = createAgentService;
        this.agentQueryService = agentQueryService;
        this.changeAgentStatusService =
                changeAgentStatusService;
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

    @GetMapping("/{agentCode}")
    public AgentDetailResponse getByCode(
            @PathVariable("agentCode")
            String agentCode
    ) {
        return agentQueryService.getByCode(
                agentCode
        );
    }

    @PatchMapping("/{agentCode}/status")
    public ChangeAgentStatusResponse changeStatus(
            @PathVariable("agentCode")
            String agentCode,
            @Valid @RequestBody
            ChangeAgentStatusRequest request
    ) {
        return changeAgentStatusService.changeStatus(
                agentCode,
                request
        );
    }
}