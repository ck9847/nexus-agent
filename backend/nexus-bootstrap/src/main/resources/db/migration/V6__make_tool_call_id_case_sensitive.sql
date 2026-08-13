-- Model-generated tool call IDs are opaque,
-- case-sensitive identifiers.


ALTER TABLE tool_executions
    MODIFY COLUMN tool_call_id VARCHAR(128)
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_0900_bin
        NOT NULL
        COMMENT 'Model-generated tool call ID';
