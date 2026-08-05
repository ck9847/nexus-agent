package com.nexusagent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
public class PasswordEncoderConfiguration {

    @Bean
    public PasswordEncoder passwordEncoder(
            @Value("${nexus.security.bcrypt-strength:12}")
            int strength
    ) {
        return new BCryptPasswordEncoder(strength);
    }
}
