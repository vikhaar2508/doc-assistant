package com.confluence.docassistant.agent;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * ConversationContext — per-conversation state management.
 *
 * Stores context for each conversation independently.
 * Thread-safe — multiple users can chat simultaneously without interference.
 *
 * Tracks:
 * - Last page discussed (for implicit follow-up detection)
 * - Last topic/intent (for context-aware planning)
 */
@Component
public class ConversationContext {

    private final Map<String, ConversationState> states = new ConcurrentHashMap<>();

    /**
     * Gets the last page discussed in this conversation.
     */
    public String getLastPage(String conversationId) {
        ConversationState state = states.get(conversationId);
        return state != null ? state.lastPage() : "";
    }

    /**
     * Gets the last intent discussed in this conversation.
     */
    public String getLastIntent(String conversationId) {
        ConversationState state = states.get(conversationId);
        return state != null ? state.lastIntent() : "";
    }

    /**
     * Updates context after a successful retrieval.
     */
    public void update(String conversationId, String page, String intent) {
        if (conversationId == null || conversationId.isBlank()) return;
        states.put(conversationId, new ConversationState(
                page != null ? page : "",
                intent != null ? intent : ""
        ));
    }

    /**
     * Clears context for a conversation — called on new conversation.
     */
    public void clear(String conversationId) {
        states.remove(conversationId);
    }

    /**
     * Builds a context hint string for the PlannerAgent.
     * Empty string if no context available.
     */
    public String buildContextHint(String conversationId) {
        String lastPage   = getLastPage(conversationId);
        String lastIntent = getLastIntent(conversationId);

        if (lastPage.isBlank()) return "";

        return String.format(
                "\nConversation context: user was previously discussing '%s' (%s). " +
                        "If this question has no specific subject mentioned, " +
                        "determine if it is a follow-up about '%s' or a new topic.",
                lastPage, lastIntent, lastPage
        );
    }

    private record ConversationState(String lastPage, String lastIntent) {}
}