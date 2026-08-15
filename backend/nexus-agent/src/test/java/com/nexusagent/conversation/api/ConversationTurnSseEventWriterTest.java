package com.nexusagent.conversation.api;

import com.nexusagent.model.api.ChatModelFinishReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationTurnSseEventWriterTest {

    private static final Instant CREATED_AT =
            Instant.parse("2026-08-09T10:15:30.123Z");

    private static final ConversationTurnStreamEvent.Started STARTED =
            new ConversationTurnStreamEvent.Started(
                    "901",
                    "500",
                    "1001",
                    2L,
                    "1002",
                    3L,
                    1,
                    CREATED_AT
            );

    private static final ConversationTurnStreamEvent.TextDelta DELTA =
            new ConversationTurnStreamEvent.TextDelta("Hello");

    private static final ConversationTurnStreamEvent.Completed COMPLETED =
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
            );

    private static final ConversationTurnSseError ERROR =
            new ConversationTurnSseError(
                    "INTERNAL_ERROR",
                    "Conversation turn failed",
                    false
            );

    @Test
    void shouldSendStartedAsNamedEvent() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        ConversationTurnSseEventWriter writer =
                new ConversationTurnSseEventWriter(emitter);

        SseEmitter.SseEventBuilder builder =
                mock(SseEmitter.SseEventBuilder.class);
        when(builder.name("started")).thenReturn(builder);
        when(builder.data(any(), any())).thenReturn(builder);

        try (MockedStatic<SseEmitter> emitterStatic =
                     mockStatic(SseEmitter.class)) {
            emitterStatic.when(SseEmitter::event).thenReturn(builder);

            writer.onEvent(STARTED);
        }

        verify(builder).name("started");
        verify(builder).data(STARTED, MediaType.APPLICATION_JSON);
        verify(emitter).send(builder);
        verify(emitter, never()).complete();
        assertFalse(writer.terminal());
        assertFalse(writer.transportFailed());
    }

    @Test
    void shouldSendTextDeltaAsNamedDeltaEvent() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        ConversationTurnSseEventWriter writer =
                new ConversationTurnSseEventWriter(emitter);

        SseEmitter.SseEventBuilder builder =
                mock(SseEmitter.SseEventBuilder.class);
        when(builder.name("delta")).thenReturn(builder);
        when(builder.data(any(), any())).thenReturn(builder);

        try (MockedStatic<SseEmitter> emitterStatic =
                     mockStatic(SseEmitter.class)) {
            emitterStatic.when(SseEmitter::event).thenReturn(builder);

            writer.onEvent(DELTA);
        }

        verify(builder).name("delta");
        verify(builder).data(DELTA, MediaType.APPLICATION_JSON);
        verify(emitter).send(builder);
        verify(emitter, never()).complete();
        assertFalse(writer.terminal());
    }

    @Test
    void shouldSendCompletedAndCompleteEmitter() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        ConversationTurnSseEventWriter writer =
                new ConversationTurnSseEventWriter(emitter);

        SseEmitter.SseEventBuilder builder =
                mock(SseEmitter.SseEventBuilder.class);
        when(builder.name("completed")).thenReturn(builder);
        when(builder.data(any(), any())).thenReturn(builder);

        try (MockedStatic<SseEmitter> emitterStatic =
                     mockStatic(SseEmitter.class)) {
            emitterStatic.when(SseEmitter::event).thenReturn(builder);

            writer.onEvent(COMPLETED);
        }

        verify(builder).name("completed");
        verify(builder).data(COMPLETED, MediaType.APPLICATION_JSON);
        verify(emitter).send(builder);
        verify(emitter).complete();
        assertTrue(writer.terminal());
        assertFalse(writer.transportFailed());
    }

    @Test
    void shouldSendErrorAndCompleteEmitter() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        ConversationTurnSseEventWriter writer =
                new ConversationTurnSseEventWriter(emitter);

        SseEmitter.SseEventBuilder builder =
                mock(SseEmitter.SseEventBuilder.class);
        when(builder.name("error")).thenReturn(builder);
        when(builder.data(any(), any())).thenReturn(builder);

        try (MockedStatic<SseEmitter> emitterStatic =
                     mockStatic(SseEmitter.class)) {
            emitterStatic.when(SseEmitter::event).thenReturn(builder);

            writer.sendError(
                    ERROR,
                    new IllegalStateException("boom")
            );
        }

        verify(builder).name("error");
        verify(builder).data(ERROR, MediaType.APPLICATION_JSON);
        verify(emitter).send(builder);
        verify(emitter).complete();
        assertTrue(writer.terminal());
        assertFalse(writer.transportFailed());
    }

    @Test
    void shouldWrapIoExceptionAndMarkTransportFailed()
            throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        ConversationTurnSseEventWriter writer =
                new ConversationTurnSseEventWriter(emitter);

        SseEmitter.SseEventBuilder builder =
                mock(SseEmitter.SseEventBuilder.class);
        when(builder.name("started")).thenReturn(builder);
        when(builder.data(any(), any())).thenReturn(builder);

        IOException ioFailure = new IOException("socket closed");
        doThrow(ioFailure).when(emitter).send(builder);

        ConversationTurnStreamDeliveryException exception;

        try (MockedStatic<SseEmitter> emitterStatic =
                     mockStatic(SseEmitter.class)) {
            emitterStatic.when(SseEmitter::event).thenReturn(builder);

            exception = assertThrows(
                    ConversationTurnStreamDeliveryException.class,
                    () -> writer.onEvent(STARTED)
            );
        }

        assertEquals(
                "Conversation turn SSE delivery failed",
                exception.getMessage()
        );
        assertSame(ioFailure, exception.getCause());
        assertTrue(writer.transportFailed());
        assertTrue(writer.terminal());
    }

    @Test
    void shouldNotSendErrorWhenTransportAlreadyFailed()
            throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        ConversationTurnSseEventWriter writer =
                new ConversationTurnSseEventWriter(emitter);

        SseEmitter.SseEventBuilder startedBuilder =
                mock(SseEmitter.SseEventBuilder.class);
        when(startedBuilder.name("started")).thenReturn(startedBuilder);
        when(startedBuilder.data(any(), any()))
                .thenReturn(startedBuilder);
        doThrow(new IOException("closed"))
                .when(emitter).send(startedBuilder);

        try (MockedStatic<SseEmitter> emitterStatic =
                     mockStatic(SseEmitter.class)) {
            emitterStatic.when(SseEmitter::event)
                    .thenReturn(startedBuilder);

            assertThrows(
                    ConversationTurnStreamDeliveryException.class,
                    () -> writer.onEvent(STARTED)
            );
        }

        assertTrue(writer.transportFailed());

        SseEmitter.SseEventBuilder errorBuilder =
                mock(SseEmitter.SseEventBuilder.class);
        when(errorBuilder.name("error")).thenReturn(errorBuilder);
        when(errorBuilder.data(any(), any()))
                .thenReturn(errorBuilder);

        try (MockedStatic<SseEmitter> emitterStatic =
                     mockStatic(SseEmitter.class)) {
            emitterStatic.when(SseEmitter::event)
                    .thenReturn(errorBuilder);

            writer.sendError(
                    ERROR,
                    new IllegalStateException("boom")
            );
        }

        verify(errorBuilder, never()).name("error");
        verify(emitter, times(1))
                .send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter, never()).complete();
    }

    @Test
    void shouldNotSendEventAfterTransportFailed() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        ConversationTurnSseEventWriter writer =
                new ConversationTurnSseEventWriter(emitter);

        SseEmitter.SseEventBuilder builder =
                mock(SseEmitter.SseEventBuilder.class);
        when(builder.name("started")).thenReturn(builder);
        when(builder.data(any(), any())).thenReturn(builder);
        doThrow(new IOException("closed")).when(emitter).send(builder);

        try (MockedStatic<SseEmitter> emitterStatic =
                     mockStatic(SseEmitter.class)) {
            emitterStatic.when(SseEmitter::event).thenReturn(builder);

            assertThrows(
                    ConversationTurnStreamDeliveryException.class,
                    () -> writer.onEvent(STARTED)
            );
        }

        assertTrue(writer.transportFailed());

        assertThrows(
                ConversationTurnStreamDeliveryException.class,
                () -> writer.onEvent(DELTA)
        );
        verify(emitter, times(1))
                .send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void shouldMarkTransportClosed() {
        SseEmitter emitter = mock(SseEmitter.class);
        ConversationTurnSseEventWriter writer =
                new ConversationTurnSseEventWriter(emitter);

        writer.markTransportClosed();

        assertTrue(writer.transportFailed());
        assertTrue(writer.terminal());
    }

    @Test
    void shouldSendErrorOnlyOnce() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        ConversationTurnSseEventWriter writer =
                new ConversationTurnSseEventWriter(emitter);

        SseEmitter.SseEventBuilder builder =
                mock(SseEmitter.SseEventBuilder.class);
        when(builder.name("error")).thenReturn(builder);
        when(builder.data(any(), any())).thenReturn(builder);

        try (MockedStatic<SseEmitter> emitterStatic =
                     mockStatic(SseEmitter.class)) {
            emitterStatic.when(SseEmitter::event).thenReturn(builder);

            writer.sendError(
                    ERROR,
                    new IllegalStateException("first")
            );
            writer.sendError(
                    ERROR,
                    new IllegalStateException("second")
            );
        }

        verify(builder, times(1)).name("error");
        verify(emitter, times(1)).send(builder);
        verify(emitter, times(1)).complete();
    }

    @Test
    void shouldTrackTerminalStateAcrossLifecycle() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        ConversationTurnSseEventWriter writer =
                new ConversationTurnSseEventWriter(emitter);

        SseEmitter.SseEventBuilder builder =
                mock(SseEmitter.SseEventBuilder.class);
        when(builder.name(anyString())).thenReturn(builder);
        when(builder.data(any(), any())).thenReturn(builder);

        try (MockedStatic<SseEmitter> emitterStatic =
                     mockStatic(SseEmitter.class)) {
            emitterStatic.when(SseEmitter::event).thenReturn(builder);

            assertFalse(writer.terminal());

            writer.onEvent(STARTED);
            assertFalse(writer.terminal());

            writer.onEvent(DELTA);
            assertFalse(writer.terminal());

            writer.onEvent(COMPLETED);
            assertTrue(writer.terminal());
        }

        verify(emitter, times(3)).send(builder);
        verify(emitter, times(1)).complete();
        assertFalse(writer.transportFailed());
    }

    @Test
    void shouldMarkTransportFailedForUncheckedSendFailure()
            throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);

        IllegalStateException sendFailure =
                new IllegalStateException(
                        "response already completed"
                );

        doThrow(sendFailure)
                .when(emitter)
                .send(any(
                        SseEmitter.SseEventBuilder.class
                ));

        ConversationTurnSseEventWriter writer =
                new ConversationTurnSseEventWriter(
                        emitter
                );

        ConversationTurnStreamDeliveryException exception =
                assertThrows(
                        ConversationTurnStreamDeliveryException.class,
                        () -> writer.onEvent(STARTED)
                );

        assertSame(sendFailure, exception.getCause());
        assertTrue(writer.transportFailed());
        assertTrue(writer.isCancellationRequested());
    }

    @Test
    void shouldMarkTransportFailedWhenCompleteThrows()
            throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        ConversationTurnSseEventWriter writer =
                new ConversationTurnSseEventWriter(emitter);

        SseEmitter.SseEventBuilder builder =
                mock(SseEmitter.SseEventBuilder.class);
        when(builder.name("completed")).thenReturn(builder);
        when(builder.data(any(), any())).thenReturn(builder);

        IllegalStateException completeFailure =
                new IllegalStateException(
                        "response already completed"
                );

        doThrow(completeFailure).when(emitter).complete();

        try (MockedStatic<SseEmitter> emitterStatic =
                     mockStatic(SseEmitter.class)) {
            emitterStatic.when(SseEmitter::event)
                    .thenReturn(builder);

            ConversationTurnStreamDeliveryException exception =
                    assertThrows(
                            ConversationTurnStreamDeliveryException.class,
                            () -> writer.onEvent(COMPLETED)
                    );

            assertSame(completeFailure, exception.getCause());
        }

        assertTrue(writer.transportFailed());
        assertTrue(writer.isCancellationRequested());
    }

    @Test
    void shouldMarkTransportFailedWhenErrorSendThrowsIoException()
            throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        ConversationTurnSseEventWriter writer =
                new ConversationTurnSseEventWriter(emitter);

        SseEmitter.SseEventBuilder builder =
                mock(SseEmitter.SseEventBuilder.class);
        when(builder.name("error")).thenReturn(builder);
        when(builder.data(any(), any())).thenReturn(builder);

        IOException ioFailure = new IOException("socket closed");
        doThrow(ioFailure).when(emitter).send(builder);

        try (MockedStatic<SseEmitter> emitterStatic =
                     mockStatic(SseEmitter.class)) {
            emitterStatic.when(SseEmitter::event)
                    .thenReturn(builder);

            ConversationTurnStreamDeliveryException exception =
                    assertThrows(
                            ConversationTurnStreamDeliveryException.class,
                            () -> writer.sendError(
                                    ERROR,
                                    new IllegalStateException(
                                            "boom"
                                    )
                            )
                    );

            assertSame(ioFailure, exception.getCause());
        }

        assertTrue(writer.transportFailed());
        assertTrue(writer.isCancellationRequested());
    }

    @Test
    void shouldMarkTransportFailedWhenErrorSendThrowsUnchecked()
            throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        ConversationTurnSseEventWriter writer =
                new ConversationTurnSseEventWriter(emitter);

        SseEmitter.SseEventBuilder builder =
                mock(SseEmitter.SseEventBuilder.class);
        when(builder.name("error")).thenReturn(builder);
        when(builder.data(any(), any())).thenReturn(builder);

        IllegalStateException sendFailure =
                new IllegalStateException(
                        "response already completed"
                );

        doThrow(sendFailure).when(emitter).send(builder);

        try (MockedStatic<SseEmitter> emitterStatic =
                     mockStatic(SseEmitter.class)) {
            emitterStatic.when(SseEmitter::event)
                    .thenReturn(builder);

            ConversationTurnStreamDeliveryException exception =
                    assertThrows(
                            ConversationTurnStreamDeliveryException.class,
                            () -> writer.sendError(
                                    ERROR,
                                    new IllegalStateException(
                                            "boom"
                                    )
                            )
                    );

            assertSame(sendFailure, exception.getCause());
        }

        assertTrue(writer.transportFailed());
        assertTrue(writer.isCancellationRequested());
    }

    @Test
    void shouldCountCompletedTerminationAndDecrementActiveOnce()
            throws IOException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConversationTurnSseMetrics metrics =
                new ConversationTurnSseMetrics(registry);

        SseEmitter emitter = mock(SseEmitter.class);
        ConversationTurnSseEventWriter writer =
                new ConversationTurnSseEventWriter(emitter, metrics);

        SseEmitter.SseEventBuilder builder =
                mock(SseEmitter.SseEventBuilder.class);
        when(builder.name("completed")).thenReturn(builder);
        when(builder.data(any(), any())).thenReturn(builder);

        writer.markAccepted();
        assertEquals(1, metrics.activeConnections());

        try (MockedStatic<SseEmitter> emitterStatic =
                     mockStatic(SseEmitter.class)) {
            emitterStatic.when(SseEmitter::event)
                    .thenReturn(builder);

            writer.onEvent(COMPLETED);
        }

        assertEquals(0, metrics.activeConnections());
        assertEquals(
                1.0,
                count(
                        registry,
                        ConversationTurnSseMetrics
                                .COMPLETED_COUNTER
                )
        );

        // 已结束的连接再超时：不得二次 decrement 或重复计数。
        writer.markTimeout();
        assertEquals(0, metrics.activeConnections());
        assertEquals(
                0.0,
                count(
                        registry,
                        ConversationTurnSseMetrics
                                .TIMEOUT_COUNTER
                )
        );
    }

    @Test
    void shouldCountErrorTerminationAndDecrementActive()
            throws IOException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConversationTurnSseMetrics metrics =
                new ConversationTurnSseMetrics(registry);

        SseEmitter emitter = mock(SseEmitter.class);
        ConversationTurnSseEventWriter writer =
                new ConversationTurnSseEventWriter(emitter, metrics);

        SseEmitter.SseEventBuilder builder =
                mock(SseEmitter.SseEventBuilder.class);
        when(builder.name("error")).thenReturn(builder);
        when(builder.data(any(), any())).thenReturn(builder);

        writer.markAccepted();

        try (MockedStatic<SseEmitter> emitterStatic =
                     mockStatic(SseEmitter.class)) {
            emitterStatic.when(SseEmitter::event)
                    .thenReturn(builder);

            writer.sendError(
                    ERROR,
                    new IllegalStateException("boom")
            );
        }

        assertEquals(0, metrics.activeConnections());
        assertEquals(
                1.0,
                count(
                        registry,
                        ConversationTurnSseMetrics
                                .ERROR_COUNTER
                )
        );
        assertEquals(
                0.0,
                count(
                        registry,
                        ConversationTurnSseMetrics
                                .COMPLETED_COUNTER
                )
        );
    }

    @Test
    void shouldEndOnlyOnceWhenTimeoutAndDisconnectBothFire() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConversationTurnSseMetrics metrics =
                new ConversationTurnSseMetrics(registry);

        ConversationTurnSseEventWriter writer =
                new ConversationTurnSseEventWriter(
                        mock(SseEmitter.class),
                        metrics
                );

        writer.markAccepted();

        // onTimeout 与 onError 同时触发（先超时后断连，
        // 再叠加兼容调用点）也只能结束一次。
        writer.markTimeout();
        writer.markClientDisconnected();
        writer.markTransportClosed();

        assertEquals(0, metrics.activeConnections());
        assertEquals(
                1.0,
                count(
                        registry,
                        ConversationTurnSseMetrics
                                .TIMEOUT_COUNTER
                )
        );
        assertEquals(
                0.0,
                count(
                        registry,
                        ConversationTurnSseMetrics
                                .CLIENT_DISCONNECT_COUNTER
                )
        );
    }

    @Test
    void shouldCountClientDisconnectOnSendFailure()
            throws IOException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConversationTurnSseMetrics metrics =
                new ConversationTurnSseMetrics(registry);

        SseEmitter emitter = mock(SseEmitter.class);
        ConversationTurnSseEventWriter writer =
                new ConversationTurnSseEventWriter(emitter, metrics);

        SseEmitter.SseEventBuilder builder =
                mock(SseEmitter.SseEventBuilder.class);
        when(builder.name("started")).thenReturn(builder);
        when(builder.data(any(), any())).thenReturn(builder);

        doThrow(new IOException("socket closed"))
                .when(emitter).send(builder);

        writer.markAccepted();

        try (MockedStatic<SseEmitter> emitterStatic =
                     mockStatic(SseEmitter.class)) {
            emitterStatic.when(SseEmitter::event)
                    .thenReturn(builder);

            assertThrows(
                    ConversationTurnStreamDeliveryException.class,
                    () -> writer.onEvent(STARTED)
            );
        }

        assertEquals(0, metrics.activeConnections());
        assertEquals(
                1.0,
                count(
                        registry,
                        ConversationTurnSseMetrics
                                .CLIENT_DISCONNECT_COUNTER
                )
        );
    }

    @Test
    void shouldCountErrorSendFailureSeparately()
            throws IOException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConversationTurnSseMetrics metrics =
                new ConversationTurnSseMetrics(registry);

        SseEmitter emitter = mock(SseEmitter.class);
        ConversationTurnSseEventWriter writer =
                new ConversationTurnSseEventWriter(emitter, metrics);

        SseEmitter.SseEventBuilder builder =
                mock(SseEmitter.SseEventBuilder.class);
        when(builder.name("error")).thenReturn(builder);
        when(builder.data(any(), any())).thenReturn(builder);

        doThrow(new IOException("socket closed"))
                .when(emitter).send(builder);

        writer.markAccepted();

        try (MockedStatic<SseEmitter> emitterStatic =
                     mockStatic(SseEmitter.class)) {
            emitterStatic.when(SseEmitter::event)
                    .thenReturn(builder);

            assertThrows(
                    ConversationTurnStreamDeliveryException.class,
                    () -> writer.sendError(
                            ERROR,
                            new IllegalStateException("boom")
                    )
            );
        }

        assertEquals(0, metrics.activeConnections());
        assertEquals(
                1.0,
                count(
                        registry,
                        ConversationTurnSseMetrics
                                .ERROR_SEND_FAILURE_COUNTER
                )
        );
        assertEquals(
                1.0,
                count(
                        registry,
                        ConversationTurnSseMetrics
                                .CLIENT_DISCONNECT_COUNTER
                )
        );
        assertEquals(
                0.0,
                count(
                        registry,
                        ConversationTurnSseMetrics
                                .ERROR_COUNTER
                )
        );
    }

    @Test
    void shouldSettlePendingEndWhenAcceptedAfterCompletion()
            throws IOException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConversationTurnSseMetrics metrics =
                new ConversationTurnSseMetrics(registry);

        SseEmitter emitter = mock(SseEmitter.class);
        ConversationTurnSseEventWriter writer =
                new ConversationTurnSseEventWriter(emitter, metrics);

        SseEmitter.SseEventBuilder builder =
                mock(SseEmitter.SseEventBuilder.class);
        when(builder.name("completed")).thenReturn(builder);
        when(builder.data(any(), any())).thenReturn(builder);

        // worker 在 markAccepted() 之前已结束：
        // 终态仅被暂存，active 尚未建立。
        try (MockedStatic<SseEmitter> emitterStatic =
                     mockStatic(SseEmitter.class)) {
            emitterStatic.when(SseEmitter::event)
                    .thenReturn(builder);

            writer.onEvent(COMPLETED);
        }

        assertEquals(0, metrics.activeConnections());
        assertEquals(
                0.0,
                count(
                        registry,
                        ConversationTurnSseMetrics
                                .ESTABLISHED_COUNTER
                )
        );

        // Controller 在 submit 成功返回后标记接受：
        // 先 established，再补结算暂存终态。
        writer.markAccepted();

        assertEquals(0, metrics.activeConnections());
        assertEquals(
                1.0,
                count(
                        registry,
                        ConversationTurnSseMetrics
                                .ESTABLISHED_COUNTER
                )
        );
        assertEquals(
                1.0,
                count(
                        registry,
                        ConversationTurnSseMetrics
                                .COMPLETED_COUNTER
                )
        );
    }

    @Test
    void shouldNotDoubleSettleWhenEndHappensBeforeAndAfterAccepted()
            throws IOException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConversationTurnSseMetrics metrics =
                new ConversationTurnSseMetrics(registry);

        SseEmitter emitter = mock(SseEmitter.class);
        ConversationTurnSseEventWriter writer =
                new ConversationTurnSseEventWriter(emitter, metrics);

        SseEmitter.SseEventBuilder builder =
                mock(SseEmitter.SseEventBuilder.class);
        when(builder.name(anyString())).thenReturn(builder);
        when(builder.data(any(), any())).thenReturn(builder);

        // 接受前结束（pending）。
        try (MockedStatic<SseEmitter> emitterStatic =
                     mockStatic(SseEmitter.class)) {
            emitterStatic.when(SseEmitter::event)
                    .thenReturn(builder);

            writer.markTimeout();
        }

        writer.markAccepted();

        // 接受后再次结束：终态已结算过，不得重复。
        writer.markClientDisconnected();

        assertEquals(0, metrics.activeConnections());
        assertEquals(
                1.0,
                count(
                        registry,
                        ConversationTurnSseMetrics
                                .ESTABLISHED_COUNTER
                )
        );
        assertEquals(
                1.0,
                count(
                        registry,
                        ConversationTurnSseMetrics
                                .TIMEOUT_COUNTER
                )
        );
        assertEquals(
                0.0,
                count(
                        registry,
                        ConversationTurnSseMetrics
                                .CLIENT_DISCONNECT_COUNTER
                )
        );
    }

    @Test
    void shouldEstablishOnlyOnceForRepeatedMarkAccepted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConversationTurnSseMetrics metrics =
                new ConversationTurnSseMetrics(registry);

        ConversationTurnSseEventWriter writer =
                new ConversationTurnSseEventWriter(
                        mock(SseEmitter.class),
                        metrics
                );

        writer.markAccepted();
        writer.markAccepted();

        assertEquals(1, metrics.activeConnections());
        assertEquals(
                1.0,
                count(
                        registry,
                        ConversationTurnSseMetrics
                                .ESTABLISHED_COUNTER
                )
        );

        // 清理。
        writer.markTimeout();
        assertEquals(0, metrics.activeConnections());
    }

    @Test
    void shouldNeverLetActiveFallBelowZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConversationTurnSseMetrics metrics =
                new ConversationTurnSseMetrics(registry);

        metrics.connectionEstablished();

        metrics.connectionEnded(
                ConversationTurnSseMetrics.End.COMPLETED
        );
        metrics.connectionEnded(
                ConversationTurnSseMetrics.End.ERROR
        );
        metrics.connectionEnded(
                ConversationTurnSseMetrics.End.TIMEOUT
        );
        metrics.connectionEnded(
                ConversationTurnSseMetrics.End.CLIENT_DISCONNECT
        );

        assertEquals(0, metrics.activeConnections());
    }

    @Test
    void shouldNeverPropagateMetricFailures() throws IOException {
        MeterRegistry throwing = mock(MeterRegistry.class);

        doThrow(new IllegalStateException("metrics boom"))
                .when(throwing).counter(anyString());

        ConversationTurnSseMetrics metrics =
                new ConversationTurnSseMetrics(throwing);

        ConversationTurnSseEventWriter writer =
                new ConversationTurnSseEventWriter(
                        mock(SseEmitter.class),
                        metrics
                );

        // 指标初始化失败后全部退化为 no-op：
        // 任何指标调用都不影响 SSE 主流程。
        assertDoesNotThrow(metrics::connectionEstablished);
        assertDoesNotThrow(() ->
                metrics.connectionEnded(
                        ConversationTurnSseMetrics.End.COMPLETED
                )
        );
        assertDoesNotThrow(metrics::countCapacityRejected);
        assertDoesNotThrow(metrics::countErrorSendFailure);
        assertDoesNotThrow(writer::markTimeout);
        assertDoesNotThrow(writer::markClientDisconnected);

        assertEquals(0, metrics.activeConnections());
    }

    private static double count(
            MeterRegistry registry,
            String name
    ) {
        Counter counter = registry.find(name).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    void shouldRejectNullEmitter() {
        assertThrows(
                NullPointerException.class,
                () -> new ConversationTurnSseEventWriter(null)
        );
    }

    @Test
    void shouldRejectNullEvent() {
        ConversationTurnSseEventWriter writer =
                new ConversationTurnSseEventWriter(
                        mock(SseEmitter.class)
                );

        assertThrows(
                NullPointerException.class,
                () -> writer.onEvent(null)
        );
    }

    @Test
    void shouldRejectNullError() {
        ConversationTurnSseEventWriter writer =
                new ConversationTurnSseEventWriter(
                        mock(SseEmitter.class)
                );

        assertThrows(
                NullPointerException.class,
                () -> writer.sendError(
                        null,
                        new IllegalStateException("boom")
                )
        );
        assertThrows(
                NullPointerException.class,
                () -> writer.sendError(
                        ERROR,
                        null
                )
        );
    }
}
