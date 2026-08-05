-- NexusAgent conversation, tool execution and audit schema.
-- Applied migrations must never be modified.

CREATE TABLE conversations
(
    id              BIGINT       NOT NULL COMMENT 'Application-generated ID',
    tenant_id       BIGINT       NOT NULL COMMENT 'Owning tenant',
    user_id         BIGINT       NOT NULL COMMENT 'User who started the conversation',
    agent_id        BIGINT       NOT NULL COMMENT 'Agent handling the conversation',
    title           VARCHAR(255) NULL COMMENT 'Conversation title',
    status          VARCHAR(32)  NOT NULL COMMENT 'ACTIVE, COMPLETED or ARCHIVED',
    last_message_at DATETIME(3)  NULL COMMENT 'Time of the latest message',
    version         INT          NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                            ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    KEY idx_conversations_tenant_user_updated
        (tenant_id, user_id, updated_at),
    KEY idx_conversations_tenant_agent_status
        (tenant_id, agent_id, status),

    CONSTRAINT fk_conversations_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT,

    CONSTRAINT fk_conversations_user
        FOREIGN KEY (user_id) REFERENCES users (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT,

    CONSTRAINT fk_conversations_agent
        FOREIGN KEY (agent_id) REFERENCES agents (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT,

    CONSTRAINT chk_conversations_status
        CHECK (status IN ('ACTIVE', 'COMPLETED', 'ARCHIVED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'User and agent conversations';


CREATE TABLE messages
(
    id                BIGINT       NOT NULL COMMENT 'Application-generated ID',
    tenant_id         BIGINT       NOT NULL COMMENT 'Owning tenant',
    conversation_id   BIGINT       NOT NULL COMMENT 'Owning conversation',
    sequence_no       BIGINT       NOT NULL COMMENT 'Monotonically increasing sequence',
    `role`            VARCHAR(32)  NOT NULL COMMENT 'SYSTEM, USER, ASSISTANT or TOOL',
    content           LONGTEXT     NOT NULL COMMENT 'Message content',
    content_type      VARCHAR(32)  NOT NULL COMMENT 'TEXT, MARKDOWN or JSON',
    status            VARCHAR(32)  NOT NULL COMMENT 'CREATING, COMPLETED or FAILED',
    model_name        VARCHAR(128) NULL COMMENT 'Model used to generate the message',
    prompt_tokens     INT          NULL COMMENT 'Input token count',
    completion_tokens INT          NULL COMMENT 'Output token count',
    metadata_json     JSON         NULL COMMENT 'Extensible message metadata',
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_messages_conversation_sequence
        (conversation_id, sequence_no),
    KEY idx_messages_tenant_conversation_created
        (tenant_id, conversation_id, created_at),

    CONSTRAINT fk_messages_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT,

    CONSTRAINT fk_messages_conversation
        FOREIGN KEY (conversation_id) REFERENCES conversations (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT,

    CONSTRAINT chk_messages_sequence
        CHECK (sequence_no > 0),

    CONSTRAINT chk_messages_role
        CHECK (`role` IN ('SYSTEM', 'USER', 'ASSISTANT', 'TOOL')),

    CONSTRAINT chk_messages_content_type
        CHECK (content_type IN ('TEXT', 'MARKDOWN', 'JSON')),

    CONSTRAINT chk_messages_status
        CHECK (status IN ('CREATING', 'COMPLETED', 'FAILED')),

    CONSTRAINT chk_messages_prompt_tokens
        CHECK (prompt_tokens IS NULL OR prompt_tokens >= 0),

    CONSTRAINT chk_messages_completion_tokens
        CHECK (completion_tokens IS NULL OR completion_tokens >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Conversation messages';


CREATE TABLE tool_executions
(
    id                   BIGINT        NOT NULL COMMENT 'Application-generated ID',
    tenant_id            BIGINT        NOT NULL COMMENT 'Owning tenant',
    conversation_id      BIGINT        NOT NULL COMMENT 'Owning conversation',
    agent_id             BIGINT        NOT NULL COMMENT 'Agent requesting execution',
    request_message_id   BIGINT        NULL COMMENT 'Message that triggered the tool',
    result_message_id    BIGINT        NULL COMMENT 'Message containing the tool result',
    tool_call_id         VARCHAR(128)  NOT NULL COMMENT 'Model-generated tool call ID',
    tool_name            VARCHAR(128)  NOT NULL COMMENT 'Registered tool name',
    idempotency_key      VARCHAR(128)  NOT NULL COMMENT 'Duplicate execution prevention key',
    input_json           JSON          NOT NULL COMMENT 'Validated tool arguments',
    output_json          JSON          NULL COMMENT 'Tool execution result',
    status               VARCHAR(32)   NOT NULL COMMENT 'Execution lifecycle status',
    approval_required    BOOLEAN       NOT NULL DEFAULT FALSE COMMENT 'Requires human approval',
    result_entity_type   VARCHAR(64)   NULL COMMENT 'Created or modified entity type',
    result_entity_id     BIGINT        NULL COMMENT 'Created or modified entity ID',
    error_code           VARCHAR(64)   NULL COMMENT 'Stable error code',
    error_message        VARCHAR(1000) NULL COMMENT 'Sanitized error message',
    trace_id             VARCHAR(64)   NULL COMMENT 'Distributed trace ID',
    started_at           DATETIME(3)   NULL COMMENT 'Execution start time',
    completed_at         DATETIME(3)   NULL COMMENT 'Execution completion time',
    duration_ms          BIGINT        NULL COMMENT 'Execution duration in milliseconds',
    created_at           DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at           DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                                ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_tool_executions_tenant_idempotency
        (tenant_id, idempotency_key),
    UNIQUE KEY uk_tool_executions_tenant_conversation_call
        (tenant_id, conversation_id, tool_call_id),
    KEY idx_tool_executions_tenant_conversation_created
        (tenant_id, conversation_id, created_at),
    KEY idx_tool_executions_tenant_status_created
        (tenant_id, status, created_at),
    KEY idx_tool_executions_agent (agent_id),
    KEY idx_tool_executions_request_message (request_message_id),
    KEY idx_tool_executions_result_message (result_message_id),
    KEY idx_tool_executions_trace (trace_id),

    CONSTRAINT fk_tool_executions_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT,

    CONSTRAINT fk_tool_executions_conversation
        FOREIGN KEY (conversation_id) REFERENCES conversations (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT,

    CONSTRAINT fk_tool_executions_agent
        FOREIGN KEY (agent_id) REFERENCES agents (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT,

    CONSTRAINT fk_tool_executions_request_message
        FOREIGN KEY (request_message_id) REFERENCES messages (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT,

    CONSTRAINT fk_tool_executions_result_message
        FOREIGN KEY (result_message_id) REFERENCES messages (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT,

    CONSTRAINT chk_tool_executions_status
        CHECK (
            status IN (
                       'PENDING',
                       'RUNNING',
                       'WAITING_APPROVAL',
                       'SUCCEEDED',
                       'FAILED',
                       'CANCELLED'
                )
            ),

    CONSTRAINT chk_tool_executions_duration
        CHECK (duration_ms IS NULL OR duration_ms >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Agent tool execution lifecycle';


CREATE TABLE audit_logs
(
    id                BIGINT        NOT NULL COMMENT 'Application-generated ID',
    tenant_id         BIGINT        NOT NULL COMMENT 'Owning tenant',
    actor_type        VARCHAR(32)   NOT NULL COMMENT 'USER, AGENT or SYSTEM',
    actor_id          BIGINT        NULL COMMENT 'Polymorphic actor ID',
    action            VARCHAR(128)  NOT NULL COMMENT 'Performed action',
    resource_type     VARCHAR(64)   NOT NULL COMMENT 'Affected resource type',
    resource_id       BIGINT        NULL COMMENT 'Affected resource ID',
    tool_execution_id BIGINT        NULL COMMENT 'Related tool execution',
    result            VARCHAR(32)   NOT NULL COMMENT 'SUCCESS, FAILURE or DENIED',
    request_id        VARCHAR(64)   NULL COMMENT 'HTTP request ID',
    trace_id          VARCHAR(64)   NULL COMMENT 'Distributed trace ID',
    ip_address        VARCHAR(45)   NULL COMMENT 'IPv4 or IPv6 address',
    before_json       JSON          NULL COMMENT 'Resource state before operation',
    after_json        JSON          NULL COMMENT 'Resource state after operation',
    error_code        VARCHAR(64)   NULL COMMENT 'Stable error code',
    error_message     VARCHAR(1000) NULL COMMENT 'Sanitized error message',
    created_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    KEY idx_audit_logs_tenant_resource
        (tenant_id, resource_type, resource_id),
    KEY idx_audit_logs_tenant_actor_created
        (tenant_id, actor_type, actor_id, created_at),
    KEY idx_audit_logs_tenant_created
        (tenant_id, created_at),
    KEY idx_audit_logs_tool_execution
        (tool_execution_id),
    KEY idx_audit_logs_trace
        (trace_id),

    CONSTRAINT fk_audit_logs_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT,

    CONSTRAINT fk_audit_logs_tool_execution
        FOREIGN KEY (tool_execution_id) REFERENCES tool_executions (id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT,

    CONSTRAINT chk_audit_logs_actor_type
        CHECK (actor_type IN ('USER', 'AGENT', 'SYSTEM')),

    CONSTRAINT chk_audit_logs_result
        CHECK (result IN ('SUCCESS', 'FAILURE', 'DENIED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Immutable operation audit records';