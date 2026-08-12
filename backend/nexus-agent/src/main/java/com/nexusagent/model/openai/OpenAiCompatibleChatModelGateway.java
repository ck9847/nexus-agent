package com.nexusagent.model.openai;

import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.model.api.ChatModelException;
import com.nexusagent.model.api.ChatModelGateway;
import com.nexusagent.model.api.ChatModelRequest;
import com.nexusagent.model.api.ChatModelStreamHandler;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public final class OpenAiCompatibleChatModelGateway
        implements ChatModelGateway {

    private final RestClient restClient;
    private final OpenAiCompatibleProperties properties;
    private final OpenAiChatCompletionRequestMapper requestMapper;
    private final OpenAiChatCompletionStreamDecoder streamDecoder;
    private final OpenAiCompatibleErrorMapper errorMapper;

    public OpenAiCompatibleChatModelGateway(
            RestClient restClient,
            OpenAiCompatibleProperties properties,
            OpenAiChatCompletionRequestMapper requestMapper,
            OpenAiChatCompletionStreamDecoder streamDecoder,
            OpenAiCompatibleErrorMapper errorMapper
    ) {
        this.restClient = Objects.requireNonNull(
                restClient,
                "restClient must not be null"
        );
        this.properties = Objects.requireNonNull(
                properties,
                "properties must not be null"
        );
        this.requestMapper = Objects.requireNonNull(
                requestMapper,
                "requestMapper must not be null"
        );
        this.streamDecoder = Objects.requireNonNull(
                streamDecoder,
                "streamDecoder must not be null"
        );
        this.errorMapper = Objects.requireNonNull(
                errorMapper,
                "errorMapper must not be null"
        );

        if (!properties.enabled()
                || properties.apiKey() == null) {
            throw new IllegalArgumentException(
                    "OpenAI-compatible gateway "
                            + "requires enabled configuration"
            );
        }
    }

    @Override
    public AgentModelProvider provider() {
        return AgentModelProvider.OPENAI;
    }

    @Override
    public void stream(
            ChatModelRequest request,
            ChatModelStreamHandler handler
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );
        Objects.requireNonNull(
                handler,
                "handler must not be null"
        );

        try {
            Boolean processed = restClient.post()
                    .uri(properties.chatCompletionsUri())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .headers(headers ->
                            headers.setBearerAuth(
                                    properties.apiKey()
                            )
                    )
                    .body(requestMapper.map(request))
                    .exchange((httpRequest, response) -> {
                        int status =
                                response.getStatusCode()
                                        .value();

                        if (!response.getStatusCode()
                                .is2xxSuccessful()) {
                            discardErrorBody(response.getBody());

                            throw errorMapper.fromHttpStatus(
                                    status
                            );
                        }

                        MediaType contentType =
                                response.getHeaders()
                                        .getContentType();

                        if (contentType == null
                                || !MediaType
                                .TEXT_EVENT_STREAM
                                .isCompatibleWith(
                                        contentType
                                )) {
                            throw new ChatModelException(
                                    ChatModelErrorCategory
                                            .MALFORMED_RESPONSE,
                                    "Chat model provider did not "
                                            + "return an SSE stream",
                                    status,
                                    null
                            );
                        }

                        streamDecoder.decode(
                                response.getBody(),
                                handler
                        );

                        return Boolean.TRUE;
                    });

            if (!Boolean.TRUE.equals(processed)) {
                throw new ChatModelException(
                        ChatModelErrorCategory
                                .MALFORMED_RESPONSE,
                        "Chat model provider returned "
                                + "an empty response"
                );
            }
        } catch (ChatModelException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw errorMapper.fromTransport(exception);
        } catch (RestClientException exception) {
            throw new ChatModelException(
                    ChatModelErrorCategory
                            .PROVIDER_UNAVAILABLE,
                    "Chat model provider request failed",
                    null,
                    exception
            );
        }
    }

    private void discardErrorBody(InputStream input) {
        try {
            byte[] buffer = new byte[4_096];
            int remaining =
                    properties.maxErrorBodyBytes();

            while (remaining > 0) {
                int read = input.read(
                        buffer,
                        0,
                        Math.min(buffer.length, remaining)
                );

                if (read < 0) {
                    return;
                }

                remaining -= read;
            }
        } catch (IOException ignored) {
            // HTTP status is more useful than an error-body read failure.
        }
    }
}