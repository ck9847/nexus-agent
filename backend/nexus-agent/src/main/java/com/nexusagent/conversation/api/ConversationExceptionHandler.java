package com.nexusagent.conversation.api;

import com.nexusagent.agent.api.AgentNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(
        basePackageClasses =
                ConversationController.class
)
public class ConversationExceptionHandler {

    @ExceptionHandler(
            MethodArgumentNotValidException.class
    )
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
                        "One or more request fields "
                                + "are invalid"
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

    @ExceptionHandler(
            HttpMessageNotReadableException.class
    )
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

    @ExceptionHandler(AgentNotFoundException.class)
    public ProblemDetail handleActiveAgentNotFound(
            AgentNotFoundException exception
    ) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.NOT_FOUND,
                        "Active Agent not found"
                );

        problem.setTitle(
                "Active Agent not found"
        );
        problem.setProperty(
                "errorCode",
                "ACTIVE_AGENT_NOT_FOUND"
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

    @ExceptionHandler(ConversationNotFoundException.class)
    public ProblemDetail handleConversationNotFound(
            ConversationNotFoundException exception
    ) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.NOT_FOUND,
                        "Conversation not found"
                );

        problem.setTitle("Conversation not found");
        problem.setProperty(
                "errorCode",
                "CONVERSATION_NOT_FOUND"
        );

        return problem;
    }

    @ExceptionHandler(ConversationNotActiveException.class)
    public ProblemDetail handleConversationNotActive(
            ConversationNotActiveException exception
    ) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.CONFLICT,
                        "Conversation is not active"
                );

        problem.setTitle(
                "Conversation is not active"
        );

        problem.setProperty(
                "errorCode",
                "CONVERSATION_NOT_ACTIVE"
        );

        problem.setProperty(
                "currentStatus",
                exception.currentStatus().name()
        );

        return problem;
    }
}