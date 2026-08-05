-- NexusAgent agent and ticket schema.
-- Applied migrations must never be modified.

CREATE TABLE agents
(
    id                 BIGINT       NOT NULL COMMENT 'Application-generated ID',
    tenant_id          BIGINT       NOT NULL COMMENT 'Owning tenant',
    code               VARCHAR(64)  NOT NULL COMMENT 'Tenant-unique agent code',
    name               VARCHAR(128) NOT NULL COMMENT 'Agent name',
    description        VARCHAR(500) NULL COMMENT 'Agent description',
    system_prompt      LONGTEXT     NOT NULL COMMENT 'Agent system prompt',
    model_provider     VARCHAR(64)  NOT NULL COMMENT 'Model provider',
    model_name         VARCHAR(128) NOT NULL COMMENT 'Model name',
    model_config       JSON         NULL COMMENT 'Non-secret model configuration',
    status             VARCHAR(32)  NOT NULL COMMENT 'DRAFT, ACTIVE or DISABLED',
    created_by_user_id BIGINT       NOT NULL COMMENT 'User who created the agent',
    version            INT          NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                              ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_agents_tenant_code (tenant_id, code),
    KEY idx_agents_tenant_status (tenant_id, status),
    KEY idx_agents_created_by_user (created_by_user_id),

    CONSTRAINT fk_agents_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT,

    CONSTRAINT fk_agents_created_by_user
        FOREIGN KEY (created_by_user_id) REFERENCES users (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT,

    CONSTRAINT chk_agents_status
        CHECK (status IN ('DRAFT', 'ACTIVE', 'DISABLED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Tenant agent definitions';


CREATE TABLE tickets
(
    id                  BIGINT       NOT NULL COMMENT 'Application-generated ID',
    tenant_id           BIGINT       NOT NULL COMMENT 'Owning tenant',
    ticket_no           VARCHAR(32)  NOT NULL COMMENT 'Public ticket number',
    title               VARCHAR(255) NOT NULL COMMENT 'Ticket title',
    description         TEXT         NOT NULL COMMENT 'Problem description',
    priority            VARCHAR(16)  NOT NULL COMMENT 'LOW, MEDIUM, HIGH or URGENT',
    status              VARCHAR(32)  NOT NULL COMMENT 'Ticket lifecycle status',
    source              VARCHAR(32)  NOT NULL COMMENT 'USER, AGENT or API',
    requester_user_id   BIGINT       NOT NULL COMMENT 'User requesting assistance',
    assignee_user_id    BIGINT       NULL COMMENT 'Current ticket assignee',
    created_by_agent_id BIGINT       NULL COMMENT 'Agent that created the ticket',
    version             INT          NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                               ON UPDATE CURRENT_TIMESTAMP(3),
    closed_at           DATETIME(3)  NULL COMMENT 'Ticket close time',

    PRIMARY KEY (id),
    UNIQUE KEY uk_tickets_tenant_ticket_no (tenant_id, ticket_no),
    KEY idx_tickets_tenant_status_created
        (tenant_id, status, created_at),
    KEY idx_tickets_tenant_requester
        (tenant_id, requester_user_id),
    KEY idx_tickets_tenant_assignee_status
        (tenant_id, assignee_user_id, status),
    KEY idx_tickets_created_by_agent
        (created_by_agent_id),

    CONSTRAINT fk_tickets_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT,

    CONSTRAINT fk_tickets_requester
        FOREIGN KEY (requester_user_id) REFERENCES users (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT,

    CONSTRAINT fk_tickets_assignee
        FOREIGN KEY (assignee_user_id) REFERENCES users (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT,

    CONSTRAINT fk_tickets_created_by_agent
        FOREIGN KEY (created_by_agent_id) REFERENCES agents (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT,

    CONSTRAINT chk_tickets_priority
        CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),

    CONSTRAINT chk_tickets_status
        CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),

    CONSTRAINT chk_tickets_source
        CHECK (source IN ('USER', 'AGENT', 'API')),

    CONSTRAINT chk_tickets_agent_source
        CHECK (source <> 'AGENT' OR created_by_agent_id IS NOT NULL),

    CONSTRAINT chk_tickets_closed_at
        CHECK (status <> 'CLOSED' OR closed_at IS NOT NULL)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Support tickets';