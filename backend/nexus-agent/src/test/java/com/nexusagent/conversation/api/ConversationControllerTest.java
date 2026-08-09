package com.nexusagent.conversation.api;

import com.nexusagent.agent.api.AgentNotFoundException;
import com.nexusagent.conversation.domain.ConversationStatus;
import com.nexusagent.conversation.domain.MessageContentType;
import com.nexusagent.conversation.domain.MessageRole;
import com.nexusagent.conversation.domain.MessageStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConversationController.class)
class ConversationControllerTest {

    private static final String VALID_REQUEST =
            """
            {
              "agentCode": "support-agent",
              "title": "Production issue",
              "initialMessage": "The API returns HTTP 500."
            }
            """;

    private static final Instant NOW =
            Instant.parse(
                    "2026-08-09T10:15:30.123Z"
            );

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateConversationService
            createConversationService;

    @MockitoBean
    private AppendUserMessageService
            appendUserMessageService;

    @Test
    void shouldCreateConversation() throws Exception {
        CreatedMessageResponse message =
                new CreatedMessageResponse(
                        "902",
                        1L,
                        MessageRole.USER,
                        "The API returns HTTP 500.",
                        MessageContentType.TEXT,
                        MessageStatus.COMPLETED,
                        NOW
                );

        CreateConversationResponse response =
                new CreateConversationResponse(
                        "901",
                        "301",
                        "support-agent",
                        "Production issue",
                        ConversationStatus.ACTIVE,
                        0,
                        NOW,
                        NOW,
                        NOW,
                        message
                );

        when(createConversationService.create(any()))
                .thenReturn(response);

        mockMvc.perform(
                        post("/api/v1/conversations")
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(VALID_REQUEST)
                )
                .andExpect(status().isCreated())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                MediaType.APPLICATION_JSON
                        ))
                .andExpect(jsonPath("$.conversationId")
                        .value("901"))
                .andExpect(jsonPath("$.agentId")
                        .value("301"))
                .andExpect(jsonPath("$.agentCode")
                        .value("support-agent"))
                .andExpect(jsonPath("$.title")
                        .value("Production issue"))
                .andExpect(jsonPath("$.status")
                        .value("ACTIVE"))
                .andExpect(jsonPath("$.version")
                        .value(0))
                .andExpect(jsonPath("$.lastMessageAt")
                        .value(NOW.toString()))
                .andExpect(jsonPath("$.createdAt")
                        .value(NOW.toString()))
                .andExpect(jsonPath("$.updatedAt")
                        .value(NOW.toString()))
                .andExpect(jsonPath(
                        "$.initialMessage.messageId"
                ).value("902"))
                .andExpect(jsonPath(
                        "$.initialMessage.sequenceNo"
                ).value(1))
                .andExpect(jsonPath(
                        "$.initialMessage.role"
                ).value("USER"))
                .andExpect(jsonPath(
                        "$.initialMessage.content"
                ).value("The API returns HTTP 500."))
                .andExpect(jsonPath(
                        "$.initialMessage.contentType"
                ).value("TEXT"))
                .andExpect(jsonPath(
                        "$.initialMessage.status"
                ).value("COMPLETED"))
                .andExpect(jsonPath(
                        "$.initialMessage.createdAt"
                ).value(NOW.toString()));

        ArgumentCaptor<CreateConversationRequest>
                captor =
                ArgumentCaptor.forClass(
                        CreateConversationRequest.class
                );

        verify(createConversationService)
                .create(captor.capture());

        assertEquals(
                new CreateConversationRequest(
                        "support-agent",
                        "Production issue",
                        "The API returns HTTP 500."
                ),
                captor.getValue()
        );
    }

    @Test
    void shouldRejectInvalidConversationRequest()
            throws Exception {
        String invalidRequest =
                """
                {
                  "agentCode": "INVALID CODE",
                  "title": "%s",
                  "initialMessage": " "
                }
                """.formatted(
                        "t".repeat(256)
                );

        mockMvc.perform(
                        post("/api/v1/conversations")
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(invalidRequest)
                )
                .andExpect(status().isBadRequest())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                MediaType.APPLICATION_PROBLEM_JSON
                        ))
                .andExpect(jsonPath("$.errorCode")
                        .value("VALIDATION_FAILED"))
                .andExpect(jsonPath(
                        "$.errors.agentCode"
                ).exists())
                .andExpect(jsonPath(
                        "$.errors.title"
                ).exists())
                .andExpect(jsonPath(
                        "$.errors.initialMessage"
                ).exists());

        verifyNoInteractions(
                createConversationService
        );
    }

    @Test
    void shouldRejectMalformedRequest()
            throws Exception {
        mockMvc.perform(
                        post("/api/v1/conversations")
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("{")
                )
                .andExpect(status().isBadRequest())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                MediaType.APPLICATION_PROBLEM_JSON
                        ))
                .andExpect(jsonPath("$.errorCode")
                        .value("MALFORMED_REQUEST"));

        verifyNoInteractions(
                createConversationService
        );
    }

    @Test
    void shouldReturnNotFoundWhenActiveAgentIsInvisible()
            throws Exception {
        when(createConversationService.create(any()))
                .thenThrow(
                        new AgentNotFoundException()
                );

        String request =
                """
                {
                  "agentCode": "missing-agent",
                  "title": null,
                  "initialMessage": "Hello"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/conversations")
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(request)
                )
                .andExpect(status().isNotFound())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                MediaType.APPLICATION_PROBLEM_JSON
                        ))
                .andExpect(jsonPath("$.title")
                        .value("Active Agent not found"))
                .andExpect(jsonPath("$.detail")
                        .value("Active Agent not found"))
                .andExpect(jsonPath("$.errorCode")
                        .value("ACTIVE_AGENT_NOT_FOUND"))
                .andExpect(jsonPath("$.agentCode")
                        .doesNotExist())
                .andExpect(content().string(
                        not(containsString(
                                "missing-agent"
                        ))
                ));
    }

    @Test
    void shouldReturnBadRequestForInvalidServiceArgument()
            throws Exception {
        when(createConversationService.create(any()))
                .thenThrow(
                        new IllegalArgumentException(
                                "initialMessage "
                                        + "must not be blank"
                        )
                );

        mockMvc.perform(
                        post("/api/v1/conversations")
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(VALID_REQUEST)
                )
                .andExpect(status().isBadRequest())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                MediaType.APPLICATION_PROBLEM_JSON
                        ))
                .andExpect(jsonPath("$.title")
                        .value("Invalid request"))
                .andExpect(jsonPath("$.detail")
                        .value(
                                "initialMessage "
                                        + "must not be blank"
                        ))
                .andExpect(jsonPath("$.errorCode")
                        .value("INVALID_ARGUMENT"));
    }

    @Test
    void shouldAppendUserMessage() throws Exception {
        CreatedMessageResponse message =
                new CreatedMessageResponse(
                        "902",
                        2L,
                        MessageRole.USER,
                        "The payment still fails.",
                        MessageContentType.TEXT,
                        MessageStatus.COMPLETED,
                        NOW
                );

        AppendUserMessageResponse response =
                new AppendUserMessageResponse(
                        "901",
                        1,
                        NOW,
                        message
                );

        when(appendUserMessageService.append(
                any(),
                any()
        )).thenReturn(response);

        String request =
                """
                {
                  "content": "The payment still fails."
                }
                """;

        mockMvc.perform(
                        post(
                                "/api/v1/conversations/"
                                        + "901/messages"
                        )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(request)
                )
                .andExpect(status().isCreated())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                MediaType.APPLICATION_JSON
                        ))
                .andExpect(jsonPath("$.conversationId")
                        .value("901"))
                .andExpect(jsonPath(
                        "$.conversationVersion"
                ).value(1))
                .andExpect(jsonPath("$.lastMessageAt")
                        .value(NOW.toString()))
                .andExpect(jsonPath(
                        "$.message.messageId"
                ).value("902"))
                .andExpect(jsonPath(
                        "$.message.sequenceNo"
                ).value(2))
                .andExpect(jsonPath("$.message.role")
                        .value("USER"))
                .andExpect(jsonPath("$.message.content")
                        .value(
                                "The payment still fails."
                        ))
                .andExpect(jsonPath(
                        "$.message.contentType"
                ).value("TEXT"))
                .andExpect(jsonPath("$.message.status")
                        .value("COMPLETED"))
                .andExpect(jsonPath(
                        "$.message.createdAt"
                ).value(NOW.toString()));

        verify(appendUserMessageService)
                .append(
                        "901",
                        new AppendUserMessageRequest(
                                "The payment still fails."
                        )
                );
    }

    @Test
    void shouldRejectInvalidAppendMessageRequest()
            throws Exception {
        mockMvc.perform(
                        post(
                                "/api/v1/conversations/"
                                        + "901/messages"
                        )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                          "content": " "
                                        }
                                        """
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                MediaType.APPLICATION_PROBLEM_JSON
                        ))
                .andExpect(jsonPath("$.errorCode")
                        .value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.content")
                        .exists());

        mockMvc.perform(
                        post(
                                "/api/v1/conversations/"
                                        + "901/messages"
                        )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                          "content": "%s"
                                        }
                                        """.formatted(
                                                "x".repeat(50_001)
                                        )
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode")
                        .value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.content")
                        .exists());

        verifyNoInteractions(
                appendUserMessageService
        );
    }

    @Test
    void shouldRejectMalformedAppendMessageRequest()
            throws Exception {
        mockMvc.perform(
                        post(
                                "/api/v1/conversations/"
                                        + "901/messages"
                        )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("{")
                )
                .andExpect(status().isBadRequest())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                MediaType.APPLICATION_PROBLEM_JSON
                        ))
                .andExpect(jsonPath("$.errorCode")
                        .value("MALFORMED_REQUEST"));

        verifyNoInteractions(
                appendUserMessageService
        );
    }

    @Test
    void shouldHideMissingOrInaccessibleConversation()
            throws Exception {
        when(appendUserMessageService.append(
                any(),
                any()
        )).thenThrow(
                new ConversationNotFoundException()
        );

        mockMvc.perform(
                        post(
                                "/api/v1/conversations/"
                                        + "901/messages"
                        )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                          "content": "Hello"
                                        }
                                        """
                                )
                )
                .andExpect(status().isNotFound())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                MediaType.APPLICATION_PROBLEM_JSON
                        ))
                .andExpect(jsonPath("$.title")
                        .value("Conversation not found"))
                .andExpect(jsonPath("$.detail")
                        .value("Conversation not found"))
                .andExpect(jsonPath("$.errorCode")
                        .value("CONVERSATION_NOT_FOUND"))
                .andExpect(jsonPath("$.conversationId")
                        .doesNotExist())
                .andExpect(jsonPath("$.tenantId")
                        .doesNotExist())
                .andExpect(jsonPath("$.userId")
                        .doesNotExist());
    }

    @Test
    void shouldRejectNonActiveConversation()
            throws Exception {
        when(appendUserMessageService.append(
                any(),
                any()
        )).thenThrow(
                new ConversationNotActiveException(
                        ConversationStatus.COMPLETED
                )
        );

        mockMvc.perform(
                        post(
                                "/api/v1/conversations/"
                                        + "901/messages"
                        )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                          "content": "Hello"
                                        }
                                        """
                                )
                )
                .andExpect(status().isConflict())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                MediaType.APPLICATION_PROBLEM_JSON
                        ))
                .andExpect(jsonPath("$.errorCode")
                        .value(
                                "CONVERSATION_NOT_ACTIVE"
                        ))
                .andExpect(jsonPath("$.currentStatus")
                        .value("COMPLETED"));
    }

    @Test
    void shouldReturnBadRequestForInvalidConversationId()
            throws Exception {
        when(appendUserMessageService.append(
                any(),
                any()
        )).thenThrow(
                new IllegalArgumentException(
                        "conversationId must be "
                                + "a positive integer"
                )
        );

        mockMvc.perform(
                        post(
                                "/api/v1/conversations/"
                                        + "invalid/messages"
                        )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                          "content": "Hello"
                                        }
                                        """
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title")
                        .value("Invalid request"))
                .andExpect(jsonPath("$.errorCode")
                        .value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.detail")
                        .value(
                                "conversationId must be "
                                        + "a positive integer"
                        ));
    }
}