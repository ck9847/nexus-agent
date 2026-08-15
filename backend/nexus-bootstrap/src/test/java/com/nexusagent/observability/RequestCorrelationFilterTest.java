package com.nexusagent.observability;

import com.nexusagent.common.id.SnowflakeIdGenerator;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯单元验证请求关联过滤器：
 * <ul>
 *   <li>无 header 自动生成并回传；</li>
 *   <li>合法 header 原样传播，非法/空白/超长 header 被替换；</li>
 *   <li>两次请求生成的 ID 不同；</li>
 *   <li>401/404 等错误响应同样携带关联头；</li>
 *   <li>过滤器异常后 ThreadLocal 与 MDC 严格清理。</li>
 * </ul>
 */
class RequestCorrelationFilterTest {

    private final RequestCorrelationFilter filter =
            new RequestCorrelationFilter(
                    new SnowflakeIdGenerator(0)
            );

    @AfterEach
    void clearContext() {
        ThreadLocalRequestCorrelationContext.clear();
        ThreadLocalRequestCorrelationContext.clearMdc();
    }

    @Test
    void shouldGenerateCorrelationWhenHeadersMissing()
            throws Exception {
        MockHttpServletResponse response =
                runFilter(request("/health"), passthrough());

        String requestId = response.getHeader(
                RequestCorrelationFilter.REQUEST_ID_HEADER
        );
        String traceId = response.getHeader(
                RequestCorrelationFilter.TRACE_ID_HEADER
        );

        assertTrue(
                RequestCorrelationFilter.isValidCorrelationId(
                        requestId
                ),
                "generated requestId must be valid: " + requestId
        );
        assertTrue(
                RequestCorrelationFilter.isValidCorrelationId(
                        traceId
                ),
                "generated traceId must be valid: " + traceId
        );

        // 缺少 traceId 时默认使用新生成的 requestId。
        assertEquals(requestId, traceId);
    }

    @Test
    void shouldPropagateValidHeadersVerbatim() throws Exception {
        MockHttpServletRequest request = request("/health");

        request.addHeader(
                RequestCorrelationFilter.REQUEST_ID_HEADER,
                "req-abc.123"
        );
        request.addHeader(
                RequestCorrelationFilter.TRACE_ID_HEADER,
                "trace-X_y-9"
        );

        MockHttpServletResponse response =
                runFilter(request, passthrough());

        assertEquals(
                "req-abc.123",
                response.getHeader(
                        RequestCorrelationFilter.REQUEST_ID_HEADER
                )
        );
        assertEquals(
                "trace-X_y-9",
                response.getHeader(
                        RequestCorrelationFilter.TRACE_ID_HEADER
                )
        );
    }

    @Test
    void shouldReplaceInvalidHeaders() throws Exception {
        MockHttpServletRequest request = request("/health");

        request.addHeader(
                RequestCorrelationFilter.REQUEST_ID_HEADER,
                "bad id!"
        );
        request.addHeader(
                RequestCorrelationFilter.TRACE_ID_HEADER,
                "trace-with space"
        );

        MockHttpServletResponse response =
                runFilter(request, passthrough());

        String requestId = response.getHeader(
                RequestCorrelationFilter.REQUEST_ID_HEADER
        );
        String traceId = response.getHeader(
                RequestCorrelationFilter.TRACE_ID_HEADER
        );

        assertNotEquals("bad id!", requestId);
        assertTrue(
                RequestCorrelationFilter.isValidCorrelationId(
                        requestId
                )
        );

        // 非法 traceId 被替换为采用后的 requestId。
        assertEquals(requestId, traceId);
    }

    @Test
    void shouldReplaceBlankHeaders() throws Exception {
        MockHttpServletRequest request = request("/health");

        request.addHeader(
                RequestCorrelationFilter.REQUEST_ID_HEADER,
                "   "
        );
        request.addHeader(
                RequestCorrelationFilter.TRACE_ID_HEADER,
                ""
        );

        MockHttpServletResponse response =
                runFilter(request, passthrough());

        String requestId = response.getHeader(
                RequestCorrelationFilter.REQUEST_ID_HEADER
        );
        String traceId = response.getHeader(
                RequestCorrelationFilter.TRACE_ID_HEADER
        );

        assertTrue(
                RequestCorrelationFilter.isValidCorrelationId(
                        requestId
                )
        );
        assertEquals(requestId, traceId);
    }

    @Test
    void shouldReplaceOversizedHeaders() throws Exception {
        String oversized = "a".repeat(65);

        MockHttpServletRequest request = request("/health");

        request.addHeader(
                RequestCorrelationFilter.REQUEST_ID_HEADER,
                oversized
        );

        MockHttpServletResponse response =
                runFilter(request, passthrough());

        String requestId = response.getHeader(
                RequestCorrelationFilter.REQUEST_ID_HEADER
        );

        assertNotEquals(oversized, requestId);
        assertTrue(
                RequestCorrelationFilter.isValidCorrelationId(
                        requestId
                )
        );
    }

    @Test
    void shouldGenerateDistinctIdsAcrossRequests() throws Exception {
        MockHttpServletResponse first = runFilter(
                request("/first"),
                passthrough()
        );
        MockHttpServletResponse second = runFilter(
                request("/second"),
                passthrough()
        );

        String firstRequestId = first.getHeader(
                RequestCorrelationFilter.REQUEST_ID_HEADER
        );
        String secondRequestId = second.getHeader(
                RequestCorrelationFilter.REQUEST_ID_HEADER
        );

        assertNotNull(firstRequestId);
        assertNotNull(secondRequestId);
        assertNotEquals(firstRequestId, secondRequestId);
    }

    @Test
    void shouldCarryCorrelationOnUnauthorizedResponse()
            throws Exception {
        MockHttpServletResponse response = runFilter(
                request("/api/v1/conversations"),
                (req, res) -> ((MockHttpServletResponse) res)
                        .setStatus(401)
        );

        assertEquals(401, response.getStatus());
        assertCarriesValidCorrelationHeaders(response);
    }

    @Test
    void shouldCarryCorrelationOnNotFoundResponse()
            throws Exception {
        MockHttpServletResponse response = runFilter(
                request("/api/v1/definitely-missing"),
                (req, res) -> ((MockHttpServletResponse) res)
                        .setStatus(404)
        );

        assertEquals(404, response.getStatus());
        assertCarriesValidCorrelationHeaders(response);
    }

    @Test
    void shouldCarryCorrelationOnServerErrorResponse()
            throws Exception {
        MockHttpServletResponse response = runFilter(
                request("/api/v1/explode"),
                (req, res) -> ((MockHttpServletResponse) res)
                        .setStatus(500)
        );

        assertEquals(500, response.getStatus());
        assertCarriesValidCorrelationHeaders(response);
    }

    @Test
    void shouldClearContextAfterFilterException() throws Exception {
        MockHttpServletRequest request = request("/boom");

        request.setRemoteAddr("192.168.1.7");

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        FilterChain exploding = (req, res) -> {
            throw new RuntimeException("boom");
        };

        assertThrows(
                RuntimeException.class,
                () -> filter.doFilter(
                        request,
                        response,
                        exploding
                )
        );

        // 异常后 ThreadLocal 与 MDC 必须已清理。
        assertNull(ThreadLocalRequestCorrelationContext.currentOrNull());
        assertNull(MDC.get(
                ThreadLocalRequestCorrelationContext.MDC_REQUEST_ID
        ));
        assertNull(MDC.get(
                ThreadLocalRequestCorrelationContext.MDC_TRACE_ID
        ));

        // 即使失败，关联头也已回传。
        assertNotNull(response.getHeader(
                RequestCorrelationFilter.REQUEST_ID_HEADER
        ));
        assertNotNull(response.getHeader(
                RequestCorrelationFilter.TRACE_ID_HEADER
        ));
    }

    @Test
    void shouldSetCorrelationAndMdcDuringFilterChain()
            throws Exception {
        MockHttpServletRequest request = request("/health");

        request.addHeader(
                RequestCorrelationFilter.REQUEST_ID_HEADER,
                "req-in-chain"
        );
        request.addHeader(
                RequestCorrelationFilter.TRACE_ID_HEADER,
                "trace-in-chain"
        );

        final String[] seenRequestId = new String[1];
        final String[] seenTraceId = new String[1];

        MockHttpServletResponse response = runFilter(
                request,
                (req, res) -> {
                    seenRequestId[0] = MDC.get(
                            ThreadLocalRequestCorrelationContext
                                    .MDC_REQUEST_ID
                    );
                    seenTraceId[0] = MDC.get(
                            ThreadLocalRequestCorrelationContext
                                    .MDC_TRACE_ID
                    );
                }
        );

        assertEquals("req-in-chain", seenRequestId[0]);
        assertEquals("trace-in-chain", seenTraceId[0]);
        assertEquals(
                "req-in-chain",
                response.getHeader(
                        RequestCorrelationFilter.REQUEST_ID_HEADER
                )
        );
    }

    private MockHttpServletResponse runFilter(
            MockHttpServletRequest request,
            FilterChain chain
    ) throws Exception {
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        return response;
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", path);

        request.setRemoteAddr("192.168.1.7");

        return request;
    }

    private static FilterChain passthrough() {
        return (req, res) -> {
        };
    }

    private static void assertCarriesValidCorrelationHeaders(
            MockHttpServletResponse response
    ) {
        String requestId = response.getHeader(
                RequestCorrelationFilter.REQUEST_ID_HEADER
        );
        String traceId = response.getHeader(
                RequestCorrelationFilter.TRACE_ID_HEADER
        );

        assertNotNull(requestId);
        assertNotNull(traceId);
        assertTrue(
                RequestCorrelationFilter.isValidCorrelationId(
                        requestId
                ),
                "requestId must be valid: " + requestId
        );
        assertTrue(
                RequestCorrelationFilter.isValidCorrelationId(
                        traceId
                ),
                "traceId must be valid: " + traceId
        );
    }
}
