package com.nexusagent.conversation.api;

import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.model.api.ChatModelException;
import com.nexusagent.model.api.ChatModelFinishReason;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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

    @Test
    void shouldStartAsyncStreamAndDeliverCompletedEvent()
            throws Exception {
        doAnswer(invocation -> {
            ConversationTurnStreamHandler handler =
                    invocation.getArgument(2);
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
                any(ConversationTurnStreamHandler.class)
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
                            invocation.getArgument(2);
            writer.markTransportClosed();
            throw new IllegalStateException("boom");
        }).when(service).stream(
                anyString(),
                anyString(),
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

        verifyNoInteractions(service);
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

        assertThrows(
                NullPointerException.class,
                () -> new ConversationTurnStreamController(
                        null,
                        executor,
                        timeout
                )
        );
        assertThrows(
                NullPointerException.class,
                () -> new ConversationTurnStreamController(
                        service,
                        null,
                        timeout
                )
        );
        assertThrows(
                NullPointerException.class,
                () -> new ConversationTurnStreamController(
                        service,
                        executor,
                        null
                )
        );
    }

    @Test
    void shouldRejectNonPositiveTimeout() {
        AsyncTaskExecutor executor = this.executor;
        StreamConversationTurnService service = this.service;

        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationTurnStreamController(
                        service,
                        executor,
                        Duration.ZERO
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationTurnStreamController(
                        service,
                        executor,
                        Duration.ofSeconds(-1)
                )
        );
    }

    @Test
    void shouldEmitStartedTwoDeltasAndCompletedInOrder()
            throws Exception {
        doAnswer(invocation -> {
            ConversationTurnStreamHandler handler =
                    invocation.getArgument(2);
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
                        Duration.ofMinutes(2)
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
                        Duration.ofMinutes(2)
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
