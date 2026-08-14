package com.nexusagent.conversation.internal;

import com.nexusagent.model.api.ChatModelMessage;
import com.nexusagent.model.api.ChatModelRequest;
import com.nexusagent.model.api.ChatModelRole;
import com.nexusagent.model.api.ChatModelToolCall;
import com.nexusagent.tool.internal.CreateTicketToolJsonCodec;
import com.nexusagent.tool.internal.CreateTicketToolOutput;
import com.nexusagent.tool.internal.ExecuteCreateTicketToolResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Assembles the second-round continuation request purely in memory.
 *
 * <p>After the tool execution has already committed its TOOL result
 * message and the final ASSISTANT/CREATING placeholder, no database
 * state can fail this step anymore. The service therefore carries no
 * transaction and no mapper access: it only verifies that the
 * prepared turn, the completed tool call and the tool result describe
 * one coherent chain, reuses the first request verbatim and appends
 * the structured tool-call exchange.
 *
 * <p>The continuation request declares no tools, so the current
 * version cannot enter an infinite tool loop.
 */
@Service
public class DefaultPrepareConversationToolContinuationService
        implements PrepareConversationToolContinuationService {

    private static final String TOOL_NAME = "create_ticket";

    private final CreateTicketToolJsonCodec ticketToolJsonCodec;

    public DefaultPrepareConversationToolContinuationService(
            CreateTicketToolJsonCodec ticketToolJsonCodec
    ) {
        this.ticketToolJsonCodec = Objects.requireNonNull(
                ticketToolJsonCodec
        );
    }

    @Override
    public PreparedConversationToolContinuation prepare(
            PreparedConversationTurn prepared,
            CompletedConversationToolCall completedToolCall,
            ExecuteCreateTicketToolResult toolResult
    ) {
        Objects.requireNonNull(
                prepared,
                "prepared must not be null"
        );
        Objects.requireNonNull(
                completedToolCall,
                "completedToolCall must not be null"
        );
        Objects.requireNonNull(
                toolResult,
                "toolResult must not be null"
        );

        requireMatchingScope(
                prepared,
                completedToolCall,
                toolResult
        );
        requireConsecutiveSequences(
                completedToolCall,
                toolResult
        );
        requireMatchingVersion(prepared, toolResult);

        ChatModelToolCall toolCall =
                completedToolCall.toolCall();

        String outputJson = ticketToolJsonCodec.encodeOutput(
                new CreateTicketToolOutput(
                        toolResult.ticketId(),
                        toolResult.ticketNo(),
                        toolResult.ticketStatus()
                )
        );

        ChatModelRequest firstRequest =
                prepared.modelRequest();

        List<ChatModelMessage> messages =
                new ArrayList<>(
                        firstRequest.messages().size() + 2
                );

        messages.addAll(firstRequest.messages());

        messages.add(new ChatModelMessage(
                ChatModelRole.ASSISTANT,
                null,
                List.of(toolCall),
                null
        ));

        messages.add(new ChatModelMessage(
                ChatModelRole.TOOL,
                outputJson,
                List.of(),
                toolCall.id()
        ));

        ChatModelRequest modelRequest =
                new ChatModelRequest(
                        firstRequest.modelName(),
                        firstRequest.systemPrompt(),
                        firstRequest.options(),
                        messages,
                        List.of()
                );

        return new PreparedConversationToolContinuation(
                prepared.tenantId(),
                prepared.userId(),
                prepared.conversationId(),
                prepared.agent(),
                toolResult.toolExecutionId(),
                toolResult.resultMessageId(),
                toolResult.resultMessageSequenceNo(),
                toolResult.assistantMessageId(),
                toolResult.assistantSequenceNo(),
                toolResult.conversationVersion(),
                toolResult.assistantPreparedAt(),
                toolCall,
                modelRequest
        );
    }

    private static void requireMatchingScope(
            PreparedConversationTurn prepared,
            CompletedConversationToolCall completedToolCall,
            ExecuteCreateTicketToolResult toolResult
    ) {
        if (prepared.tenantId()
                != completedToolCall.tenantId()
                || prepared.userId()
                != completedToolCall.userId()
                || prepared.conversationId()
                != completedToolCall.conversationId()
                || prepared.agent().agentId()
                != completedToolCall.agentId()) {
            throw new IllegalArgumentException(
                    "Tool call must belong to "
                            + "the prepared turn"
            );
        }

        if (completedToolCall.assistantMessageId()
                != prepared.assistantMessageId()
                || completedToolCall.assistantSequenceNo()
                != prepared.assistantSequenceNo()) {
            throw new IllegalArgumentException(
                    "Tool call must complete the "
                            + "prepared turn assistant message"
            );
        }

        if (completedToolCall.toolExecutionId()
                != toolResult.toolExecutionId()) {
            throw new IllegalArgumentException(
                    "Tool result must match "
                            + "the tool execution"
            );
        }

        if (!TOOL_NAME.equals(
                completedToolCall.toolCall().name()
        )) {
            throw new IllegalArgumentException(
                    "Only create_ticket tool calls "
                            + "can be continued"
            );
        }

        if (toolResult.resultMessageId()
                == completedToolCall.assistantMessageId()
                || toolResult.assistantMessageId()
                == completedToolCall.assistantMessageId()) {
            throw new IllegalArgumentException(
                    "Tool continuation message IDs "
                            + "must be distinct"
            );
        }
    }

    private static void requireConsecutiveSequences(
            CompletedConversationToolCall completedToolCall,
            ExecuteCreateTicketToolResult toolResult
    ) {
        if (toolResult.resultMessageSequenceNo()
                != completedToolCall.assistantSequenceNo() + 1) {
            throw new IllegalArgumentException(
                    "Tool result sequence must immediately "
                            + "follow the tool call "
                            + "assistant message"
            );
        }

        if (toolResult.assistantSequenceNo()
                != toolResult.resultMessageSequenceNo() + 1) {
            throw new IllegalArgumentException(
                    "Continuation assistant sequence must "
                            + "immediately follow the "
                            + "tool result message"
            );
        }
    }

    private static void requireMatchingVersion(
            PreparedConversationTurn prepared,
            ExecuteCreateTicketToolResult toolResult
    ) {
        if (toolResult.conversationVersion()
                != prepared.conversationVersion() + 1) {
            throw new IllegalArgumentException(
                    "Tool result version must immediately "
                            + "follow the prepared turn"
            );
        }
    }
}
