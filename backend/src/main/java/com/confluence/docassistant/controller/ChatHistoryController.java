package com.confluence.docassistant.controller;

import com.confluence.docassistant.service.ChatHistoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/history")
public class ChatHistoryController {

    private final ChatHistoryService historyService;

    public ChatHistoryController(ChatHistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getConversations() {
        return ResponseEntity.ok(historyService.getConversations());
    }

    @GetMapping("/{conversationId}")
    public ResponseEntity<List<Map<String, Object>>> getMessages(
            @PathVariable String conversationId) {
        return ResponseEntity.ok(historyService.getMessages(conversationId));
    }

    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Map<String, String>> deleteConversation(
            @PathVariable String conversationId) {
        historyService.deleteConversation(conversationId);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }
}