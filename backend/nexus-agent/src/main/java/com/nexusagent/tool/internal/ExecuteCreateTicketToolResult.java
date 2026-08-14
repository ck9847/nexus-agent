package com.nexusagent.tool.internal;

import com.nexusagent.ticket.domain.TicketStatus;

import java.time.Instant;
import java.util.Objects;

public record ExecuteCreateTicketToolResult(
        long toolExecutionId,
        String ticketId,
        String ticketNo,
        TicketStatus ticketStatus,
        long resultMessageId,
        long resultMessageSequenceNo,
        long assistantMessageId,
        long assistantSequenceNo,
        int conversationVersion,
        Instant assistantPreparedAt,
        boolean replayed
) {

    public ExecuteCreateTicketToolResult {
        if (toolExecutionId <= 0
                || resultMessageId <= 0
                || resultMessageSequenceNo <= 0
                || assistantMessageId <= 0
                || assistantSequenceNo <= 0) {
            throw new IllegalArgumentException(
                    "Tool result IDs and sequences "
                            + "must be positive"
            );
        }

        if (assistantSequenceNo
                != resultMessageSequenceNo + 1) {
            throw new IllegalArgumentException(
                    "Assistant sequence must immediately "
                            + "follow the result message"
            );
        }

        if (resultMessageId == assistantMessageId) {
            throw new IllegalArgumentException(
                    "Result and assistant message IDs "
                            + "must differ"
            );
        }

        if (conversationVersion <= 0) {
            throw new IllegalArgumentException(
                    "conversationVersion must be positive"
            );
        }

        if (ticketId == null
                || ticketId.isBlank()) {
            throw new IllegalArgumentException(
                    "ticketId must not be blank"
            );
        }

        if (ticketNo == null
                || ticketNo.isBlank()) {
            throw new IllegalArgumentException(
                    "ticketNo must not be blank"
            );
        }

        Objects.requireNonNull(
                ticketStatus,
                "ticketStatus must not be null"
        );
        Objects.requireNonNull(
                assistantPreparedAt,
                "assistantPreparedAt must not be null"
        );
    }
}
