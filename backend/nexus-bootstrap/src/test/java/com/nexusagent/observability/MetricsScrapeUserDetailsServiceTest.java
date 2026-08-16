package com.nexusagent.observability;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsScrapeUserDetailsServiceTest {

    private static final String USERNAME = "prometheus";

    private static final String PASSWORD =
            "p".repeat(MetricsScrapeProperties
                    .MIN_PASSWORD_LENGTH);

    @Test
    void shouldLoadMachineIdentityWithMetricsRoleOnly() {
        MetricsScrapeUserDetailsService service =
                new MetricsScrapeUserDetailsService(
                        new MetricsScrapeProperties(
                                true,
                                USERNAME,
                                PASSWORD
                        ),
                        new BCryptPasswordEncoder(4)
                );

        UserDetails user = service.loadUserByUsername(USERNAME);

        assertEquals(USERNAME, user.getUsername());
        assertTrue(user.isEnabled());
        assertTrue(user.isAccountNonLocked());
        assertTrue(user.isCredentialsNonExpired());

        // 密码只以编码后形态持有，绝不等于明文。
        assertNotEquals(PASSWORD, user.getPassword());
        assertTrue(user.getPassword().startsWith("$2"));

        // 权限固定且唯一：ROLE_METRICS，不携带任何业务角色。
        assertEquals(
                1,
                user.getAuthorities().size()
        );
        assertEquals(
                "ROLE_METRICS",
                user.getAuthorities()
                        .stream()
                        .map(GrantedAuthority::getAuthority)
                        .findFirst()
                        .orElseThrow()
        );
    }

    @Test
    void shouldHonorConfiguredUsername() {
        // 启用态的用户名被固定为 "prometheus"（见
        // MetricsScrapePropertiesTest），自定义用户名只在
        // 未启用抓取安全时合法；此处验证服务总是返回
        // properties 携带的用户名。
        MetricsScrapeUserDetailsService service =
                new MetricsScrapeUserDetailsService(
                        new MetricsScrapeProperties(
                                false,
                                "scraper",
                                ""
                        ),
                        new BCryptPasswordEncoder(4)
                );

        assertEquals(
                "scraper",
                service.loadUserByUsername("scraper")
                        .getUsername()
        );
    }

    @Test
    void shouldRejectUnknownUsername() {
        MetricsScrapeUserDetailsService service =
                new MetricsScrapeUserDetailsService(
                        new MetricsScrapeProperties(
                                true,
                                USERNAME,
                                PASSWORD
                        ),
                        new BCryptPasswordEncoder(4)
                );

        UsernameNotFoundException exception =
                assertThrows(
                        UsernameNotFoundException.class,
                        () -> service.loadUserByUsername(
                                "someone-else"
                        )
                );

        // 失败消息不含任何输入值，不做用户名枚举。
        assertEquals(
                "Unknown metrics scrape user",
                exception.getMessage()
        );
    }

    @Test
    void shouldReturnFreshUserDetailsAfterCredentialErasure() {
        // 回归测试：Spring Security 认证成功后会对 principal 执行
        // eraseCredentials()，把 User 的密码字段原地置为 null。
        // 若实现缓存并复用同一个 User 实例，第一次成功抓取之后
        // 的所有认证都会因 "Empty encoded password" 失败。
        MetricsScrapeUserDetailsService service =
                new MetricsScrapeUserDetailsService(
                        new MetricsScrapeProperties(
                                true,
                                USERNAME,
                                PASSWORD
                        ),
                        new BCryptPasswordEncoder(4)
                );

        UserDetails first =
                service.loadUserByUsername(USERNAME);

        // 模拟 ProviderManager 认证成功后的凭据擦除。
        ((CredentialsContainer) first).eraseCredentials();

        assertNull(first.getPassword());

        // 后续查找必须返回全新的实例，密码必须仍为非空哈希。
        UserDetails second =
                service.loadUserByUsername(USERNAME);

        assertNotSame(first, second);
        assertNotEquals("", second.getPassword());
        assertTrue(
                new BCryptPasswordEncoder(4).matches(
                        PASSWORD,
                        second.getPassword()
                ),
                "returned user must still match "
                        + "the configured password"
        );
    }
}
