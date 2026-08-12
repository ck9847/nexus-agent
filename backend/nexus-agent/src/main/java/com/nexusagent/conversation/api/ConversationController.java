package com.nexusagent.conversation.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private final CreateConversationService
            createConversationService;

    private final AppendUserMessageService
            appendUserMessageService;

    private final ConversationQueryService
            conversationQueryService;

    public ConversationController(
            CreateConversationService createConversationService,
            AppendUserMessageService appendUserMessageService,
            ConversationQueryService conversationQueryService
    ) {
        this.createConversationService =
                createConversationService;

        this.appendUserMessageService =
                appendUserMessageService;

        this.conversationQueryService =
                conversationQueryService;
    }

    @PostMapping
    public ResponseEntity<CreateConversationResponse> create(
            @Valid @RequestBody
            CreateConversationRequest request
    ) {
        CreateConversationResponse response =
                createConversationService.create(
                        request
                );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @PostMapping("/{conversationId}/messages")
    public ResponseEntity<AppendUserMessageResponse>
    appendUserMessage(
            @PathVariable String conversationId,
            @Valid @RequestBody
            AppendUserMessageRequest request
    ) {
        AppendUserMessageResponse response =
                appendUserMessageService.append(
                        conversationId,
                        request
                );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/{conversationId}")
    public ResponseEntity<ConversationDetailResponse>
    getConversation(
            @PathVariable String conversationId
    ) {
        ConversationDetailResponse response =
                conversationQueryService.getById(
                        conversationId
                );

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<ConversationMessagesResponse>
    listMessages(
            @PathVariable String conversationId,
            @RequestParam(
                    defaultValue = "20"
            )
            int limit,
            @RequestParam(
                    required = false
            )
            String cursor
    ) {
        ConversationMessagesQuery query =
                new ConversationMessagesQuery(
                        limit,
                        cursor
                );

        ConversationMessagesResponse response =
                conversationQueryService.listMessages(
                        conversationId,
                        query
                );

        return ResponseEntity.ok(response);
    }
}