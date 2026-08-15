package com.nexusagent.common.observability;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 单次 HTTP 请求的关联标识，用于日志与审计串联。
 *
 * <p>记录不可变，所有字段禁止 null、空白与控制字符：
 * <ul>
 *     <li>{@code requestId}、{@code traceId}：1～64 字符，
 *         只允许字母、数字、{@code .}、{@code _}、{@code -}；</li>
 *     <li>{@code ipAddress}：最长 45 字符（IPv6 上限）。</li>
 * </ul>
 */
public record RequestCorrelation(
        String requestId,
        String traceId,
        String ipAddress
) {

    private static final int MAX_ID_LENGTH = 64;

    private static final int MAX_IP_ADDRESS_LENGTH = 45;

    private static final Pattern ID_PATTERN =
            Pattern.compile(
                    "[A-Za-z0-9._-]{1," + MAX_ID_LENGTH + "}"
            );

    public RequestCorrelation {
        requestId = requireId(requestId, "requestId");
        traceId = requireId(traceId, "traceId");
        ipAddress = requireIpAddress(ipAddress);
    }

    private static String requireId(
            String value,
            String field
    ) {
        Objects.requireNonNull(
                value,
                field + " must not be null"
        );

        if (!ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    field + " must contain 1 to "
                            + MAX_ID_LENGTH
                            + " characters of letters, digits, "
                            + "'.', '_' or '-'"
            );
        }

        return value;
    }

    private static String requireIpAddress(String value) {
        Objects.requireNonNull(
                value,
                "ipAddress must not be null"
        );

        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "ipAddress must not be blank"
            );
        }

        if (value.length() > MAX_IP_ADDRESS_LENGTH) {
            throw new IllegalArgumentException(
                    "ipAddress must not exceed "
                            + MAX_IP_ADDRESS_LENGTH
                            + " characters"
            );
        }

        for (int index = 0;
                index < value.length();
                index++) {
            char character = value.charAt(index);

            if (Character.isWhitespace(character)
                    || Character.isISOControl(character)) {
                throw new IllegalArgumentException(
                        "ipAddress must not contain "
                                + "whitespace or control characters"
                );
            }
        }

        return value;
    }
}
