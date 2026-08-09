package com.nexusagent.agent.api;

import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.agent.domain.AgentStatus;
import com.nexusagent.agent.domain.AgentModelConfig;
import com.nexusagent.agent.domain.AgentVersionConflictException;
import com.nexusagent.agent.domain.InvalidAgentStatusTransitionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

@WebMvcTest(AgentController.class)
class AgentControllerTest {

    private static final String VALID_REQUEST =
            """
            {
              "code": "support-agent",
              "name": "Support Agent",
              "description": "Handles support requests.",
              "systemPrompt": "You are an enterprise support agent.",
              "modelProvider": "OPENAI",
              "modelName": "gpt-5-mini",
              "modelConfig": {
                "temperature": 0.2,
                "topP": 0.9,
                "maxOutputTokens": 2048
              }
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateAgentService createAgentService;

    @MockitoBean
    private AgentQueryService agentQueryService;

    @MockitoBean
    private ChangeAgentStatusService changeAgentStatusService;

    @Test
    void shouldCreateAgent() throws Exception {
        when(createAgentService.create(any()))
                .thenReturn(new CreateAgentResponse(
                        "901",
                        "support-agent",
                        AgentStatus.DRAFT,
                        0
                ));

        mockMvc.perform(post("/api/v1/agents")
                        .contentType("application/json")
                        .content(VALID_REQUEST))
                .andExpect(status().isCreated())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                "application/json"
                        ))
                .andExpect(jsonPath("$.agentId")
                        .value("901"))
                .andExpect(jsonPath("$.code")
                        .value("support-agent"))
                .andExpect(jsonPath("$.status")
                        .value("DRAFT"))
                .andExpect(jsonPath("$.version")
                        .value(0));

        ArgumentCaptor<CreateAgentRequest> captor =
                ArgumentCaptor.forClass(
                        CreateAgentRequest.class
                );

        verify(createAgentService).create(
                captor.capture()
        );

        CreateAgentRequest request =
                captor.getValue();

        assertAll(
                () -> assertEquals(
                        "support-agent",
                        request.code()
                ),
                () -> assertEquals(
                        AgentModelProvider.OPENAI,
                        request.modelProvider()
                ),
                () -> assertEquals(
                        new BigDecimal("0.2"),
                        request.modelConfig()
                                .temperature()
                ),
                () -> assertEquals(
                        new BigDecimal("0.9"),
                        request.modelConfig()
                                .topP()
                ),
                () -> assertEquals(
                        Integer.valueOf(2_048),
                        request.modelConfig()
                                .maxOutputTokens()
                )
        );
    }

    @Test
    void shouldRejectInvalidAgentRequest()
            throws Exception {
        mockMvc.perform(post("/api/v1/agents")
                        .contentType("application/json")
                        .content("""
                                {
                                  "code": "INVALID CODE",
                                  "name": "",
                                  "systemPrompt": "",
                                  "modelProvider": null,
                                  "modelName": "",
                                  "modelConfig": {
                                    "temperature": 2.1,
                                    "topP": 1.1,
                                    "maxOutputTokens": 0
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                "application/problem+json"
                        ))
                .andExpect(jsonPath("$.errorCode")
                        .value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.code")
                        .exists())
                .andExpect(jsonPath("$.errors.name")
                        .exists())
                .andExpect(jsonPath(
                        "$.errors.systemPrompt"
                ).exists())
                .andExpect(jsonPath(
                        "$.errors.modelProvider"
                ).exists())
                .andExpect(jsonPath(
                        "$.errors.modelName"
                ).exists())
                .andExpect(jsonPath(
                        "$.errors['modelConfig.temperature']"
                ).exists())
                .andExpect(jsonPath(
                        "$.errors['modelConfig.topP']"
                ).exists())
                .andExpect(jsonPath(
                        "$.errors['modelConfig.maxOutputTokens']"
                ).exists());

        verifyNoInteractions(createAgentService);
    }

    @Test
    void shouldRejectUnknownModelProvider()
            throws Exception {
        String request = VALID_REQUEST.replace(
                "\"OPENAI\"",
                "\"ANTHROPIC\""
        );

        mockMvc.perform(post("/api/v1/agents")
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                "application/problem+json"
                        ))
                .andExpect(jsonPath("$.title")
                        .value("Malformed request"))
                .andExpect(jsonPath("$.errorCode")
                        .value("MALFORMED_REQUEST"));

        verifyNoInteractions(createAgentService);
    }

    @Test
    void shouldReturnConflictForDuplicateCode()
            throws Exception {
        when(createAgentService.create(any()))
                .thenThrow(
                        new AgentCodeAlreadyExistsException(
                                "support-agent"
                        )
                );

        mockMvc.perform(post("/api/v1/agents")
                        .contentType("application/json")
                        .content(VALID_REQUEST))
                .andExpect(status().isConflict())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                "application/problem+json"
                        ))
                .andExpect(jsonPath("$.title")
                        .value("Agent already exists"))
                .andExpect(jsonPath("$.detail")
                        .value(
                                "Agent code already exists: "
                                        + "support-agent"
                        ))
                .andExpect(jsonPath("$.errorCode")
                        .value(
                                "AGENT_CODE_ALREADY_EXISTS"
                        ))
                .andExpect(jsonPath("$.agentCode")
                        .value("support-agent"));
    }

    @Test
    void shouldReturnForbiddenForNonAdministrator()
            throws Exception {
        when(createAgentService.create(any()))
                .thenThrow(
                        new AgentAdministrationForbiddenException()
                );

        mockMvc.perform(post("/api/v1/agents")
                        .contentType("application/json")
                        .content(VALID_REQUEST))
                .andExpect(status().isForbidden())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                "application/problem+json"
                        ))
                .andExpect(jsonPath("$.title")
                        .value(
                                "Agent administration forbidden"
                        ))
                .andExpect(jsonPath("$.detail")
                        .value(
                                "Administrator role is required "
                                        + "to manage agents"
                        ))
                .andExpect(jsonPath("$.errorCode")
                        .value(
                                "AGENT_ADMINISTRATION_FORBIDDEN"
                        ));
    }

    @Test
    void shouldReturnBadRequestForInvalidServiceArgument()
            throws Exception {
        when(createAgentService.create(any()))
                .thenThrow(
                        new IllegalArgumentException(
                                "modelName must not be blank"
                        )
                );

        mockMvc.perform(post("/api/v1/agents")
                        .contentType("application/json")
                        .content(VALID_REQUEST))
                .andExpect(status().isBadRequest())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                "application/problem+json"
                        ))
                .andExpect(jsonPath("$.title")
                        .value("Invalid request"))
                .andExpect(jsonPath("$.detail")
                        .value(
                                "modelName must not be blank"
                        ))
                .andExpect(jsonPath("$.errorCode")
                        .value("INVALID_ARGUMENT"));
    }

    @Test
    void shouldReturnAgentDetails() throws Exception {
        Instant createdAt = Instant.parse(
                "2026-08-09T01:00:00Z"
        );

        Instant updatedAt = Instant.parse(
                "2026-08-09T02:00:00Z"
        );

        when(agentQueryService.getByCode(
                "support-agent"
        )).thenReturn(new AgentDetailResponse(
                "901",
                "support-agent",
                "Support Agent",
                "Handles support requests.",
                "You are an enterprise support agent.",
                AgentModelProvider.OPENAI,
                "gpt-5-mini",
                new AgentModelConfig(
                        new BigDecimal("0.2"),
                        new BigDecimal("0.9"),
                        2_048
                ),
                AgentStatus.DRAFT,
                "101",
                0,
                createdAt,
                updatedAt
        ));

        mockMvc.perform(get(
                        "/api/v1/agents/{agentCode}",
                        "support-agent"
                ))
                .andExpect(status().isOk())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                "application/json"
                        ))
                .andExpect(jsonPath("$.agentId")
                        .value("901"))
                .andExpect(jsonPath("$.code")
                        .value("support-agent"))
                .andExpect(jsonPath("$.name")
                        .value("Support Agent"))
                .andExpect(jsonPath("$.systemPrompt")
                        .value(
                                "You are an enterprise "
                                        + "support agent."
                        ))
                .andExpect(jsonPath("$.modelProvider")
                        .value("OPENAI"))
                .andExpect(jsonPath("$.modelName")
                        .value("gpt-5-mini"))
                .andExpect(jsonPath(
                        "$.modelConfig.temperature"
                ).value(0.2))
                .andExpect(jsonPath(
                        "$.modelConfig.topP"
                ).value(0.9))
                .andExpect(jsonPath(
                        "$.modelConfig.maxOutputTokens"
                ).value(2_048))
                .andExpect(jsonPath("$.status")
                        .value("DRAFT"))
                .andExpect(jsonPath("$.createdByUserId")
                        .value("101"))
                .andExpect(jsonPath("$.version")
                        .value(0))
                .andExpect(jsonPath("$.createdAt")
                        .value(
                                "2026-08-09T01:00:00Z"
                        ))
                .andExpect(jsonPath("$.updatedAt")
                        .value(
                                "2026-08-09T02:00:00Z"
                        ));

        verify(agentQueryService).getByCode(
                "support-agent"
        );
    }

    @Test
    void shouldReturnNotFoundWhenAgentIsInvisible()
            throws Exception {
        when(agentQueryService.getByCode(
                "missing-agent"
        )).thenThrow(new AgentNotFoundException());

        mockMvc.perform(get(
                        "/api/v1/agents/{agentCode}",
                        "missing-agent"
                ))
                .andExpect(status().isNotFound())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                "application/problem+json"
                        ))
                .andExpect(jsonPath("$.title")
                        .value("Agent not found"))
                .andExpect(jsonPath("$.detail")
                        .value("Agent not found"))
                .andExpect(jsonPath("$.errorCode")
                        .value("AGENT_NOT_FOUND"))
                .andExpect(jsonPath("$.agentCode")
                        .doesNotExist());
    }

    @Test
    void shouldChangeAgentStatus() throws Exception {
        Instant updatedAt = Instant.parse(
                "2026-08-09T03:00:00Z"
        );

        ChangeAgentStatusRequest request =
                new ChangeAgentStatusRequest(
                        AgentStatus.ACTIVE,
                        0
                );

        when(changeAgentStatusService.changeStatus(
                "support-agent",
                request
        )).thenReturn(new ChangeAgentStatusResponse(
                "901",
                "support-agent",
                AgentStatus.DRAFT,
                AgentStatus.ACTIVE,
                1,
                updatedAt
        ));

        mockMvc.perform(patch(
                        "/api/v1/agents/{agentCode}/status",
                        "support-agent"
                )
                        .contentType("application/json")
                        .content("""
                            {
                              "targetStatus": "ACTIVE",
                              "expectedVersion": 0
                            }
                            """))
                .andExpect(status().isOk())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                "application/json"
                        ))
                .andExpect(jsonPath("$.agentId")
                        .value("901"))
                .andExpect(jsonPath("$.code")
                        .value("support-agent"))
                .andExpect(jsonPath("$.previousStatus")
                        .value("DRAFT"))
                .andExpect(jsonPath("$.currentStatus")
                        .value("ACTIVE"))
                .andExpect(jsonPath("$.version")
                        .value(1))
                .andExpect(jsonPath("$.updatedAt")
                        .value(
                                "2026-08-09T03:00:00Z"
                        ));

        verify(changeAgentStatusService).changeStatus(
                "support-agent",
                request
        );
    }

    @Test
    void shouldRejectInvalidStatusChangeRequest()
            throws Exception {
        mockMvc.perform(patch(
                        "/api/v1/agents/{agentCode}/status",
                        "support-agent"
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
                changeAgentStatusService
        );
    }

    @Test
    void shouldRejectUnknownTargetStatus()
            throws Exception {
        mockMvc.perform(patch(
                        "/api/v1/agents/{agentCode}/status",
                        "support-agent"
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
                changeAgentStatusService
        );
    }

    @Test
    void shouldReturnNotFoundWhenChangingInvisibleAgent()
            throws Exception {
        ChangeAgentStatusRequest request =
                new ChangeAgentStatusRequest(
                        AgentStatus.ACTIVE,
                        0
                );

        when(changeAgentStatusService.changeStatus(
                "missing-agent",
                request
        )).thenThrow(new AgentNotFoundException());

        mockMvc.perform(patch(
                        "/api/v1/agents/{agentCode}/status",
                        "missing-agent"
                )
                        .contentType("application/json")
                        .content("""
                            {
                              "targetStatus": "ACTIVE",
                              "expectedVersion": 0
                            }
                            """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode")
                        .value("AGENT_NOT_FOUND"));
    }

    @Test
    void shouldReturnConflictForInvalidStatusTransition()
            throws Exception {
        ChangeAgentStatusRequest request =
                new ChangeAgentStatusRequest(
                        AgentStatus.DISABLED,
                        0
                );

        when(changeAgentStatusService.changeStatus(
                "support-agent",
                request
        )).thenThrow(
                new InvalidAgentStatusTransitionException(
                        AgentStatus.DRAFT,
                        AgentStatus.DISABLED
                )
        );

        mockMvc.perform(patch(
                        "/api/v1/agents/{agentCode}/status",
                        "support-agent"
                )
                        .contentType("application/json")
                        .content("""
                            {
                              "targetStatus": "DISABLED",
                              "expectedVersion": 0
                            }
                            """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title")
                        .value(
                                "Invalid agent status transition"
                        ))
                .andExpect(jsonPath("$.detail")
                        .value(
                                "Cannot transition agent "
                                        + "from DRAFT to DISABLED"
                        ))
                .andExpect(jsonPath("$.errorCode")
                        .value(
                                "INVALID_AGENT_STATUS_TRANSITION"
                        ))
                .andExpect(jsonPath("$.currentStatus")
                        .value("DRAFT"))
                .andExpect(jsonPath("$.targetStatus")
                        .value("DISABLED"));
    }

    @Test
    void shouldReturnConflictForStaleVersion()
            throws Exception {
        ChangeAgentStatusRequest request =
                new ChangeAgentStatusRequest(
                        AgentStatus.ACTIVE,
                        0
                );

        when(changeAgentStatusService.changeStatus(
                "support-agent",
                request
        )).thenThrow(
                new AgentVersionConflictException()
        );

        mockMvc.perform(patch(
                        "/api/v1/agents/{agentCode}/status",
                        "support-agent"
                )
                        .contentType("application/json")
                        .content("""
                            {
                              "targetStatus": "ACTIVE",
                              "expectedVersion": 0
                            }
                            """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title")
                        .value("Agent version conflict"))
                .andExpect(jsonPath("$.detail")
                        .value(
                                "Agent was modified "
                                        + "by another request"
                        ))
                .andExpect(jsonPath("$.errorCode")
                        .value(
                                "AGENT_VERSION_CONFLICT"
                        ));
    }
}