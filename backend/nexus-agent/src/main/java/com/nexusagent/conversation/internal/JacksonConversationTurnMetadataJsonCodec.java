package com.nexusagent.conversation.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

@Component
public final class JacksonConversationTurnMetadataJsonCodec
        implements ConversationTurnMetadataJsonCodec {

    private final ObjectWriter writer;

    public JacksonConversationTurnMetadataJsonCodec(
            ObjectMapper objectMapper
    ) {
        ObjectMapper configured =
                Objects.requireNonNull(
                        objectMapper,
                        "objectMapper must not be null"
                ).copy();

        this.writer = configured.writerFor(Map.class);
    }

    @Override
    public String encode(Map<String, ?> metadata) {
        Objects.requireNonNull(
                metadata,
                "metadata must not be null"
        );

        try {
            return writer.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Failed to serialize "
                            + "conversation turn metadata",
                    exception
            );
        }
    }
}