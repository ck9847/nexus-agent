-- NexusAgent identity schema.
-- Applied migrations must never be modified.

CREATE TABLE tenants
(
    id         BIGINT       NOT NULL COMMENT 'Application-generated ID',
    code       VARCHAR(64)  NOT NULL COMMENT 'Unique tenant code',
    name       VARCHAR(128) NOT NULL COMMENT 'Tenant name',
    status     VARCHAR(32)  NOT NULL COMMENT 'ACTIVE or DISABLED',
    version    INT          NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                         ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_tenants_code (code),
    KEY idx_tenants_status (status),

    CONSTRAINT chk_tenants_status
        CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Enterprise tenants';


CREATE TABLE users
(
    id            BIGINT       NOT NULL COMMENT 'Application-generated ID',
    tenant_id     BIGINT       NOT NULL COMMENT 'Owning tenant',
    username      VARCHAR(64)  NOT NULL COMMENT 'Login username',
    email         VARCHAR(255) NULL COMMENT 'Email address',
    password_hash VARCHAR(255) NOT NULL COMMENT 'Password hash, never plaintext',
    display_name  VARCHAR(128) NOT NULL COMMENT 'Display name',
    status        VARCHAR(32)  NOT NULL COMMENT 'ACTIVE, LOCKED or DISABLED',
    last_login_at DATETIME(3)  NULL COMMENT 'Most recent login time',
    version       INT          NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                            ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_users_tenant_username (tenant_id, username),
    UNIQUE KEY uk_users_tenant_email (tenant_id, email),
    KEY idx_users_tenant_status (tenant_id, status),

    CONSTRAINT fk_users_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT,

    CONSTRAINT chk_users_status
        CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Tenant users';


CREATE TABLE roles
(
    id          BIGINT       NOT NULL COMMENT 'Application-generated ID',
    tenant_id   BIGINT       NOT NULL COMMENT 'Owning tenant',
    code        VARCHAR(64)  NOT NULL COMMENT 'Role code',
    name        VARCHAR(128) NOT NULL COMMENT 'Role name',
    description VARCHAR(500) NULL COMMENT 'Role description',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                         ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_roles_tenant_code (tenant_id, code),

    CONSTRAINT fk_roles_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Tenant roles';


CREATE TABLE user_roles
(
    tenant_id  BIGINT      NOT NULL COMMENT 'Owning tenant',
    user_id    BIGINT      NOT NULL COMMENT 'Assigned user',
    role_id    BIGINT      NOT NULL COMMENT 'Assigned role',
    assigned_by BIGINT     NULL COMMENT 'User who assigned the role',
    assigned_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (tenant_id, user_id, role_id),
    KEY idx_user_roles_tenant_role (tenant_id, role_id),
    KEY idx_user_roles_user (user_id),
    KEY idx_user_roles_role (role_id),
    KEY idx_user_roles_assigned_by (assigned_by),

    CONSTRAINT fk_user_roles_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT,

    CONSTRAINT fk_user_roles_user
        FOREIGN KEY (user_id) REFERENCES users (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT,

    CONSTRAINT fk_user_roles_role
        FOREIGN KEY (role_id) REFERENCES roles (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT,

    CONSTRAINT fk_user_roles_assigned_by
        FOREIGN KEY (assigned_by) REFERENCES users (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'User and role assignments';