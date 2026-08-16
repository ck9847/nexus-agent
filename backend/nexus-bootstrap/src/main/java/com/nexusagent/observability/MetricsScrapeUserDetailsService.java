package com.nexusagent.observability;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Objects;

/**
 * Prometheus 指标抓取专用机器身份的 {@link UserDetailsService}。
 *
 * <p>该身份只持有固定权限 {@code ROLE_METRICS}，不持有任何
 * 业务角色，因此绝不可能通过 JWT 资源服务器访问 {@code /api/**}。
 *
 * <p>凭据安全：
 * <ul>
 *     <li>密码只以 {@link PasswordEncoder} 编码后的形态持有；</li>
 *     <li>本类绝不记录用户名、密码或 Authorization header；</li>
 *     <li>用户名查找失败使用不含任何输入值的固定消息，
 *         不做用户名枚举。</li>
 * </ul>
 *
 * <p>实现要点：每次 {@code loadUserByUsername} 都构造一个新的
 * {@link User} 返回，绝不缓存并复用同一个实例。Spring Security 在
 * 认证成功后会对 principal 执行 {@code eraseCredentials()}，而
 * {@link User#eraseCredentials()} 会把密码字段原地置为 null；
 * 若复用同一实例，第一次成功抓取之后的所有认证都会因
 * "Empty encoded password" 失败。
 */
public class MetricsScrapeUserDetailsService
        implements UserDetailsService {

    private final String username;
    private final String encodedPassword;

    public MetricsScrapeUserDetailsService(
            MetricsScrapeProperties properties,
            PasswordEncoder passwordEncoder
    ) {
        Objects.requireNonNull(
                properties,
                "properties must not be null"
        );
        Objects.requireNonNull(
                passwordEncoder,
                "passwordEncoder must not be null"
        );

        this.username = properties.username();
        this.encodedPassword = passwordEncoder.encode(
                properties.password()
        );
    }

    @Override
    public UserDetails loadUserByUsername(
            String username
    ) throws UsernameNotFoundException {
        if (!this.username.equals(username)) {
            throw new UsernameNotFoundException(
                    "Unknown metrics scrape user"
            );
        }

        return User.withUsername(username)
                .password(encodedPassword)
                .roles("METRICS")
                .build();
    }
}
