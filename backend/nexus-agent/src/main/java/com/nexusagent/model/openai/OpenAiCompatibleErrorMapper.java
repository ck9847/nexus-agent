package com.nexusagent.model.openai;

import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.model.api.ChatModelException;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpTimeoutException;
import java.util.Objects;

public final class OpenAiCompatibleErrorMapper {

    public ChatModelException fromHttpStatus(int status) {
        ChatModelErrorCategory category;

        if (status == 401 || status == 403) {
            category =
                    ChatModelErrorCategory.AUTHENTICATION;
        } else if (status == 408) {
            category = ChatModelErrorCategory.TIMEOUT;
        } else if (status == 429) {
            category = ChatModelErrorCategory.RATE_LIMIT;
        } else if (status >= 400 && status < 500) {
            category =
                    ChatModelErrorCategory.INVALID_REQUEST;
        } else if (status >= 500 && status < 600) {
            category = ChatModelErrorCategory
                    .PROVIDER_UNAVAILABLE;
        } else {
            category = ChatModelErrorCategory
                    .MALFORMED_RESPONSE;
        }

        return new ChatModelException(
                category,
                safeMessage(category),
                status,
                null
        );
    }

    public ChatModelException fromTransport(
            RestClientException exception
    ) {
        Objects.requireNonNull(
                exception,
                "exception must not be null"
        );

        ChatModelErrorCategory category =
                containsCause(
                        exception,
                        HttpTimeoutException.class
                )
                        || containsCause(
                        exception,
                        java.net.SocketTimeoutException.class
                )
                        ? ChatModelErrorCategory.TIMEOUT
                        : ChatModelErrorCategory.CONNECTION;

        return new ChatModelException(
                category,
                safeMessage(category),
                null,
                exception
        );
    }

    private static boolean containsCause(
            Throwable throwable,
            Class<? extends Throwable> type
    ) {
        Throwable current = throwable;

        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }

    private static String safeMessage(
            ChatModelErrorCategory category
    ) {
        return switch (category) {
            case AUTHENTICATION ->
                    "Chat model provider authentication failed";
            case INVALID_REQUEST ->
                    "Chat model provider rejected the request";
            case RATE_LIMIT ->
                    "Chat model provider rate limit exceeded";
            case TIMEOUT ->
                    "Chat model provider request timed out";
            case CONNECTION ->
                    "Could not connect to chat model provider";
            case PROVIDER_UNAVAILABLE ->
                    "Chat model provider is unavailable";
            case MALFORMED_RESPONSE ->
                    "Chat model provider returned "
                            + "an invalid response";
            default ->
                    "Chat model provider request failed";
        };
    }
}