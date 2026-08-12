package com.nexusagent.conversation.internal;

import com.nexusagent.conversation.api.ConversationTurnStreamEvent;
import com.nexusagent.conversation.api.ConversationTurnStreamHandler;
import com.nexusagent.conversation.api.StreamConversationTurnService;
import com.nexusagent.conversation.internal.ConversationTurnTextStreamAccumulator.TextCompletion;
import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.model.api.ChatModelException;
import com.nexusagent.model.api.ChatModelGateway;
import com.nexusagent.model.api.ChatModelGatewayResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Orchestrates a streaming conversation turn by driving the
 * prepare, model stream and complete lifecycle.
 *
 * <p>This service intentionally carries no transaction. Database
 * transactions are managed by the prepare, complete and fail services
 * individually (each {@code REQUIRES_NEW}), so the model network call
 * always happens outside of any database transaction.
 */
@Service
public class DefaultStreamConversationTurnService
        implements StreamConversationTurnService {

    private final PrepareConversationTurnService prepareService;
    private final ChatModelGatewayResolver gatewayResolver;
    private final CompleteConversationTurnService completeService;
    private final FailConversationTurnService failService;

    public DefaultStreamConversationTurnService(
            PrepareConversationTurnService prepareService,
            ChatModelGatewayResolver gatewayResolver,
            CompleteConversationTurnService completeService,
            FailConversationTurnService failService
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

        TextCompletion completion;

        try {
            emitStarted(prepared, handler);
            completion = invokeModel(prepared, handler);
            requireActiveConsumer(handler);
        } catch (ChatModelException failure) {
            markFailed(prepared, failure);
            throw failure;
        }

        CompletedConversationTurn completed =
                completeService.complete(
                        prepared,
                        completion.content(),
                        completion.finishReason(),
                        completion.usage()
                );

        // 必须在数据库完成事务成功之后发送最终事件。
        handler.onEvent(toCompletedEvent(
                prepared,
                completed
        ));
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
                    "Conversation turn stream delivery failed",
                    null,
                    cause
            );
        }
    }

    private TextCompletion invokeModel(
            PreparedConversationTurn prepared,
            ConversationTurnStreamHandler handler
    ) {
        try {
            ChatModelGateway gateway =
                    gatewayResolver.requireGateway(
                            prepared.agent().modelProvider()
                    );

            ConversationTurnTextStreamAccumulator accumulator =
                    new ConversationTurnTextStreamAccumulator(handler);

            gateway.stream(
                    prepared.modelRequest(),
                    accumulator
            );

            return accumulator.requireCompletion();
        } catch (ConversationTurnStreamConsumerException exception) {
            throw new ChatModelException(
                    ChatModelErrorCategory.STREAM_INTERRUPTED,
                    "Conversation turn stream delivery failed",
                    null,
                    exception.getCause()
            );
        } catch (ChatModelException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ChatModelException(
                    ChatModelErrorCategory.MALFORMED_RESPONSE,
                    "Chat model gateway failed unexpectedly",
                    null,
                    exception
            );
        }
    }

    private ConversationTurnStreamEvent toCompletedEvent(
            PreparedConversationTurn prepared,
            CompletedConversationTurn completed
    ) {
        return new ConversationTurnStreamEvent.Completed(
                Long.toString(completed.conversationId()),
                Long.toString(completed.agentId()),
                Long.toString(completed.assistantMessageId()),
                completed.assistantSequenceNo(),
                prepared.conversationVersion(),
                completed.modelName(),
                completed.finishReason(),
                completed.usage().promptTokens(),
                completed.usage().completionTokens(),
                completed.completedAt()
        );
    }

    private void markFailed(
            PreparedConversationTurn prepared,
            ChatModelException modelFailure
    ) {
        try {
            failService.fail(prepared, modelFailure);
        } catch (RuntimeException finalizationFailure) {
            if (finalizationFailure != modelFailure) {
                finalizationFailure.addSuppressed(modelFailure);
            }
            throw finalizationFailure;
        }
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
                    "Conversation turn stream delivery failed",
                    null,
                    cause
            );
        }

        if (cancelled) {
            throw new ChatModelException(
                    ChatModelErrorCategory.STREAM_INTERRUPTED,
                    "Conversation turn stream delivery failed"
            );
        }
    }
}
