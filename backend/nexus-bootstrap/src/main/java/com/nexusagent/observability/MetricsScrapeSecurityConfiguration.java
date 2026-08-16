package com.nexusagent.observability;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Prometheus 指标抓取专用安全链。
 *
 * <p>仅在 {@code nexus.observability.metrics-scrape.enabled=true}
 * 时注册。启用后：
 * <ul>
 *     <li>一条独立的高优先级 {@link SecurityFilterChain} 只匹配
 *         {@code GET /actuator/prometheus}；</li>
 *     <li>机器身份通过 HTTP Basic 认证，权限固定为
 *         {@code ROLE_METRICS}；</li>
 *     <li>该链同时接受普通 JWT（与主链共享同一
 *         {@link JwtAuthenticationConverter}），因此 MEMBER 等
 *         已认证但无权限的角色得到 403 而非 401；ADMIN JWT
 *         保持可访问；</li>
 *     <li>{@code ROLE_METRICS} 身份不带任何业务角色，主链只接受
 *         JWT，因此该身份访问任何 {@code /api/**} 都会得到 401；</li>
 *     <li>未启用时本配置整体不生效，保留主链原有的
 *         {@code hasRole("ADMIN")} 行为。</li>
 * </ul>
 *
 * <p>凭据不出现在任何日志中：认证失败消息不含用户名/密码，
 * Authorization header 不被记录。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MetricsScrapeProperties.class)
@ConditionalOnProperty(
        prefix = "nexus.observability.metrics-scrape",
        name = "enabled",
        havingValue = "true"
)
public class MetricsScrapeSecurityConfiguration {

    public static final String PROMETHEUS_PATH =
            "/actuator/prometheus";

    public static final String METRICS_ROLE = "METRICS";

    @Bean
    public MetricsScrapeUserDetailsService
    metricsScrapeUserDetailsService(
            MetricsScrapeProperties properties,
            PasswordEncoder passwordEncoder
    ) {
        return new MetricsScrapeUserDetailsService(
                properties,
                passwordEncoder
        );
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain metricsScrapeSecurityFilterChain(
            HttpSecurity http,
            MetricsScrapeUserDetailsService userDetailsService,
            JwtAuthenticationConverter jwtAuthenticationConverter
    ) throws Exception {
        http
                .securityMatcher(
                        AntPathRequestMatcher.antMatcher(
                                HttpMethod.GET,
                                PROMETHEUS_PATH
                        )
                )
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )
                .authorizeHttpRequests(authorize -> authorize
                        .anyRequest().hasAnyRole(
                                METRICS_ROLE,
                                "ADMIN"
                        )
                )
                .httpBasic(Customizer.withDefaults())
                .userDetailsService(userDetailsService)
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .jwtAuthenticationConverter(
                                        jwtAuthenticationConverter
                                )
                        )
                )
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable());

        return http.build();
    }
}
