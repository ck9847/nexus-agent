package com.nexusagent.tool.internal;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

@Component
public final class ToolExecutionIdempotencyKeyFactory {

    private static final String KEY_PREFIX = "tool:v1:";

    private static final String CLIENT_TURN_KEY_PREFIX =
            "tool:turn:v1:";

    private static final String HASH_DOMAIN =
            "nexusagent:tool-execution:v1";

    private static final int MAX_TOOL_CALL_ID_LENGTH = 128;
    private static final int MAX_TOOL_NAME_LENGTH = 64;

    private static final Pattern TOOL_NAME_PATTERN =
            Pattern.compile("[a-z][a-z0-9_]{0,63}");

    public String create(
            long tenantId,
            long conversationId,
            long agentId,
            long requestMessageId,
            String toolCallId,
            String toolName
    ) {
        requirePositive(tenantId, "tenantId");
        requirePositive(conversationId, "conversationId");
        requirePositive(agentId, "agentId");
        requirePositive(
                requestMessageId,
                "requestMessageId"
        );

        String normalizedCallId = normalizeRequired(
                toolCallId,
                "toolCallId",
                MAX_TOOL_CALL_ID_LENGTH
        );

        String normalizedToolName = normalizeRequired(
                toolName,
                "toolName",
                MAX_TOOL_NAME_LENGTH
        );

        if (!TOOL_NAME_PATTERN.matcher(
                normalizedToolName
        ).matches()) {
            throw new IllegalArgumentException(
                    "toolName must use lowercase letters, "
                            + "numbers, and underscores"
            );
        }

        MessageDigest digest = newDigest();

        updateString(digest, HASH_DOMAIN);
        updateLong(digest, tenantId);
        updateLong(digest, conversationId);
        updateLong(digest, agentId);
        updateLong(digest, requestMessageId);
        updateString(digest, normalizedCallId);
        updateString(digest, normalizedToolName);

        return KEY_PREFIX
                + HexFormat.of()
                .formatHex(digest.digest());
    }

    /**
     * 为携带客户端轮次幂等键的注册派生幂等键。
     *
     * <p>与 {@link #create} 的按调用身份派生不同：客户端重放同一
     * {@code Idempotency-Key}（例如网络超时后的重试）时，两次注册
     * 会碰撞到同一唯一键，由既有的 FOR UPDATE 重放/冲突机制保证
     * 不创建第二次工具执行、不重复创建工单。
     *
     * <p>作用域为 (tenant, conversation, clientTurnKey)：
     * conversationId 是请求 URL 的一部分，属于同一逻辑轮次
     * 重试的必要条件。
     */
    public String createForClientTurn(
            long tenantId,
            long conversationId,
            String clientTurnKey
    ) {
        requirePositive(tenantId, "tenantId");
        requirePositive(conversationId, "conversationId");

        String normalizedKey = normalizeRequired(
                clientTurnKey,
                "clientTurnKey",
                MAX_TOOL_CALL_ID_LENGTH
        );

        MessageDigest digest = newDigest();

        updateString(digest, HASH_DOMAIN);
        updateString(digest, CLIENT_TURN_KEY_PREFIX);
        updateLong(digest, tenantId);
        updateLong(digest, conversationId);
        updateString(digest, normalizedKey);

        return CLIENT_TURN_KEY_PREFIX
                + HexFormat.of()
                .formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is not available",
                    exception
            );
        }
    }

    private static void updateLong(
            MessageDigest digest,
            long value
    ) {
        digest.update(
                ByteBuffer.allocate(Long.BYTES)
                        .putLong(value)
                        .array()
        );
    }

    private static void updateString(
            MessageDigest digest,
            String value
    ) {
        byte[] bytes =
                value.getBytes(StandardCharsets.UTF_8);

        digest.update(
                ByteBuffer.allocate(Integer.BYTES)
                        .putInt(bytes.length)
                        .array()
        );
        digest.update(bytes);
    }

    private static void requirePositive(
            long value,
            String field
    ) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    field + " must be positive"
            );
        }
    }

    private static String normalizeRequired(
            String value,
            String field,
            int maximumLength
    ) {
        if (value == null) {
            throw new IllegalArgumentException(
                    field + " must not be null"
            );
        }

        String normalized = value.trim();

        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank"
            );
        }

        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " must not exceed "
                            + maximumLength
                            + " characters"
            );
        }

        return normalized;
    }
}