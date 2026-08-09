package com.nexusagent.ticket.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.nexusagent.ticket.domain.InvalidTicketStatusTransitionException;
import com.nexusagent.ticket.domain.TicketVersionConflictException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(
        basePackageClasses = TicketController.class
)
public class TicketExceptionHandler {

    @ExceptionHandler(TicketNotFoundException.class)
    public ProblemDetail handleTicketNotFound(
            TicketNotFoundException exception
    ) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.NOT_FOUND,
                        "Ticket not found"
                );

        problem.setTitle("Ticket not found");
        problem.setProperty(
                "errorCode",
                "TICKET_NOT_FOUND"
        );

        return problem;
    }

    @ExceptionHandler(InvalidTicketQueryException.class)
    public ProblemDetail handleInvalidTicketQuery(
            InvalidTicketQueryException exception
    ) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST,
                        exception.getMessage()
                );

        problem.setTitle("Invalid ticket query");
        problem.setProperty(
                "errorCode",
                "INVALID_TICKET_QUERY"
        );

        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(
            MethodArgumentNotValidException exception
    ) {
        Map<String, String> errors =
                new LinkedHashMap<>();

        exception.getBindingResult()
                .getFieldErrors()
                .forEach(error -> errors.putIfAbsent(
                        error.getField(),
                        error.getDefaultMessage()
                ));

        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST,
                        "One or more request fields are invalid"
                );

        problem.setTitle("Request validation failed");
        problem.setProperty(
                "errorCode",
                "VALIDATION_FAILED"
        );
        problem.setProperty("errors", errors);

        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableRequest(
            HttpMessageNotReadableException exception
    ) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST,
                        "Request body is malformed or "
                                + "contains an unsupported value"
                );

        problem.setTitle("Malformed request");
        problem.setProperty(
                "errorCode",
                "MALFORMED_REQUEST"
        );

        return problem;
    }

    @ExceptionHandler(
            InvalidTicketStatusTransitionException.class
    )
    public ProblemDetail handleInvalidStatusTransition(
            InvalidTicketStatusTransitionException exception
    ) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.CONFLICT,
                        exception.getMessage()
                );

        problem.setTitle(
                "Invalid ticket status transition"
        );
        problem.setProperty(
                "errorCode",
                "INVALID_TICKET_STATUS_TRANSITION"
        );
        problem.setProperty(
                "currentStatus",
                exception.currentStatus().name()
        );
        problem.setProperty(
                "targetStatus",
                exception.targetStatus().name()
        );

        return problem;
    }

    @ExceptionHandler(TicketVersionConflictException.class)
    public ProblemDetail handleTicketVersionConflict(
            TicketVersionConflictException exception
    ) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.CONFLICT,
                        exception.getMessage()
                );

        problem.setTitle("Ticket version conflict");
        problem.setProperty(
                "errorCode",
                "TICKET_VERSION_CONFLICT"
        );

        return problem;
    }

}