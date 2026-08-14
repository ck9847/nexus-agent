package com.nexusagent.conversation.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.agent.api.ActiveAgentRuntime;
import com.nexusagent.conversation.api.ConversationTurnStreamEvent;
import com.nexusagent.conversation.api.ConversationTurnStreamHandler;
import com.nexusagent.conversation.api.StreamConversationTurnService;
import com.nexusagent.conversation.internal.ConversationTurnTextStreamAccumulator.TextCompletion;
import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.model.api.ChatModelException;
import com.nexusagent.model.api.ChatModelGateway;
import com.nexusagent.model.api.ChatModelGatewayResolver;
import com.nexusagent.model.api.ChatModelRequest;
import com.nexusagent.model.api.ChatModelToolCall;
import com.nexusagent.tool.api.RegisterToolExecutionCommand;
import com.nexusagent.tool.api.RegisterToolExecutionResult;
import com.nexusagent.tool.api.RegisterToolExecutionService;
import com.nexusagent.tool.internal.AgentToolExecutionContext;
import com.nexusagent.tool.internal.CreateTicketChatToolDefinition;
import com.nexusagent.tool.internal.CreateTicketToolExecutionService;
import com.nexusagent.tool.internal.ExecuteCreateTicketToolResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Orchestrates a streaming conversation turn by driving the
 * prepare, model stream and complete lifecycle.
 *
 * <p>This service intentionally carries no transaction. Database
 * transactions are managed by the prepare, complete and fail services
 * individually (each {@code REQUIRES_NEW}), so the model network call
 * always happens outside of any database transaction.
 *
 * <p>The first model round may answer with plain text or with a
 * {@code create_ticket} tool call. A tool call triggers the full
 * tool round: register the execution, complete the first assistant
 * message, execute the tool, prepare the continuation and run a
 * second, tool-free model round whose result finalizes the
 * continuation assistant placeholder.
 */
@Service
public class DefaultStreamConversationTurnService
        implements StreamConversationTurnService {

    private static final String STREAM_DELIVERY_FAILED_MESSAGE =
            "Conversation turn stream delivery failed";

    private static final String GATEWAY_FAILED_MESSAGE =
            "Chat model gateway failed unexpectedly";

    private final PrepareConversationTurnService prepareService;
    private final ChatModelGatewayResolver gatewayResolver;
    private final CompleteConversationTurnService completeService;
    private final FailConversationTurnService failService;
    private final ObjectMapper objectMapper;
    private final CreateTicketChatToolDefinition createTicketTool;
    private final RegisterToolExecutionService
            registerToolExecutionService;
    private final CompleteConversationToolCallService
            completeToolCallService;
    private final CreateTicketToolExecutionService
            toolExecutionService;
    private final PrepareConversationToolContinuationService
            continuationService;

    public DefaultStreamConversationTurnService(
            PrepareConversationTurnService prepareService,
            ChatModelGatewayResolver gatewayResolver,
            CompleteConversationTurnService completeService,
            FailConversationTurnService failService,
            ObjectMapper objectMapper,
            CreateTicketChatToolDefinition createTicketTool,
            RegisterToolExecutionService
                    registerToolExecutionService,
            CompleteConversationToolCallService
                    completeToolCallService,
            CreateTicketToolExecutionService toolExecutionService,
            PrepareConversationToolContinuationService
                    continuationService
    ) {
        this.prepareService = Objects.requireNonNull(
                prepareService,
                "prepareService must not be null"
        );
        this.gatewayResolver = Objects.requireNonNull(
                gatewayResolver,
                "gatewayResolver must not be null"
        );
        this.completeService = Objects.requireNonNull(
                completeService,
                "completeService must not be null"
        );
        this.failService = Objects.requireNonNull(
                failService,
                "failService must not be null"
        );
        this.objectMapper = Objects.requireNonNull(
                objectMapper,
                "objectMapper must not be null"
        );
        this.createTicketTool = Objects.requireNonNull(
                createTicketTool,
                "createTicketTool must not be null"
        );
        this.registerToolExecutionService = Objects.requireNonNull(
                registerToolExecutionService,
                "registerToolExecutionService must not be null"
        );
        this.completeToolCallService = Objects.requireNonNull(
                completeToolCallService,
                "completeToolCallService must not be null"
        );
        this.toolExecutionService = Objects.requireNonNull(
                toolExecutionService,
                "toolExecutionService must not be null"
        );
        this.continuationService = Objects.requireNonNull(
                continuationService,
                "continuationService must not be null"
        );
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void stream(
            String conversationId,
            String content,
            ConversationTurnStreamHandler handler
    ) {
        Objects.requireNonNull(
                handler,
                "handler must not be null"
        );

        PreparedConversationTurn prepared =
                prepareService.prepare(
                        conversationId,
                        content
                );

        ConversationTurnModelCompletion firstCompletion;

        try {
            emitStarted(prepared, handler);
            firstCompletion = invokeFirstModel(prepared, handler);
            requireActiveConsumer(handler);
        } catch (ChatModelException failure) {
            markFailed(prepared, failure);
            throw failure;
        }

        if (firstCompletion
                instanceof ConversationTurnModelCompletion.Text text) {
            completeTextRound(prepared, text, handler);
            return;
        }

        completeToolRound(
                prepared,
                (ConversationTurnModelCompletion.ToolCall)
                        firstCompletion,
                handler
        );
    }

    private void completeTextRound(
            PreparedConversationTurn prepared,
            ConversationTurnModelCompletion.Text text,
            ConversationTurnStreamHandler handler
    ) {
        CompletedConversationTurn completed =
                completeService.complete(
                        prepared,
                        text.content(),
                        text.finishReason(),
                        text.usage()
                );

        // 必须在数据库完成事务成功之后发送最终事件。
        handler.onEvent(toCompletedEvent(
                prepared,
                completed
        ));
    }

    private void completeToolRound(
            PreparedConversationTurn prepared,
            ConversationTurnModelCompletion.ToolCall toolCompletion,
            ConversationTurnStreamHandler handler
    ) {
        ChatModelToolCall call = toolCompletion.call();

        RegisterToolExecutionResult registration;

        try {
            registration =
                    registerToolExecutionService.register(
                            new RegisterToolExecutionCommand(
                                    prepared.conversationId(),
                                    prepared.agent().agentId(),
                                    prepared.assistantMessageId(),
                                    call.id(),
                                    call.name(),
                                    call.arguments(),
                                    false,
                                    null
                            )
                    );
        } catch (RuntimeException registrationFailure) {
            markFailed(
                    prepared,
                    toModelFailure(registrationFailure)
            );
            throw registrationFailure;
        }

        AgentToolExecutionContext context =
                new AgentToolExecutionContext(
                        prepared.tenantId(),
                        prepared.userId(),
                        prepared.conversationId(),
                        prepared.agent().agentId(),
                        prepared.assistantMessageId(),
                        registration.toolExecutionId(),
                        call.id()
                );

        CompletedConversationToolCall completedToolCall;

        try {
            completedToolCall = completeToolCallService.complete(
                    prepared,
                    call,
                    toolCompletion.usage(),
                    registration.toolExecutionId()
            );
        } catch (RuntimeException completionFailure) {
            compensateCompletedToolCallFailure(
                    prepared,
                    context,
                    completionFailure
            );

            throw completionFailure;
        }

        try {
            requireActiveConsumer(handler);
        } catch (ChatModelException cancelled) {
            toolExecutionService.failPending(
                    context,
                    cancelled
            );
            throw cancelled;
        }

        ExecuteCreateTicketToolResult toolResult;

        try {
            toolResult = toolExecutionService.execute(context);
        } catch (RuntimeException executionFailure) {
            // 执行服务已经自行把 execution 补偿为 FAILED；
            // 首轮 ASSISTANT 已是 COMPLETED，不能再调用 failService。
            throw executionFailure;
        }

        AssistantMessageCompletionTarget continuationTarget =
                new AssistantMessageCompletionTargetSnapshot(
                        prepared.tenantId(),
                        prepared.userId(),
                        prepared.conversationId(),
                        prepared.agent(),
                        toolResult.assistantMessageId(),
                        toolResult.assistantSequenceNo(),
                        toolResult.conversationVersion(),
                        toolResult.assistantPreparedAt()
                );

        PreparedConversationToolContinuation continuation;

        try {
            continuation = continuationService.prepare(
                    prepared,
                    completedToolCall,
                    toolResult
            );
        } catch (RuntimeException continuationFailure) {
            markFailed(
                    continuationTarget,
                    toModelFailure(continuationFailure)
            );

            throw continuationFailure;
        }

        completeContinuationRound(continuation, handler);
    }

    private void completeContinuationRound(
            PreparedConversationToolContinuation continuation,
            ConversationTurnStreamHandler handler
    ) {
        TextCompletion secondCompletion;

        try {
            requireActiveConsumer(handler);
            secondCompletion = invokeTextModel(
                    continuation.modelRequest(),
                    continuation.agent(),
                    handler
            );
            requireActiveConsumer(handler);
        } catch (ChatModelException failure) {
            markFailed(continuation, failure);
            throw failure;
        }

        CompletedConversationTurn completed =
                completeService.complete(
                        continuation,
                        secondCompletion.content(),
                        secondCompletion.finishReason(),
                        secondCompletion.usage()
                );

        // 必须在数据库完成事务成功之后发送最终事件；
        // 最终事件使用续轮占位的消息 ID、序号与版本。
        handler.onEvent(toCompletedEvent(
                continuation,
                completed
        ));
    }

    private void compensateCompletedToolCallFailure(
            PreparedConversationTurn prepared,
            AgentToolExecutionContext context,
            RuntimeException failure
    ) {
        RuntimeException primary = null;

        try {
            toolExecutionService.failPending(context, failure);
        } catch (RuntimeException failPendingFailure) {
            primary = failPendingFailure;
        }

        try {
            markFailed(prepared, toModelFailure(failure));
        } catch (RuntimeException failTurnFailure) {
            if (primary != null) {
                failTurnFailure.addSuppressed(primary);
            }
            throw failTurnFailure;
        }

        if (primary != null) {
            throw primary;
        }
    }

    private ConversationTurnModelCompletion invokeFirstModel(
            PreparedConversationTurn prepared,
            ConversationTurnStreamHandler handler
    ) {
        try {
            ChatModelGateway gateway =
                    gatewayResolver.requireGateway(
                            prepared.agent().modelProvider()
                    );

            ChatModelRequest original =
                    prepared.modelRequest();

            ChatModelRequest firstRequest =
                    new ChatModelRequest(
                            original.modelName(),
                            original.systemPrompt(),
                            original.options(),
                            original.messages(),
                            List.of(createTicketTool.definition())
                    );

            ConversationTurnModelStreamAccumulator accumulator =
                    new ConversationTurnModelStreamAccumulator(
                            objectMapper,
                            handler
                    );

            gateway.stream(firstRequest, accumulator);

            return accumulator.requireCompletion();
        } catch (ConversationTurnStreamConsumerException exception) {
            throw new ChatModelException(
                    ChatModelErrorCategory.STREAM_INTERRUPTED,
                    STREAM_DELIVERY_FAILED_MESSAGE,
                    null,
                    exception.getCause()
            );
        } catch (ChatModelException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ChatModelException(
                    ChatModelErrorCategory.MALFORMED_RESPONSE,
                    GATEWAY_FAILED_MESSAGE,
                    null,
                    exception
            );
        }
    }

    private TextCompletion invokeTextModel(
            ChatModelRequest request,
            ActiveAgentRuntime agent,
            ConversationTurnStreamHandler handler
    ) {
        try {
            ChatModelGateway gateway =
                    gatewayResolver.requireGateway(
                            agent.modelProvider()
                    );

            ConversationTurnTextStreamAccumulator accumulator =
                    new ConversationTurnTextStreamAccumulator(handler);

            gateway.stream(request, accumulator);

            return accumulator.requireCompletion();
        } catch (ConversationTurnStreamConsumerException exception) {
            throw new ChatModelException(
                    ChatModelErrorCategory.STREAM_INTERRUPTED,
                    STREAM_DELIVERY_FAILED_MESSAGE,
                    null,
                    exception.getCause()
            );
        } catch (ChatModelException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ChatModelException(
                    ChatModelErrorCategory.MALFORMED_RESPONSE,
                    GATEWAY_FAILED_MESSAGE,
                    null,
                    exception
            );
        }
    }

    private void emitStarted(
            PreparedConversationTurn prepared,
            ConversationTurnStreamHandler handler
    ) {
        ConversationTurnStreamEvent.Started event =
                new ConversationTurnStreamEvent.Started(
                        Long.toString(prepared.conversationId()),
                        Long.toString(prepared.agent().agentId()),
                        Long.toString(prepared.userMessageId()),
                        prepared.userSequenceNo(),
                        Long.toString(prepared.assistantMessageId()),
                        prepared.assistantSequenceNo(),
                        prepared.conversationVersion(),
                        prepared.preparedAt()
                );

        try {
            handler.onEvent(event);
        } catch (RuntimeException cause) {
            throw new ChatModelException(
                    ChatModelErrorCategory.STREAM_INTERRUPTED,
                    STREAM_DELIVERY_FAILED_MESSAGE,
                    null,
                    cause
            );
        }
    }

    private ConversationTurnStreamEvent toCompletedEvent(
            AssistantMessageCompletionTarget target,
            CompletedConversationTurn completed
    ) {
        return new ConversationTurnStreamEvent.Completed(
                Long.toString(completed.conversationId()),
                Long.toString(completed.agentId()),
                Long.toString(target.assistantMessageId()),
                target.assistantSequenceNo(),
                target.conversationVersion(),
                completed.modelName(),
                completed.finishReason(),
                completed.usage().promptTokens(),
                completed.usage().completionTokens(),
                completed.completedAt()
        );
    }

    private void markFailed(
            AssistantMessageCompletionTarget target,
            ChatModelException modelFailure
    ) {
        try {
            failService.fail(target, modelFailure);
        } catch (RuntimeException finalizationFailure) {
            if (finalizationFailure != modelFailure) {
                finalizationFailure.addSuppressed(modelFailure);
            }
            throw finalizationFailure;
        }
    }

    private static ChatModelException toModelFailure(
            RuntimeException failure
    ) {
        if (failure instanceof ChatModelException modelFailure) {
            return modelFailure;
        }

        return new ChatModelException(
                ChatModelErrorCategory.MALFORMED_RESPONSE,
                "Tool call flow failed",
                null,
                failure
        );
    }

    /**
     * 模型流已返回、数据库完成事务提交前，确认客户端仍然在线。
     *
     * <p>即使 Gateway 忽略了 {@code Future.cancel(true)} 的中断，
     * 只要它最终返回，这里也能在 {@code completeService} 之前发现
     * 断流并把占位消息回写为 FAILED。
     *
     * <p>语义边界：完成事务提交前观察到断流 -> FAILED；
     * 完成事务已提交后客户端才断开 -> 保持 COMPLETED。
     * 数据库事务与网络连接无法原子化，后者才是正确结果。
     */
    private void requireActiveConsumer(
            ConversationTurnStreamHandler handler
    ) {
        final boolean cancelled;

        try {
            cancelled = handler.isCancellationRequested();
        } catch (RuntimeException cause) {
            throw new ChatModelException(
                    ChatModelErrorCategory.STREAM_INTERRUPTED,
                    STREAM_DELIVERY_FAILED_MESSAGE,
                    null,
                    cause
            );
        }

        if (cancelled) {
            throw new ChatModelException(
                    ChatModelErrorCategory.STREAM_INTERRUPTED,
                    STREAM_DELIVERY_FAILED_MESSAGE
            );
        }
    }
}
