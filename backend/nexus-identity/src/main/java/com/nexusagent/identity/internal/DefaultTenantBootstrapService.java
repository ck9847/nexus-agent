package com.nexusagent.identity.internal;

import com.nexusagent.audit.api.AuditActorType;
import com.nexusagent.audit.api.AuditLogCommand;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.audit.api.AuditResult;
import com.nexusagent.common.id.IdGenerator;
import com.nexusagent.identity.api.BootstrapTenantRequest;
import com.nexusagent.identity.api.BootstrapTenantResponse;
import com.nexusagent.identity.api.TenantBootstrapService;
import com.nexusagent.identity.api.TenantCodeAlreadyExistsException;
import com.nexusagent.identity.domain.TenantStatus;
import com.nexusagent.identity.domain.UserStatus;
import com.nexusagent.identity.internal.persistence.RoleMapper;
import com.nexusagent.identity.internal.persistence.RoleRow;
import com.nexusagent.identity.internal.persistence.TenantMapper;
import com.nexusagent.identity.internal.persistence.TenantRow;
import com.nexusagent.identity.internal.persistence.UserMapper;
import com.nexusagent.identity.internal.persistence.UserRoleMapper;
import com.nexusagent.identity.internal.persistence.UserRoleRow;
import com.nexusagent.identity.internal.persistence.UserRow;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

@Service
public class DefaultTenantBootstrapService implements TenantBootstrapService {

    private static final String ADMIN_ROLE_CODE = "ADMIN";

    private final TenantMapper tenantMapper;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final IdGenerator idGenerator;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogWriter auditLogWriter;

    public DefaultTenantBootstrapService(
            TenantMapper tenantMapper,
            UserMapper userMapper,
            RoleMapper roleMapper,
            UserRoleMapper userRoleMapper,
            IdGenerator idGenerator,
            PasswordEncoder passwordEncoder,
            AuditLogWriter auditLogWriter
    ) {
        this.tenantMapper = tenantMapper;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.idGenerator = idGenerator;
        this.passwordEncoder = passwordEncoder;
        this.auditLogWriter = auditLogWriter;
    }

    @Override
    @Transactional
    public BootstrapTenantResponse bootstrap(
            BootstrapTenantRequest request
    ) {
        String tenantCode = normalizeLowercase(request.tenantCode());
        String tenantName = request.tenantName().trim();
        String adminUsername =
                normalizeLowercase(request.adminUsername());
        String adminEmail =
                normalizeLowercase(request.adminEmail());

        validatePasswordByteLength(request.adminPassword());

        if (tenantMapper.existsByCode(tenantCode)) {
            throw new TenantCodeAlreadyExistsException(tenantCode);
        }

        long tenantId = idGenerator.nextId();
        long adminUserId = idGenerator.nextId();
        long adminRoleId = idGenerator.nextId();

        String passwordHash =
                passwordEncoder.encode(request.adminPassword());

        TenantRow tenant = new TenantRow(
                tenantId,
                tenantCode,
                tenantName,
                TenantStatus.ACTIVE,
                0
        );

        UserRow adminUser = new UserRow(
                adminUserId,
                tenantId,
                adminUsername,
                adminEmail,
                passwordHash,
                adminUsername,
                UserStatus.ACTIVE,
                0
        );

        RoleRow adminRole = new RoleRow(
                adminRoleId,
                tenantId,
                ADMIN_ROLE_CODE,
                "Administrator",
                "Tenant administrator with full access"
        );

        UserRoleRow assignment = new UserRoleRow(
                tenantId,
                adminUserId,
                adminRoleId,
                adminUserId
        );

        requireSingleRow(
                tenantMapper.insert(tenant),
                "tenant"
        );

        requireSingleRow(
                userMapper.insert(adminUser),
                "admin user"
        );

        requireSingleRow(
                roleMapper.insert(adminRole),
                "admin role"
        );

        requireSingleRow(
                userRoleMapper.insert(assignment),
                "user role assignment"
        );

        auditLogWriter.write(new AuditLogCommand(
                tenantId,
                AuditActorType.SYSTEM,
                null,
                "TENANT_BOOTSTRAPPED",
                "TENANT",
                tenantId,
                null,
                AuditResult.SUCCESS,
                null,
                null,
                null,
                null,
                Map.of(
                        "tenantCode", tenantCode,
                        "adminUsername", adminUsername,
                        "adminRole", ADMIN_ROLE_CODE
                ),
                null,
                null
        ));

        return new BootstrapTenantResponse(
                Long.toString(tenantId),
                Long.toString(adminUserId),
                Long.toString(adminRoleId)
        );
    }

    private static String normalizeLowercase(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static void validatePasswordByteLength(String password) {
        int byteLength =
                password.getBytes(StandardCharsets.UTF_8).length;

        if (byteLength > 72) {
            throw new IllegalArgumentException(
                    "BCrypt password must not exceed 72 UTF-8 bytes"
            );
        }
    }

    private static void requireSingleRow(
            int affectedRows,
            String entityName
    ) {
        if (affectedRows != 1) {
            throw new IllegalStateException(
                    "Expected one " + entityName
                            + " row to be inserted"
            );
        }
    }
}