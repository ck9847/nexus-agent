ALTER TABLE tickets
DROP INDEX idx_tickets_tenant_status_created,

    ADD INDEX idx_tickets_tenant_created_id
        (
            tenant_id,
            created_at DESC,
            id DESC
        ),

    ADD INDEX idx_tickets_tenant_status_created_id
        (
            tenant_id,
            status,
            created_at DESC,
            id DESC
        ),

    ADD INDEX idx_tickets_tenant_priority_created_id
        (
            tenant_id,
            priority,
            created_at DESC,
            id DESC
        ),

    ADD INDEX idx_tickets_tenant_status_priority_created_id
        (
            tenant_id,
            status,
            priority,
            created_at DESC,
            id DESC
        );