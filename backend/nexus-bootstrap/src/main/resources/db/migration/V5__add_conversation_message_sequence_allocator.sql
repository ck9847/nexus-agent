ALTER TABLE conversations
    ADD COLUMN next_message_sequence BIGINT NULL
        COMMENT 'Next message sequence to allocate'
        AFTER last_message_at;

UPDATE conversations AS c
    LEFT JOIN
    (
    SELECT
    conversation_id,
    MAX(sequence_no) + 1 AS next_sequence
    FROM messages
    GROUP BY conversation_id
    ) AS m
ON m.conversation_id = c.id
    SET c.next_message_sequence =
        COALESCE(m.next_sequence, 1);

ALTER TABLE conversations
    MODIFY COLUMN next_message_sequence BIGINT
    NOT NULL
    COMMENT 'Next message sequence to allocate';

ALTER TABLE conversations
    ADD CONSTRAINT chk_conversations_next_message_sequence
        CHECK (next_message_sequence > 0);