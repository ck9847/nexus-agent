package com.nexusagent.identity.api;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class IdentityExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(
            MethodArgumentNotValidException exception
    ) {
        Map<String, String> errors = new LinkedHashMap<>();

        exception.getBindingResult()
                .getFieldErrors()
                .forEach(error -> errors.putIfAbsent(
                        error.getField(),
                        error.getDefaultMessage()
                ));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "One or more request fields are invalid"
        );

        problem.setTitle("Request validation failed");
        problem.setProperty("errorCode", "VALIDATION_FAILED");
        problem.setProperty("errors", errors);

        return problem;
    }

    @ExceptionHandler(TenantCodeAlreadyExistsException.class)
    public ProblemDetail handleDuplicateTenant(
            TenantCodeAlreadyExistsException exception
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                exception.getMessage()
        );

        problem.setTitle("Tenant already exists");
        problem.setProperty(
                "errorCode",
                "TENANT_CODE_ALREADY_EXISTS"
        );
        problem.setProperty(
                "tenantCode",
                exception.getTenantCode()
        );

        return problem;
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ProblemDetail handleDuplicateKey(
            DuplicateKeyException exception
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "A resource with the same unique value already exists"
        );

        problem.setTitle("Resource conflict");
        problem.setProperty(
                "errorCode",
                "RESOURCE_ALREADY_EXISTS"
        );

        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(
            IllegalArgumentException exception
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
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

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail handleInvalidCredentials(
            InvalidCredentialsException exception
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED,
                "Invalid tenant code, username or password"
        );

        problem.setTitle("Authentication failed");
        problem.setProperty(
                "errorCode",
                "INVALID_CREDENTIALS"
        );

        return problem;
    }
}