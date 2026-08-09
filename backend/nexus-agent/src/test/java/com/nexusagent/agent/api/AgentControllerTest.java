package com.nexusagent.agent.api;

import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.agent.domain.AgentStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
}