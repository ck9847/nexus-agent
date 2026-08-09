package com.nexusagent.agent.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(
        basePackageClasses = AgentController.class
)
public class AgentExceptionHandler {

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

        problem.setTitle(
                "Request validation failed"
        );
        problem.setProperty(
                "errorCode",
                "VALIDATION_FAILED"
        );
        problem.setProperty(
                "errors",
                errors
        );

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
            AgentCodeAlreadyExistsException.class
    )
    public ProblemDetail handleDuplicateAgentCode(
            AgentCodeAlreadyExistsException exception
    ) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.CONFLICT,
                        exception.getMessage()
                );

        problem.setTitle(
                "Agent already exists"
        );
        problem.setProperty(
                "errorCode",
                "AGENT_CODE_ALREADY_EXISTS"
        );
        problem.setProperty(
                "agentCode",
                exception.getAgentCode()
        );

        return problem;
    }

    @ExceptionHandler(
            AgentAdministrationForbiddenException.class
    )
    public ProblemDetail handleAdministrationForbidden(
            AgentAdministrationForbiddenException exception
    ) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.FORBIDDEN,
                        exception.getMessage()
                );

        problem.setTitle(
                "Agent administration forbidden"
        );
        problem.setProperty(
                "errorCode",
                "AGENT_ADMINISTRATION_FORBIDDEN"
        );

        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(
            IllegalArgumentException exception
    ) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST,
                        exception.getMessage()
                );

        problem.setTitle("Invalid request");
        problem.setProperty(
                "errorCode",
                "INVALID_ARGUMENT"
        );

        return problem;
    }
}