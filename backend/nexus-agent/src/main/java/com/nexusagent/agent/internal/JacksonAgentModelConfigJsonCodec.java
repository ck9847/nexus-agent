package com.nexusagent.agent.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.nexusagent.agent.domain.AgentModelConfig;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class JacksonAgentModelConfigJsonCodec
        implements AgentModelConfigJsonCodec {

    private final ObjectWriter writer;
    private final ObjectReader reader;

    public JacksonAgentModelConfigJsonCodec(
            ObjectMapper objectMapper
    ) {
        ObjectMapper configuredMapper =
                Objects.requireNonNull(
                        objectMapper,
                        "objectMapper must not be null"
                ).copy();

        configuredMapper.setDefaultPropertyInclusion(
                JsonInclude.Include.NON_NULL
        );

        configuredMapper.configure(
                DeserializationFeature
                        .FAIL_ON_UNKNOWN_PROPERTIES,
                true
        );

        this.writer = configuredMapper.writerFor(
                AgentModelConfig.class
        );

        this.reader = configuredMapper.readerFor(
                AgentModelConfig.class
        );
    }

    @Override
    public String encode(
            AgentModelConfig config
    ) {
        if (config == null) {
            return null;
        }

        try {
            return writer.writeValueAsString(config);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Failed to serialize agent model config",
                    exception
            );
        }
    }

    @Override
    public AgentModelConfig decode(
            String json
    ) {
        if (json == null) {
            return null;
        }

        try {
            return reader.readValue(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Failed to deserialize agent model config",
                    exception
            );
        }
    }
}