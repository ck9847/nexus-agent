package com.nexusagent.ticket.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

}