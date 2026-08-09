package com.nexusagent.ticket.domain;

import java.util.Objects;

public final class TicketStatusTransitionPolicy {

    private TicketStatusTransitionPolicy() {
    }

    public static boolean isAllowed(
            TicketStatus currentStatus,
            TicketStatus targetStatus
    ) {
        Objects.requireNonNull(
                currentStatus,
                "currentStatus must not be null"
        );

        Objects.requireNonNull(
                targetStatus,
                "targetStatus must not be null"
        );

        return switch (currentStatus) {
            case OPEN ->
                    targetStatus
                            == TicketStatus.IN_PROGRESS;

            case IN_PROGRESS ->
                    targetStatus
                            == TicketStatus.RESOLVED;

            case RESOLVED ->
                    targetStatus
                            == TicketStatus.IN_PROGRESS
                            || targetStatus
                            == TicketStatus.CLOSED;

            case CLOSED -> false;
        };
    }

    public static void requireAllowed(
            TicketStatus currentStatus,
            TicketStatus targetStatus
    ) {
        if (!isAllowed(
                currentStatus,
                targetStatus
        )) {
            throw new InvalidTicketStatusTransitionException(
                    currentStatus,
                    targetStatus
            );
        }
    }
}