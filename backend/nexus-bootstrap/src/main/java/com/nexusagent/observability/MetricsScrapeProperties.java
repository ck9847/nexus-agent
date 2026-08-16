package com.nexusagent.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Prometheus 指标抓取专用机器身份配置。
 *
 * <p>通过 {@code NEXUS_METRICS_SCRAPE_ENABLED} 等环境变量注入；
 * 未启用时保留现有 ADMIN JWT 访问 {@code /actuator/prometheus}
 * 的行为。
 *
 * <p>安全约束：
 * <ul>
 *     <li>启用时密码必须至少 {@value #MIN_PASSWORD_LENGTH} 个字符，
 *         否则应用启动失败；</li>
 *     <li>校验消息绝不携带密码值；</li>
 *     <li>{@link #toString()} 屏蔽密码，防止任何日志、启动报告或
 *         配置诊断输出泄漏凭据。</li>
 * </ul>
 */
@ConfigurationProperties(
        prefix = "nexus.observability.metrics-scrape"
)
public record MetricsScrapeProperties(
        boolean enabled,
        String username,
        String password
) {

    public static final int MIN_PASSWORD_LENGTH = 32;

    /**
     * Prometheus 静态配置与 smoke script 使用的固定机器用户名。
     * 密码仍由环境变量/secret file 注入。
     */
    public static final String REQUIRED_USERNAME = "prometheus";

    public MetricsScrapeProperties {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException(
                    "Metrics scrape username must not be blank"
            );
        }

        if (enabled && (password == null
                || password.length() < MIN_PASSWORD_LENGTH)) {
            throw new IllegalArgumentException(
                    "Metrics scrape password must contain at "
                            + "least " + MIN_PASSWORD_LENGTH
                            + " characters when metrics scrape "
                            + "security is enabled"
            );
        }

        if (enabled && !REQUIRED_USERNAME.equals(username)) {
            throw new IllegalArgumentException(
                    "Metrics scrape username must be prometheus "
                            + "when metrics scrape security is enabled"
            );
        }
    }

    @Override
    public String toString() {
        return "MetricsScrapeProperties{enabled=" + enabled
                + ", username='" + username
                + "', password=[REDACTED]}";
    }
}
