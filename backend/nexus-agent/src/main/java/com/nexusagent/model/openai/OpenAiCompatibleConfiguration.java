package com.nexusagent.model.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.model.api.ChatModelGateway;
import org.springframework.boot.autoconfigure.condition
        .ConditionalOnProperty;
import org.springframework.boot.context.properties
        .EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client
        .JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
        OpenAiCompatibleProperties.class
)
@ConditionalOnProperty(
        prefix = "nexus.model.openai",
        name = "enabled",
        havingValue = "true"
)
public class OpenAiCompatibleConfiguration {

    @Bean
    OpenAiChatCompletionRequestMapper
    openAiChatCompletionRequestMapper(
            ObjectMapper objectMapper
    ) {
        return new JacksonOpenAiChatCompletionRequestMapper(
                objectMapper
        );
    }

    @Bean
    OpenAiChatCompletionStreamDecoder
    openAiChatCompletionStreamDecoder(
            ObjectMapper objectMapper
    ) {
        return new JacksonOpenAiChatCompletionStreamDecoder(
                objectMapper
        );
    }

    @Bean
    OpenAiCompatibleErrorMapper
    openAiCompatibleErrorMapper() {
        return new OpenAiCompatibleErrorMapper();
    }

    @Bean
    RestClient openAiRestClient(
            OpenAiCompatibleProperties properties
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(
                        properties.connectTimeout()
                )
                .followRedirects(
                        HttpClient.Redirect.NEVER
                )
                .build();

        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(
                        httpClient
                );

        requestFactory.setReadTimeout(
                properties.readTimeout()
        );

        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    ChatModelGateway openAiCompatibleChatModelGateway(
            RestClient openAiRestClient,
            OpenAiCompatibleProperties properties,
            OpenAiChatCompletionRequestMapper requestMapper,
            OpenAiChatCompletionStreamDecoder streamDecoder,
            OpenAiCompatibleErrorMapper errorMapper
    ) {
        return new OpenAiCompatibleChatModelGateway(
                openAiRestClient,
                properties,
                requestMapper,
                streamDecoder,
                errorMapper
        );
    }
}