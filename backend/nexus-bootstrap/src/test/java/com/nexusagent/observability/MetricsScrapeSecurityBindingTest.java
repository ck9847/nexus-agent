package com.nexusagent.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实属性绑定验证：enabled=true 且密码为空或不足 32 位时
 * 应用必须启动失败，且启动失败输出不得泄漏密码值。
 */
class MetricsScrapeSecurityBindingTest {

    private static final String LONG_ENOUGH_PASSWORD =
            "p".repeat(MetricsScrapeProperties
                    .MIN_PASSWORD_LENGTH);

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            BindingConfiguration.class
                    );

    @Test
    void shouldFailStartupWhenEnabledWithShortPassword() {
        runner.withPropertyValues(
                        "nexus.observability.metrics-scrape"
                                + ".enabled=true",
                        "nexus.observability.metrics-scrape"
                                + ".username=prometheus",
                        "nexus.observability.metrics-scrape"
                                + ".password=too-short-secret-value"
                )
                .run(context -> {
                    assertThat(context).hasFailed();

                    assertThat(context.getStartupFailure())
                            .hasMessageNotContaining(
                                    "too-short-secret-value"
                            );
                });
    }

    @Test
    void shouldFailStartupWhenEnabledWithBlankPassword() {
        runner.withPropertyValues(
                        "nexus.observability.metrics-scrape"
                                + ".enabled=true",
                        "nexus.observability.metrics-scrape"
                                + ".username=prometheus",
                        "nexus.observability.metrics-scrape"
                                + ".password="
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldStartWhenEnabledWithLongEnoughPassword() {
        runner.withPropertyValues(
                        "nexus.observability.metrics-scrape"
                                + ".enabled=true",
                        "nexus.observability.metrics-scrape"
                                + ".username=prometheus",
                        "nexus.observability.metrics-scrape"
                                + ".password="
                                + LONG_ENOUGH_PASSWORD
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    assertThat(context).hasSingleBean(
                            MetricsScrapeProperties.class
                    );
                });
    }

    @Test
    void shouldStartWhenDisabledWithEmptyPassword() {
        runner.withPropertyValues(
                        "nexus.observability.metrics-scrape"
                                + ".enabled=false",
                        "nexus.observability.metrics-scrape"
                                + ".username=prometheus",
                        "nexus.observability.metrics-scrape"
                                + ".password="
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MetricsScrapeProperties.class)
    static class BindingConfiguration {
    }
}
