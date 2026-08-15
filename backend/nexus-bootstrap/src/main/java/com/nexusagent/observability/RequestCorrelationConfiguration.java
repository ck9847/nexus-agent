package com.nexusagent.observability;

import com.nexusagent.common.id.IdGenerator;
import com.nexusagent.common.observability.RequestCorrelationProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.task.TaskDecorator;

/**
 * 装配请求关联过滤器、异步传播装饰器与上下文 provider。
 *
 * <p>过滤器以最高优先级注册，先于 Spring Security 链执行，
 * 保证 401/403 等安全层短路响应也能携带关联头。
 */
@Configuration(proxyBeanMethods = false)
public class RequestCorrelationConfiguration {

    @Bean
    public RequestCorrelationFilter requestCorrelationFilter(
            IdGenerator idGenerator
    ) {
        return new RequestCorrelationFilter(idGenerator);
    }

    @Bean
    public FilterRegistrationBean<RequestCorrelationFilter>
    requestCorrelationFilterRegistration(
            RequestCorrelationFilter filter
    ) {
        FilterRegistrationBean<RequestCorrelationFilter> registration =
                new FilterRegistrationBean<>(filter);

        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");

        return registration;
    }

    @Bean
    public TaskDecorator requestCorrelationTaskDecorator() {
        return new RequestCorrelationTaskDecorator();
    }

    @Bean
    public RequestCorrelationProvider requestCorrelationProvider() {
        return ThreadLocalRequestCorrelationContext::current;
    }
}
