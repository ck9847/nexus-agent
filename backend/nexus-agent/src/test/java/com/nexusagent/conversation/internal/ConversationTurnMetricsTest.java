package com.nexusagent.conversation.internal;

import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.testing.ThrowingMeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversationTurnMetricsTest {

    @Test
    void shouldPreRegisterBoundedModelSeriesForFirstFailureDetection() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new ConversationTurnMetrics(registry);

        assertNotNull(
                registry.find(
                                ConversationTurnMetrics
                                        .MODEL_CALL_METRIC
                        )
                        .tags(
                                ConversationTurnMetrics.TAG_PROVIDER,
                                "OPENAI",
                                ConversationTurnMetrics.TAG_OUTCOME,
                                ConversationTurnMetrics.OUTCOME_SUCCESS,
                                ConversationTurnMetrics
                                        .TAG_ERROR_CATEGORY,
                                ConversationTurnMetrics
                                        .ERROR_CATEGORY_NONE
                        )
                        .timer()
        );

        for (String category : Set.of(
                "AUTHENTICATION",
                "RATE_LIMIT",
                "TIMEOUT",
                "PROVIDER_ERROR",
                "MALFORMED_RESPONSE",
                "STREAM_INTERRUPTED"
        )) {
            assertNotNull(
                    registry.find(
                                    ConversationTurnMetrics
                                            .MODEL_CALL_METRIC
                            )
                            .tags(
                                    ConversationTurnMetrics.TAG_PROVIDER,
                                    "OPENAI",
                                    ConversationTurnMetrics.TAG_OUTCOME,
                                    ConversationTurnMetrics.OUTCOME_FAILURE,
                                    ConversationTurnMetrics
                                            .TAG_ERROR_CATEGORY,
                                    category
                            )
                            .timer(),
                    category
            );
        }

        assertEquals(7, registry.getMeters().stream()
                .filter(meter -> ConversationTurnMetrics
                        .MODEL_CALL_METRIC
                        .equals(meter.getId().getName()))
                .count());
    }

    @Test
    void shouldRecordTimerWhenHealthy() {
        ThrowingMeterRegistry registry =
                new ThrowingMeterRegistry();
        ConversationTurnMetrics metrics =
                new ConversationTurnMetrics(registry);

        ConversationTurnMetrics.Sample sample =
                metrics.startTimer();

        sample.stop(
                ConversationTurnMetrics.MODEL_CALL_METRIC,
                ConversationTurnMetrics.TAG_OUTCOME,
                ConversationTurnMetrics.OUTCOME_SUCCESS
        );

        Timer timer = registry.find(
                        ConversationTurnMetrics.MODEL_CALL_METRIC
                )
                .tag(
                        ConversationTurnMetrics.TAG_OUTCOME,
                        ConversationTurnMetrics.OUTCOME_SUCCESS
                )
                .timer();

        assertNotNull(timer);
        assertEquals(1.0, timer.count());
    }

    @Test
    void shouldReturnNoOpSampleWhenTimerStartFails() {
        ThrowingMeterRegistry registry =
                new ThrowingMeterRegistry();
        registry.throwOnTimerStart();

        ConversationTurnMetrics metrics =
                new ConversationTurnMetrics(registry);

        ConversationTurnMetrics.Sample sample =
                metrics.startTimer();

        assertDoesNotThrow(() -> sample.stop(
                ConversationTurnMetrics.TURN_DURATION_METRIC,
                ConversationTurnMetrics.TAG_OUTCOME,
                "COMPLETED_TEXT"
        ));

        assertNull(registry.find(
                        ConversationTurnMetrics.TURN_DURATION_METRIC
                )
                .timer());
    }

    @Test
    void shouldSwallowTimerCreationFailureOnStop() {
        ThrowingMeterRegistry registry =
                new ThrowingMeterRegistry();
        ConversationTurnMetrics metrics =
                new ConversationTurnMetrics(registry);

        ConversationTurnMetrics.Sample sample =
                metrics.startTimer();

        registry.throwOnTimerCreation();

        // 构造函数已为 MODEL_CALL_METRIC 预注册零基线 timer，
        // 这里改用未预注册的 TURN_DURATION_METRIC：
        // stop 期间 timer 创建抛错必须被吞掉且不产生任何新 timer。
        assertDoesNotThrow(() -> sample.stop(
                ConversationTurnMetrics.TURN_DURATION_METRIC,
                ConversationTurnMetrics.TAG_OUTCOME,
                ConversationTurnMetrics.OUTCOME_SUCCESS
        ));

        assertNull(registry.find(
                        ConversationTurnMetrics.TURN_DURATION_METRIC
                )
                .timer());
    }

    @Test
    void shouldIncrementCounterWhenHealthy() {
        ThrowingMeterRegistry registry =
                new ThrowingMeterRegistry();
        ConversationTurnMetrics metrics =
                new ConversationTurnMetrics(registry);

        metrics.incrementCounter("nexus.test.counter", "k", "v");

        assertEquals(
                1.0,
                registry.counter("nexus.test.counter", "k", "v")
                        .count()
        );
    }

    @Test
    void shouldSwallowCounterFailure() {
        ThrowingMeterRegistry registry =
                new ThrowingMeterRegistry();
        registry.throwOnCounterCreation();

        ConversationTurnMetrics metrics =
                new ConversationTurnMetrics(registry);

        assertDoesNotThrow(() ->
                metrics.incrementCounter(
                        "nexus.test.counter",
                        "k",
                        "v"
                )
        );
    }

    @Test
    void shouldRejectNullRegistry() {
        assertThrows(
                NullPointerException.class,
                () -> new ConversationTurnMetrics(null)
        );
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("errorCategoryMappings")
    void shouldMapErrorCategoriesToLowCardinalityTags(
            ChatModelErrorCategory category,
            String expectedTag
    ) {
        assertEquals(
                expectedTag,
                ConversationTurnMetrics.errorCategoryTag(
                        category
                )
        );
    }

    private static Stream<Arguments> errorCategoryMappings() {
        return Stream.of(
                Arguments.of(
                        ChatModelErrorCategory.AUTHENTICATION,
                        ConversationTurnMetrics
                                .ERROR_CATEGORY_AUTHENTICATION
                ),
                Arguments.of(
                        ChatModelErrorCategory.RATE_LIMIT,
                        ConversationTurnMetrics
                                .ERROR_CATEGORY_RATE_LIMIT
                ),
                Arguments.of(
                        ChatModelErrorCategory.TIMEOUT,
                        ConversationTurnMetrics
                                .ERROR_CATEGORY_TIMEOUT
                ),
                Arguments.of(
                        ChatModelErrorCategory.STREAM_INTERRUPTED,
                        ConversationTurnMetrics
                                .ERROR_CATEGORY_STREAM_INTERRUPTED
                ),
                Arguments.of(
                        ChatModelErrorCategory.MALFORMED_RESPONSE,
                        ConversationTurnMetrics
                                .ERROR_CATEGORY_MALFORMED_RESPONSE
                ),
                Arguments.of(
                        ChatModelErrorCategory.INVALID_REQUEST,
                        ConversationTurnMetrics
                                .ERROR_CATEGORY_MALFORMED_RESPONSE
                ),
                Arguments.of(
                        ChatModelErrorCategory.CONNECTION,
                        ConversationTurnMetrics
                                .ERROR_CATEGORY_PROVIDER_ERROR
                ),
                Arguments.of(
                        ChatModelErrorCategory.PROVIDER_UNAVAILABLE,
                        ConversationTurnMetrics
                                .ERROR_CATEGORY_PROVIDER_ERROR
                ),
                Arguments.of(
                        ChatModelErrorCategory.CONTENT_FILTERED,
                        ConversationTurnMetrics
                                .ERROR_CATEGORY_PROVIDER_ERROR
                )
        );
    }
}
