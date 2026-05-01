package com.confluence.docassistant.controller;

import com.confluence.docassistant.service.ChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> chat(@RequestBody ChatRequest request) {
        String conversationId = (request.conversationId() != null && !request.conversationId().isBlank())
                ? request.conversationId()
                : UUID.randomUUID().toString();

        var result = chatService.chatWithFollowUps(conversationId, request.message());

        return ResponseEntity.ok(Map.of(
                "response",          result.answer(),
                "followUps",         result.followUps(),
                "confidence",        result.confidence(),
                "confidenceReason",  result.confidenceReason(),
                "gaps",              result.gaps(),
                "conversationId",    conversationId
        ));
    }

    public record ChatRequest(String message, String conversationId) {}
}