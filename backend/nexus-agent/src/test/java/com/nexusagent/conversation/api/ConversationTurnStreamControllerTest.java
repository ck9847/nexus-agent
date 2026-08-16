package com.nexusagent.conversation.api;

import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.model.api.ChatModelException;
import com.nexusagent.model.api.ChatModelFinishReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConversationTurnStreamController.class)
class ConversationTurnStreamControllerTest {

    private static final String STREAM_PATH =
            "/api/v1/conversations/901/turns:stream";

    private static final String VALID_BODY =
            """
            {
              "content": "Hello"
            }
            """;

    private static final Instant CREATED_AT =
            Instant.parse("2026-08-09T10:15:30.123Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StreamConversationTurnService service;

    @MockitoBean(name = "conversationTurnStreamExecutor")
    private AsyncTaskExecutor executor;

    @MockitoBean
    private ConversationTurnSseMetrics metrics;

    @MockitoBean
    private ConversationTurnRateLimiter rateLimiter;

    @Test
    void shouldStartAsyncStreamAndDeliverCompletedEvent()
            throws Exception {
        doAnswer(invocation -> {
            ConversationTurnStreamHandler handler =
                    invocation.getArgument(3);
            handler.onEvent(
                    new ConversationTurnStreamEvent.Completed(
                            "901",
                            "500",
                            "1002",
                            3L,
                            1,
                            "gpt-5-mini",
                            ChatModelFinishReason.STOP,
                            12,
                            34,
                            CREATED_AT
                    )
            );
            return null;
        }).when(service).stream(
                anyString(),
                anyString(),
                any(),
                any()
        );

        MvcResult result = performStream(
                stubSubmittedWorker()
        );

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                MediaType.TEXT_EVENT_STREAM
                        ))
                .andExpect(content().string(
                        containsString("event:completed")
                ));

        verify(service).stream(
                eq("901"),
                eq("Hello"),
                isNull(),
                any(ConversationTurnStreamHandler.class)
        );

        // 连接指标：提交成功后 active 增加，
        // Completed 送达后按正常完成结算。
        verify(metrics).connectionEstablished();
        verify(metrics).connectionEnded(
                ConversationTurnSseMetrics.End.COMPLETED
        );
    }

    @Test
    void shouldSendErrorEventWhenWorkerFails()
            throws Exception {
        doThrow(new IllegalStateException(
                "provider-secret-must-not-leak"
        )).when(service).stream(
                anyString(),
                anyString(),
                any(),
                any()
        );

        MvcResult result = performStream(
                stubSubmittedWorker()
        );

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("event:error")
                ))
                .andExpect(content().string(
                        containsString(
                                "\"errorCode\":\"INTERNAL_ERROR\""
                        )
                ))
                .andExpect(content().string(
                        not(containsString(
                                "provider-secret-must-not-leak"
                        ))
                ));
    }

    @Test
    void shouldCompleteWithErrorWhenTransportAlreadyFailed()
            throws Exception {
        doAnswer(invocation -> {
            ConversationTurnSseEventWriter writer =
                    (ConversationTurnSseEventWriter)
                            invocation.getArgument(3);
            writer.markTransportClosed();
            throw new IllegalStateException("boom");
        }).when(service).stream(
                anyString(),
                anyString(),
                any(),
                any()
        );

        MvcResult result = performStream(
                stubSubmittedWorker()
        );

        // completeWithError 以异步错误结束连接：
        // asyncDispatch 会重放底层失败，而不是发送 error 事件。
        assertThrows(
                ServletException.class,
                () -> mockMvc.perform(asyncDispatch(result))
        );

        verify(service).stream(
                eq("901"),
                eq("Hello"),
                isNull(),
                any(ConversationTurnStreamHandler.class)
        );
    }

    @Test
    void shouldRejectWhenSubmitIsRejected() throws Exception {
        doThrow(new TaskRejectedException(
                "queue full"
        )).when(executor).submit(any(Runnable.class));

        mockMvc.perform(
                        post(STREAM_PATH)
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(VALID_BODY)
                )
                .andExpect(
                        status().isServiceUnavailable()
                )
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                MediaType.APPLICATION_PROBLEM_JSON
                        ))
                .andExpect(jsonPath("$.errorCode")
                        .value(
                                "CONVERSATION_TURN_CAPACITY_EXCEEDED"
                        ))
                .andExpect(jsonPath("$.detail")
                        .value(
                                "Conversation turn capacity "
                                        + "is temporarily unavailable"
                        ));

        // executor reject：计入容量拒绝，且绝不增加 active。
        verify(metrics).countCapacityRejected();
        verify(metrics, never()).connectionEstablished();

        verifyNoInteractions(service);
    }

    @Test
    void shouldNotLeakActiveWhenWorkerFinishesBeforeAccept()
            throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConversationTurnSseMetrics realMetrics =
                new ConversationTurnSseMetrics(registry);

        // metrics mock 委托到真实实现以便断言数值。
        doAnswer(invocation -> {
            realMetrics.connectionEstablished();
            return null;
        }).when(metrics).connectionEstablished();

        doAnswer(invocation -> {
            realMetrics.connectionEnded(
                    invocation.getArgument(0)
            );
            return null;
        }).when(metrics).connectionEnded(any());

        // 同步 executor：worker 在 submit() 返回前即运行完毕，
        // 触发 writer 的提前终态结算——这正是历史竞态。
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return CompletableFuture.completedFuture(null);
        }).when(executor).submit(any(Runnable.class));

        doAnswer(invocation -> {
            ConversationTurnStreamHandler handler =
                    invocation.getArgument(3);
            handler.onEvent(
                    new ConversationTurnStreamEvent.Completed(
                            "901",
                            "500",
                            "1002",
                            3L,
                            1,
                            "gpt-5-mini",
                            ChatModelFinishReason.STOP,
                            12,
                            34,
                            CREATED_AT
                    )
            );
            return null;
        }).when(service).stream(
                anyString(),
                anyString(),
                any(),
                any()
        );

        mockMvc.perform(
                        post(STREAM_PATH)
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(VALID_BODY)
                )
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("event:completed")
                ));

        // 竞态下 active 必须归零、established/completed 各一次。
        assertEquals(
                0,
                realMetrics.activeConnections()
        );

        assertEquals(
                1.0,
                counterCount(
                        registry,
                        ConversationTurnSseMetrics
                                .ESTABLISHED_COUNTER
                )
        );
        assertEquals(
                1.0,
                counterCount(
                        registry,
                        ConversationTurnSseMetrics
                                .COMPLETED_COUNTER
                )
        );
    }

    @Test
    void shouldNotIncreaseActiveOnExecutorRejection()
            throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConversationTurnSseMetrics realMetrics =
                new ConversationTurnSseMetrics(registry);

        doAnswer(invocation -> {
            realMetrics.connectionEstablished();
            return null;
        }).when(metrics).connectionEstablished();

        doAnswer(invocation -> {
            realMetrics.connectionEnded(
                    invocation.getArgument(0)
            );
            return null;
        }).when(metrics).connectionEnded(any());

        doAnswer(invocation -> {
            realMetrics.countCapacityRejected();
            return null;
        }).when(metrics).countCapacityRejected();

        doThrow(new TaskRejectedException("queue full"))
                .when(executor).submit(any(Runnable.class));

        mockMvc.perform(
                        post(STREAM_PATH)
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(VALID_BODY)
                )
                .andExpect(status().isServiceUnavailable());

        // rejection 后 active 仍为 0、established 未计、
        // 仅 capacity 计数器 +1。
        assertEquals(
                0,
                realMetrics.activeConnections()
        );
        assertEquals(
                0.0,
                counterCount(
                        registry,
                        ConversationTurnSseMetrics
                                .ESTABLISHED_COUNTER
                )
        );
        assertEquals(
                1.0,
                counterCount(
                        registry,
                        ConversationTurnSseMetrics
                                .CAPACITY_REJECTED_COUNTER
                )
        );
    }

    private static double counterCount(
            io.micrometer.core.instrument.MeterRegistry registry,
            String name
    ) {
        io.micrometer.core.instrument.Counter counter =
                registry.find(name).counter();

        return counter == null ? 0.0 : counter.count();
    }

    @Test
    void shouldRejectBlankContent() throws Exception {
        mockMvc.perform(
                        post(STREAM_PATH)
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

        verifyNoInteractions(service, executor);
    }

    @Test
    void shouldRejectNullConstructorArguments() {
        AsyncTaskExecutor executor = this.executor;
        StreamConversationTurnService service = this.service;
        Duration timeout = Duration.ofMinutes(2);
        ConversationTurnSseMetrics metrics = this.metrics;

        assertThrows(
                NullPointerException.class,
                () -> new ConversationTurnStreamController(
                        null,
                        executor,
                        timeout,
                        metrics,
                        rateLimiter
                )
        );
        assertThrows(
                NullPointerException.class,
                () -> new ConversationTurnStreamController(
                        service,
                        null,
                        timeout,
                        metrics,
                        rateLimiter
                )
        );
        assertThrows(
                NullPointerException.class,
                () -> new ConversationTurnStreamController(
                        service,
                        executor,
                        null,
                        metrics,
                        rateLimiter
                )
        );
        assertThrows(
                NullPointerException.class,
                () -> new ConversationTurnStreamController(
                        service,
                        executor,
                        timeout,
                        metrics,
                        null
                )
        );
    }

    @Test
    void shouldRejectNonPositiveTimeout() {
        AsyncTaskExecutor executor = this.executor;
        StreamConversationTurnService service = this.service;
        ConversationTurnSseMetrics metrics = this.metrics;

        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationTurnStreamController(
                        service,
                        executor,
                        Duration.ZERO,
                        metrics,
                        rateLimiter
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationTurnStreamController(
                        service,
                        executor,
                        Duration.ofSeconds(-1),
                        metrics,
                        rateLimiter
                )
        );
    }

    @Test
    void shouldEmitStartedTwoDeltasAndCompletedInOrder()
            throws Exception {
        doAnswer(invocation -> {
            ConversationTurnStreamHandler handler =
                    invocation.getArgument(3);
            handler.onEvent(
                    new ConversationTurnStreamEvent.Started(
                            "901",
                            "500",
                            "1001",
                            2L,
                            "1002",
                            3L,
                            1,
                            CREATED_AT
                    )
            );
            handler.onEvent(
                    new ConversationTurnStreamEvent.TextDelta("Hel")
            );
            handler.onEvent(
                    new ConversationTurnStreamEvent.TextDelta("lo")
            );
            handler.onEvent(
                    new ConversationTurnStreamEvent.Completed(
                            "901",
                            "500",
                            "1002",
                            3L,
                            1,
                            "gpt-5-mini",
                            ChatModelFinishReason.STOP,
                            12,
                            34,
                            CREATED_AT
                    )
            );
            return null;
        }).when(service).stream(
                anyString(),
                anyString(),
                any(),
                any()
        );

        MvcResult result = performStream(
                stubSubmittedWorker()
        );

        String body = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                MediaType.TEXT_EVENT_STREAM
                        ))
                .andReturn()
                .getResponse()
                .getContentAsString(
                        StandardCharsets.UTF_8
                );

        int started = body.indexOf("event:started");
        int firstDelta = body.indexOf("event:delta");
        int secondDelta =
                body.indexOf("event:delta", firstDelta + 1);
        int completed = body.indexOf("event:completed");

        assertTrue(started >= 0);
        assertTrue(firstDelta > started);
        assertTrue(secondDelta > firstDelta);
        assertTrue(completed > secondDelta);
    }

    @Test
    void shouldRejectMalformedJson() throws Exception {
        mockMvc.perform(
                        post(STREAM_PATH)
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("{ unclosed")
                )
                .andExpect(status().isBadRequest())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                MediaType.APPLICATION_PROBLEM_JSON
                        ))
                .andExpect(jsonPath("$.errorCode")
                        .value("MALFORMED_REQUEST"));

        verifyNoInteractions(service, executor);
    }

    @Test
    void shouldMapNotFoundToSafeErrorEvent()
            throws Exception {
        doThrow(new ConversationNotFoundException())
                .when(service).stream(
                        anyString(),
                        anyString(),
                        any(),
                        any()
                );

        MvcResult result = performStream(
                stubSubmittedWorker()
        );

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString(
                                "\"errorCode\":"
                                        + "\"CONVERSATION_NOT_FOUND\""
                        )
                ));
    }

    @Test
    void shouldMapTurnInProgressToSafeErrorEvent()
            throws Exception {
        doThrow(new ConversationTurnInProgressException())
                .when(service).stream(
                        anyString(),
                        anyString(),
                        any(),
                        any()
                );

        MvcResult result = performStream(
                stubSubmittedWorker()
        );

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString(
                                "\"errorCode\":"
                                        + "\"CONVERSATION_TURN_IN_PROGRESS\""
                        )
                ))
                .andExpect(content().string(
                        containsString("\"retryable\":true")
                ));
    }

    @Test
    void shouldMapModelFailureToSafeErrorEvent()
            throws Exception {
        doThrow(new ChatModelException(
                ChatModelErrorCategory.RATE_LIMIT,
                "provider-secret-must-not-leak"
        )).when(service).stream(
                anyString(),
                anyString(),
                any(),
                any()
        );

        MvcResult result = performStream(
                stubSubmittedWorker()
        );

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString(
                                "\"errorCode\":"
                                        + "\"CHAT_MODEL_RATE_LIMIT\""
                        )
                ))
                .andExpect(content().string(
                        not(containsString(
                                "provider-secret-must-not-leak"
                        ))
                ));
    }

    @Test
    void shouldCompleteWithErrorWhenErrorEventDeliveryFails() {
        SseEmitter emitter = mock(SseEmitter.class);

        ConversationTurnSseEventWriter writer =
                mock(ConversationTurnSseEventWriter.class);

        IllegalStateException businessFailure =
                new IllegalStateException("business failure");

        ConversationTurnStreamDeliveryException
                deliveryFailure =
                new ConversationTurnStreamDeliveryException(
                        new IOException("client disconnected")
                );

        doThrow(deliveryFailure)
                .when(writer)
                .sendError(
                        any(ConversationTurnSseError.class),
                        same(businessFailure)
                );

        ConversationTurnStreamController controller =
                new ConversationTurnStreamController(
                        service,
                        executor,
                        Duration.ofMinutes(2),
                        metrics,
                        rateLimiter
                );

        controller.handleWorkerFailure(
                emitter,
                writer,
                businessFailure
        );

        verify(writer).markTransportClosed();
        verify(emitter).completeWithError(
                deliveryFailure
        );
    }

    @Test
    void shouldSwallowCompleteWithErrorItselfFailing() {
        SseEmitter emitter = mock(SseEmitter.class);

        ConversationTurnSseEventWriter writer =
                mock(ConversationTurnSseEventWriter.class);

        IllegalStateException businessFailure =
                new IllegalStateException("business failure");

        ConversationTurnStreamDeliveryException
                deliveryFailure =
                new ConversationTurnStreamDeliveryException(
                        new IOException("client disconnected")
                );

        doThrow(deliveryFailure)
                .when(writer)
                .sendError(
                        any(ConversationTurnSseError.class),
                        same(businessFailure)
                );

        doThrow(new IllegalStateException(
                "already completed"
        )).when(emitter).completeWithError(
                deliveryFailure
        );

        ConversationTurnStreamController controller =
                new ConversationTurnStreamController(
                        service,
                        executor,
                        Duration.ofMinutes(2),
                        metrics,
                        rateLimiter
                );

        assertDoesNotThrow(() ->
                controller.handleWorkerFailure(
                        emitter,
                        writer,
                        businessFailure
                ));

        verify(writer).markTransportClosed();
        verify(emitter).completeWithError(
                deliveryFailure
        );
    }

    /**
     * 在提交前 stub executor：捕获 worker 并返回立即完成的结果，
     * 让测试手动执行 worker 以观察 SSE 输出。
     */
    private AtomicReference<Runnable> stubSubmittedWorker()
            throws Exception {
        AtomicReference<Runnable> workerRef =
                new AtomicReference<>();

        doAnswer(invocation -> {
            workerRef.set(invocation.getArgument(0));
            return CompletableFuture.completedFuture(null);
        }).when(executor).submit(any(Runnable.class));

        return workerRef;
    }

    private MvcResult performStream(
            AtomicReference<Runnable> workerRef
    ) throws Exception {
        MvcResult result = mockMvc.perform(
                        post(STREAM_PATH)
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(VALID_BODY)
                )
                .andExpect(request().asyncStarted())
                .andReturn();

        workerRef.get().run();

        return result;
    }
}
