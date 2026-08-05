package com.nexusagent.identity.internal;

import com.nexusagent.audit.api.AuditLogCommand;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.common.id.IdGenerator;
import com.nexusagent.identity.api.BootstrapTenantRequest;
import com.nexusagent.identity.api.BootstrapTenantResponse;
import com.nexusagent.identity.api.TenantCodeAlreadyExistsException;
import com.nexusagent.identity.internal.persistence.RoleMapper;
import com.nexusagent.identity.internal.persistence.TenantMapper;
import com.nexusagent.identity.internal.persistence.TenantRow;
import com.nexusagent.identity.internal.persistence.UserMapper;
import com.nexusagent.identity.internal.persistence.UserRoleMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultTenantBootstrapServiceTest {

    private final TenantMapper tenantMapper = mock(TenantMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final RoleMapper roleMapper = mock(RoleMapper.class);
    private final UserRoleMapper userRoleMapper =
            mock(UserRoleMapper.class);
    private final IdGenerator idGenerator = mock(IdGenerator.class);
    private final PasswordEncoder passwordEncoder =
            mock(PasswordEncoder.class);
    private final AuditLogWriter auditLogWriter =
            mock(AuditLogWriter.class);

    private final DefaultTenantBootstrapService service =
            new DefaultTenantBootstrapService(
                    tenantMapper,
                    userMapper,
                    roleMapper,
                    userRoleMapper,
                    idGenerator,
                    passwordEncoder,
                    auditLogWriter
            );

    @Test
    void shouldBootstrapTenantAndAdministrator() {
        when(tenantMapper.existsByCode("acme-corp"))
                .thenReturn(false);

        when(idGenerator.nextId())
                .thenReturn(101L, 102L, 103L);

        when(passwordEncoder.encode("StrongPassword123!"))
                .thenReturn("$2a$12$encoded");

        when(tenantMapper.insert(any())).thenReturn(1);
        when(userMapper.insert(any())).thenReturn(1);
        when(roleMapper.insert(any())).thenReturn(1);
        when(userRoleMapper.insert(any())).thenReturn(1);

        BootstrapTenantRequest request =
                new BootstrapTenantRequest(
                        " ACME-CORP ",
                        " Acme Corporation ",
                        " Admin ",
                        " ADMIN@ACME.EXAMPLE ",
                        "StrongPassword123!"
                );

        BootstrapTenantResponse response =
                service.bootstrap(request);

        assertEquals("101", response.tenantId());
        assertEquals("102", response.adminUserId());
        assertEquals("103", response.adminRoleId());

        ArgumentCaptor<TenantRow> tenantCaptor =
                ArgumentCaptor.forClass(TenantRow.class);

        verify(tenantMapper).insert(tenantCaptor.capture());

        assertEquals("acme-corp", tenantCaptor.getValue().code());
        assertEquals(
                "Acme Corporation",
                tenantCaptor.getValue().name()
        );

        ArgumentCaptor<AuditLogCommand> auditCaptor =
                ArgumentCaptor.forClass(AuditLogCommand.class);

        verify(auditLogWriter).write(auditCaptor.capture());

        Object afterData = auditCaptor.getValue().afterData();

        assertFalse(
                ((Map<?, ?>) afterData)
                        .containsKey("adminPassword")
        );

        verify(idGenerator, times(3)).nextId();
    }

    @Test
    void shouldRejectDuplicateTenantCode() {
        when(tenantMapper.existsByCode("acme-corp"))
                .thenReturn(true);

        BootstrapTenantRequest request =
                new BootstrapTenantRequest(
                        "acme-corp",
                        "Acme Corporation",
                        "admin",
                        "admin@acme.example",
                        "StrongPassword123!"
                );

        assertThrows(
                TenantCodeAlreadyExistsException.class,
                () -> service.bootstrap(request)
        );

        verifyNoInteractions(
                userMapper,
                roleMapper,
                userRoleMapper,
                idGenerator,
                passwordEncoder,
                auditLogWriter
        );
    }
}