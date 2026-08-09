package com.nexusagent.agent.api;

import com.nexusagent.agent.domain.AgentModelConfig;
import com.nexusagent.agent.domain.AgentModelProvider;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateAgentRequestValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory()
                    .getValidator();

    @Test
    void shouldAcceptValidCreateAgentRequest() {
        CreateAgentRequest request =
                new CreateAgentRequest(
                        "support-agent",
                        "Support Agent",
                        "Handles enterprise support requests.",
                        "You are an enterprise support agent.",
                        AgentModelProvider.OPENAI,
                        "gpt-5-mini",
                        new AgentModelConfig(
                                new BigDecimal("0.2"),
                                new BigDecimal("0.9"),
                                2_048
                        )
                );

        assertTrue(
                validator.validate(request).isEmpty()
        );
    }

    @Test
    void shouldRejectInvalidRequiredFieldsAndCode() {
        CreateAgentRequest request =
                new CreateAgentRequest(
                        "INVALID CODE",
                        "",
                        null,
                        "",
                        null,
                        "",
                        null
                );

        assertEquals(
                Set.of(
                        "code",
                        "name",
                        "systemPrompt",
                        "modelProvider",
                        "modelName"
                ),
                violationPaths(request)
        );
    }

    @Test
    void shouldRejectOversizedTextFields() {
        CreateAgentRequest request =
                new CreateAgentRequest(
                        "a".repeat(65),
                        "n".repeat(129),
                        "d".repeat(501),
                        "p".repeat(50_001),
                        AgentModelProvider.OPENAI,
                        "m".repeat(129),
                        null
                );

        assertEquals(
                Set.of(
                        "code",
                        "name",
                        "description",
                        "systemPrompt",
                        "modelName"
                ),
                violationPaths(request)
        );
    }

    @Test
    void shouldCascadeValidationToModelConfig() {
        CreateAgentRequest request =
                new CreateAgentRequest(
                        "support-agent",
                        "Support Agent",
                        null,
                        "You are an enterprise support agent.",
                        AgentModelProvider.OPENAI,
                        "gpt-5-mini",
                        new AgentModelConfig(
                                new BigDecimal("2.01"),
                                new BigDecimal("1.01"),
                                0
                        )
                );

        assertEquals(
                Set.of(
                        "modelConfig.temperature",
                        "modelConfig.topP",
                        "modelConfig.maxOutputTokens"
                ),
                violationPaths(request)
        );
    }

    private Set<String> violationPaths(
            CreateAgentRequest request
    ) {
        return validator.validate(request)
                .stream()
                .map(violation ->
                        violation.getPropertyPath()
                                .toString()
                )
                .collect(Collectors.toSet());
    }
}