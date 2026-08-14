package com.nexusagent.conversation.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.agent.api.ActiveAgentRuntime;
import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.model.api.ChatModelMessage;
import com.nexusagent.model.api.ChatModelOptions;
import com.nexusagent.model.api.ChatModelRequest;
import com.nexusagent.model.api.ChatModelRole;
import com.nexusagent.model.api.ChatModelToolCall;
import com.nexusagent.model.api.ChatTokenUsage;
import com.nexusagent.ticket.domain.TicketStatus;
import com.nexusagent.tool.internal.CreateTicketToolJsonCodec;
import com.nexusagent.tool.internal.CreateTicketToolOutput;
import com.nexusagent.tool.internal.ExecuteCreateTicketToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultPrepareConversationToolContinuationServiceTest {

    private static final long TENANT_ID = 202L;
    private static final long USER_ID = 101L;
    private static final long CONVERSATION_ID = 901L;
    private static final long AGENT_ID = 500L;
    private static final long USER_MESSAGE_ID = 1001L;
    private static final long ASSISTANT_MESSAGE_ID = 1002L;
    private static final long TOOL_EXECUTION_ID = 7001L;
    private static final long TOOL_MESSAGE_ID = 8001L;
    private static final long FINAL_ASSISTANT_MESSAGE_ID = 8002L;

    private static final String TOOL_CALL_ID = "call-1";
    private static final String MODEL_NAME = "gpt-5-mini";
    private static final String SYSTEM_PROMPT =
            "You are a support agent.";

    private static final Instant TURN_PREPARED_AT =
            Instant.parse("2026-08-13T10:15:30.123Z");

    private static final Instant TOOL_PREPARED_AT =
            Instant.parse("2026-08-13T10:15:32.123Z");

    private static final String OUTPUT_JSON =
            "{\"ticketId\":\"9001\","
                    + "\"ticketNo\":\"TKT-A1\","
                    + "\"status\":\"OPEN\"}";

    private static final ActiveAgentRuntime AGENT =
            new ActiveAgentRuntime(
                    AGENT_ID,
                    TENANT_ID,
                    "support-agent",
                    SYSTEM_PROMPT,
                    AgentModelProvider.OPENAI,
                    MODEL_NAME,
                    null
            );

    private static final ChatModelRequest FIRST_REQUEST =
            new ChatModelRequest(
                    MODEL_NAME,
                    SYSTEM_PROMPT,
                    ChatModelOptions.defaults(),
                    List.of(
                            ChatModelMessage.user("Earlier message"),
                            ChatModelMessage.user(
                                    "Please create a ticket"
                            )
                    ),
                    List.of()
            );

    private static final ChatModelToolCall TOOL_CALL =
            new ChatModelToolCall(
                    TOOL_CALL_ID,
                    "create_ticket",
                    new ObjectMapper().createObjectNode()
                            .put("title", "Server down")
            );

    @Mock
    private CreateTicketToolJsonCodec ticketToolJsonCodec;

    private DefaultPrepareConversationToolContinuationService
            service;

    @BeforeEach
    void setUp() {
        service =
                new DefaultPrepareConversationToolContinuationService(
                        ticketToolJsonCodec
                );
    }

    @Test
    void shouldAssembleContinuationReusingFirstRequest() {
        when(ticketToolJsonCodec.encodeOutput(
                new CreateTicketToolOutput(
                        "9001",
                        "TKT-A1",
                        TicketStatus.OPEN
                )
        )).thenReturn(OUTPUT_JSON);

        PreparedConversationToolContinuation result =
                service.prepare(
                        prepared(),
                        completedToolCall(),
                        toolResult()
                );

        assertEquals(TENANT_ID, result.tenantId());
        assertEquals(USER_ID, result.userId());
        assertEquals(
                CONVERSATION_ID,
                result.conversationId()
        );
        assertEquals(AGENT, result.agent());
        assertEquals(
                TOOL_EXECUTION_ID,
                result.toolExecutionId()
        );
        assertEquals(TOOL_MESSAGE_ID, result.resultMessageId());
        assertEquals(3L, result.resultMessageSequenceNo());
        assertEquals(
                FINAL_ASSISTANT_MESSAGE_ID,
                result.assistantMessageId()
        );
        assertEquals(4L, result.assistantSequenceNo());
        assertEquals(2, result.conversationVersion());
        assertEquals(
                TOOL_PREPARED_AT,
                result.preparedAt()
        );
        assertEquals(TOOL_CALL, result.toolCall());

        ChatModelRequest request = result.modelRequest();

        assertEquals(MODEL_NAME, request.modelName());
        assertEquals(SYSTEM_PROMPT, request.systemPrompt());
        assertEquals(
                FIRST_REQUEST.options(),
                request.options()
        );
        assertTrue(request.tools().isEmpty());

        assertEquals(
                List.of(
                        ChatModelMessage.user("Earlier message"),
                        ChatModelMessage.user(
                                "Please create a ticket"
                        ),
                        new ChatModelMessage(
                                ChatModelRole.ASSISTANT,
                                null,
                                List.of(TOOL_CALL),
                                null
                        ),
                        new ChatModelMessage(
                                ChatModelRole.TOOL,
                                OUTPUT_JSON,
                                List.of(),
                                TOOL_CALL_ID
                        )
                ),
                request.messages()
        );
    }

    @Test
    void shouldEncodeOutputFromToolResult() {
        when(ticketToolJsonCodec.encodeOutput(
                new CreateTicketToolOutput(
                        "9001",
                        "TKT-A1",
                        TicketStatus.OPEN
                )
        )).thenReturn(OUTPUT_JSON);

        service.prepare(
                prepared(),
                completedToolCall(),
                toolResult()
        );

        ArgumentCaptor<CreateTicketToolOutput> captor =
                ArgumentCaptor.forClass(
                        CreateTicketToolOutput.class
                );

        verify(ticketToolJsonCodec).encodeOutput(
                captor.capture()
        );

        CreateTicketToolOutput encoded = captor.getValue();

        assertEquals("9001", encoded.ticketId());
        assertEquals("TKT-A1", encoded.ticketNo());
        assertEquals(TicketStatus.OPEN, encoded.status());
    }

    @Test
    void shouldRejectNullPrepared() {
        assertThrows(
                NullPointerException.class,
                () -> service.prepare(
                        null,
                        completedToolCall(),
                        toolResult()
                )
        );

        verifyNoInteractions(ticketToolJsonCodec);
    }

    @Test
    void shouldRejectNullCompletedToolCall() {
        assertThrows(
                NullPointerException.class,
                () -> service.prepare(
                        prepared(),
                        null,
                        toolResult()
                )
        );

        verifyNoInteractions(ticketToolJsonCodec);
    }

    @Test
    void shouldRejectNullToolResult() {
        assertThrows(
                NullPointerException.class,
                () -> service.prepare(
                        prepared(),
                        completedToolCall(),
                        null
                )
        );

        verifyNoInteractions(ticketToolJsonCodec);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mismatchedChains")
    void shouldRejectMismatchedChain(
            String description,
            PreparedConversationTurn prepared,
            CompletedConversationToolCall completedToolCall,
            ExecuteCreateTicketToolResult toolResult
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.prepare(
                        prepared,
                        completedToolCall,
                        toolResult
                )
        );

        verifyNoInteractions(ticketToolJsonCodec);
    }

    @Test
    void shouldRejectResultSequenceGap() {
        ExecuteCreateTicketToolResult gapped =
                new ExecuteCreateTicketToolResult(
                        TOOL_EXECUTION_ID,
                        "9001",
                        "TKT-A1",
                        TicketStatus.OPEN,
                        TOOL_MESSAGE_ID,
                        4L,
                        FINAL_ASSISTANT_MESSAGE_ID,
                        5L,
                        2,
                        TOOL_PREPARED_AT,
                        false
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.prepare(
                        prepared(),
                        completedToolCall(),
                        gapped
                )
        );

        verifyNoInteractions(ticketToolJsonCodec);
    }

    private static PreparedConversationTurn prepared() {
        return new PreparedConversationTurn(
                TENANT_ID,
                USER_ID,
                CONVERSATION_ID,
                AGENT,
                USER_MESSAGE_ID,
                1L,
                ASSISTANT_MESSAGE_ID,
                2L,
                1,
                TURN_PREPARED_AT,
                FIRST_REQUEST
        );
    }

    private static CompletedConversationToolCall completedToolCall() {
        return new CompletedConversationToolCall(
                TENANT_ID,
                USER_ID,
                CONVERSATION_ID,
                AGENT_ID,
                ASSISTANT_MESSAGE_ID,
                2L,
                TOOL_CALL,
                TOOL_EXECUTION_ID,
                MODEL_NAME,
                new ChatTokenUsage(3, 2),
                TURN_PREPARED_AT,
                TURN_PREPARED_AT
        );
    }

    private static ExecuteCreateTicketToolResult toolResult() {
        return new ExecuteCreateTicketToolResult(
                TOOL_EXECUTION_ID,
                "9001",
                "TKT-A1",
                TicketStatus.OPEN,
                TOOL_MESSAGE_ID,
                3L,
                FINAL_ASSISTANT_MESSAGE_ID,
                4L,
                2,
                TOOL_PREPARED_AT,
                false
        );
    }

    private static Stream<Arguments> mismatchedChains() {
        return Stream.of(
                Arguments.of(
                        "tool call tenant",
                        prepared(),
                        new CompletedConversationToolCall(
                                TENANT_ID + 1,
                                USER_ID,
                                CONVERSATION_ID,
                                AGENT_ID,
                                ASSISTANT_MESSAGE_ID,
                                2L,
                                TOOL_CALL,
                                TOOL_EXECUTION_ID,
                                MODEL_NAME,
                                new ChatTokenUsage(3, 2),
                                TURN_PREPARED_AT,
                                TURN_PREPARED_AT
                        ),
                        toolResult()
                ),
                Arguments.of(
                        "tool call user",
                        prepared(),
                        new CompletedConversationToolCall(
                                TENANT_ID,
                                USER_ID + 1,
                                CONVERSATION_ID,
                                AGENT_ID,
                                ASSISTANT_MESSAGE_ID,
                                2L,
                                TOOL_CALL,
                                TOOL_EXECUTION_ID,
                                MODEL_NAME,
                                new ChatTokenUsage(3, 2),
                                TURN_PREPARED_AT,
                                TURN_PREPARED_AT
                        ),
                        toolResult()
                ),
                Arguments.of(
                        "tool call conversation",
                        prepared(),
                        new CompletedConversationToolCall(
                                TENANT_ID,
                                USER_ID,
                                CONVERSATION_ID + 1,
                                AGENT_ID,
                                ASSISTANT_MESSAGE_ID,
                                2L,
                                TOOL_CALL,
                                TOOL_EXECUTION_ID,
                                MODEL_NAME,
                                new ChatTokenUsage(3, 2),
                                TURN_PREPARED_AT,
                                TURN_PREPARED_AT
                        ),
                        toolResult()
                ),
                Arguments.of(
                        "tool call agent",
                        prepared(),
                        new CompletedConversationToolCall(
                                TENANT_ID,
                                USER_ID,
                                CONVERSATION_ID,
                                AGENT_ID + 1,
                                ASSISTANT_MESSAGE_ID,
                                2L,
                                TOOL_CALL,
                                TOOL_EXECUTION_ID,
                                MODEL_NAME,
                                new ChatTokenUsage(3, 2),
                                TURN_PREPARED_AT,
                                TURN_PREPARED_AT
                        ),
                        toolResult()
                ),
                Arguments.of(
                        "assistant message id",
                        prepared(),
                        new CompletedConversationToolCall(
                                TENANT_ID,
                                USER_ID,
                                CONVERSATION_ID,
                                AGENT_ID,
                                ASSISTANT_MESSAGE_ID + 1,
                                2L,
                                TOOL_CALL,
                                TOOL_EXECUTION_ID,
                                MODEL_NAME,
                                new ChatTokenUsage(3, 2),
                                TURN_PREPARED_AT,
                                TURN_PREPARED_AT
                        ),
                        toolResult()
                ),
                Arguments.of(
                        "assistant sequence",
                        prepared(),
                        new CompletedConversationToolCall(
                                TENANT_ID,
                                USER_ID,
                                CONVERSATION_ID,
                                AGENT_ID,
                                ASSISTANT_MESSAGE_ID,
                                3L,
                                TOOL_CALL,
                                TOOL_EXECUTION_ID,
                                MODEL_NAME,
                                new ChatTokenUsage(3, 2),
                                TURN_PREPARED_AT,
                                TURN_PREPARED_AT
                        ),
                        toolResult()
                ),
                Arguments.of(
                        "tool execution id",
                        prepared(),
                        completedToolCall(),
                        new ExecuteCreateTicketToolResult(
                                TOOL_EXECUTION_ID + 1,
                                "9001",
                                "TKT-A1",
                                TicketStatus.OPEN,
                                TOOL_MESSAGE_ID,
                                3L,
                                FINAL_ASSISTANT_MESSAGE_ID,
                                4L,
                                2,
                                TOOL_PREPARED_AT,
                                false
                        )
                ),
                Arguments.of(
                        "tool call name",
                        prepared(),
                        new CompletedConversationToolCall(
                                TENANT_ID,
                                USER_ID,
                                CONVERSATION_ID,
                                AGENT_ID,
                                ASSISTANT_MESSAGE_ID,
                                2L,
                                new ChatModelToolCall(
                                        TOOL_CALL_ID,
                                        "other_tool",
                                        new ObjectMapper()
                                                .createObjectNode()
                                ),
                                TOOL_EXECUTION_ID,
                                MODEL_NAME,
                                new ChatTokenUsage(3, 2),
                                TURN_PREPARED_AT,
                                TURN_PREPARED_AT
                        ),
                        toolResult()
                ),
                Arguments.of(
                        "result id equals assistant id",
                        prepared(),
                        completedToolCall(),
                        new ExecuteCreateTicketToolResult(
                                TOOL_EXECUTION_ID,
                                "9001",
                                "TKT-A1",
                                TicketStatus.OPEN,
                                ASSISTANT_MESSAGE_ID,
                                3L,
                                FINAL_ASSISTANT_MESSAGE_ID,
                                4L,
                                2,
                                TOOL_PREPARED_AT,
                                false
                        )
                ),
                Arguments.of(
                        "final assistant id equals assistant id",
                        prepared(),
                        completedToolCall(),
                        new ExecuteCreateTicketToolResult(
                                TOOL_EXECUTION_ID,
                                "9001",
                                "TKT-A1",
                                TicketStatus.OPEN,
                                TOOL_MESSAGE_ID,
                                3L,
                                ASSISTANT_MESSAGE_ID,
                                4L,
                                2,
                                TOOL_PREPARED_AT,
                                false
                        )
                ),
                Arguments.of(
                        "conversation version",
                        prepared(),
                        completedToolCall(),
                        new ExecuteCreateTicketToolResult(
                                TOOL_EXECUTION_ID,
                                "9001",
                                "TKT-A1",
                                TicketStatus.OPEN,
                                TOOL_MESSAGE_ID,
                                3L,
                                FINAL_ASSISTANT_MESSAGE_ID,
                                4L,
                                3,
                                TOOL_PREPARED_AT,
                                false
                        )
                )
        );
    }
}
