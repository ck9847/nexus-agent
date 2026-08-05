package com.nexusagent.config;


import com.nexusagent.common.id.IdGenerator;
import com.nexusagent.common.id.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class IdGeneratorConfiguration {

    @Bean
    public IdGenerator idGenerator(
            @Value("${nexus.id.node-id:0}") long nodeId
    ) {
        return new SnowflakeIdGenerator(nodeId);
    }
}
