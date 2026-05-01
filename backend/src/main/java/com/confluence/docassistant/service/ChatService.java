package com.confluence.docassistant.service;

import com.confluence.docassistant.agent.AgenticRAGOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private final AgenticRAGOrchestrator orchestrator;
    private final ChatHistoryService     historyService;

    public ChatService(AgenticRAGOrchestrator orchestrator,
                       ChatHistoryService historyService) {
        this.orchestrator    = orchestrator;
        this.historyService  = historyService;
    }

    public AgenticRAGOrchestrator.RAGResult chatWithFollowUps(
            String conversationId, String message) {

        // Save user message
        historyService.saveMessage(conversationId, "user", message, null, null);

        // Get answer
        var result = orchestrator.answerWithFollowUps(message, conversationId);

        // Save assistant answer with confidence and gaps
        historyService.saveMessage(
                conversationId,
                "assistant",
                result.answer(),
                result.confidence(),
                result.gaps()
        );

        return result;
    }
}