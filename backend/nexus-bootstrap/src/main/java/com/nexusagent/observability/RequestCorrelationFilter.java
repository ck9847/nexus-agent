package com.nexusagent.observability;

import com.nexusagent.common.id.IdGenerator;
import com.nexusagent.common.observability.RequestCorrelation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 为每个 HTTP 请求建立关联标识：
 *
 * <ul>
 *     <li>读取 {@code X-Request-Id}、{@code X-Trace-Id}，
 *         合法则原样采用；非法、空白或超长则丢弃并重新生成；</li>
 *     <li>缺少 traceId 时默认使用（可能新生成的）requestId；</li>
 *     <li>ipAddress 取 {@code request.getRemoteAddr()}，
 *         不信任未配置可信代理前提下的 X-Forwarded-For；</li>
 *     <li>关联写入 ThreadLocal 与 SLF4J MDC，并以
 *         {@code X-Request-Id}/{@code X-Trace-Id} 响应头回传；</li>
 *     <li>{@code finally} 中严格清理 ThreadLocal 与 MDC，
 *         因此即使请求以 400/401/403/404/500 结束，响应头与
 *         清理语义都成立。</li>
 * </ul>
 */
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private static final int MAX_ID_LENGTH = 64;

    private static final Pattern ID_PATTERN =
            Pattern.compile("[A-Za-z0-9._-]{1," + MAX_ID_LENGTH + "}");

    private final IdGenerator idGenerator;

    public RequestCorrelationFilter(IdGenerator idGenerator) {
        this.idGenerator = Objects.requireNonNull(
                idGenerator,
                "idGenerator must not be null"
        );
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = resolveOrGenerate(
                request.getHeader(REQUEST_ID_HEADER)
        );
        String traceId = resolveOrAdopt(
                request.getHeader(TRACE_ID_HEADER),
                requestId
        );
        String ipAddress = request.getRemoteAddr();

        RequestCorrelation correlation =
                new RequestCorrelation(
                        requestId,
                        traceId,
                        ipAddress
                );

        ThreadLocalRequestCorrelationContext.set(correlation);
        ThreadLocalRequestCorrelationContext.attachMdc(correlation);

        // 响应头必须在链执行前设置：某些端点（如 actuator health）
        // 会在链内提交响应，提交后追加响应头会被静默丢弃，
        // 导致 200/401/403/404/500 等响应丢失关联头。
        addHeaderSafely(
                response,
                REQUEST_ID_HEADER,
                requestId
        );
        addHeaderSafely(
                response,
                TRACE_ID_HEADER,
                traceId
        );

        try {
            filterChain.doFilter(request, response);
        } finally {
            ThreadLocalRequestCorrelationContext.clearMdc();
            ThreadLocalRequestCorrelationContext.clear();
        }
    }

    private String resolveOrGenerate(String headerValue) {
        if (isValidCorrelationId(headerValue)) {
            return headerValue;
        }

        return Long.toString(idGenerator.nextId());
    }

    private static String resolveOrAdopt(
            String headerValue,
            String fallback
    ) {
        if (isValidCorrelationId(headerValue)) {
            return headerValue;
        }

        return fallback;
    }

    static boolean isValidCorrelationId(String value) {
        return value != null && ID_PATTERN.matcher(value).matches();
    }

    private static void addHeaderSafely(
            HttpServletResponse response,
            String name,
            String value
    ) {
        try {
            response.setHeader(name, value);
        } catch (IllegalStateException ignored) {
            // 响应已提交（例如 SSE 传输已开始）：无法再补充
            // 关联头。线程上下文与 MDC 仍会被 finally 清理。
        }
    }
}
