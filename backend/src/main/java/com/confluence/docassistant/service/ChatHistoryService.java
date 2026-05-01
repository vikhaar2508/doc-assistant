package com.confluence.docassistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * ChatHistoryService — persists conversations across sessions.
 *
 * Schema:
 * conversations — one row per conversation (id, title, timestamps)
 * messages      — all messages in all conversations
 *
 * Tables created automatically on startup.
 */
@Service
public class ChatHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryService.class);
    private final JdbcTemplate jdbc;

    public ChatHistoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        createTablesIfNotExist();
    }

    private void createTablesIfNotExist() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS conversations (
                    id         TEXT PRIMARY KEY,
                    title      TEXT,
                    created_at TIMESTAMP DEFAULT NOW(),
                    updated_at TIMESTAMP DEFAULT NOW()
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    id              SERIAL PRIMARY KEY,
                    conversation_id TEXT REFERENCES conversations(id) ON DELETE CASCADE,
                    role            TEXT NOT NULL,
                    content         TEXT NOT NULL,
                    confidence      TEXT,
                    gaps            TEXT,
                    created_at      TIMESTAMP DEFAULT NOW()
                )
                """);
        log.info("Chat history tables ready");
    }

    /**
     * Creates or updates a conversation.
     * Title is set from the first user message.
     */
    public void saveMessage(String conversationId, String role,
                            String content, String confidence, List<String> gaps) {
        // Upsert conversation
        String title = role.equals("user")
                ? truncate(content, 60)
                : null;

        jdbc.update("""
                INSERT INTO conversations (id, title, updated_at)
                VALUES (?, ?, NOW())
                ON CONFLICT (id) DO UPDATE
                SET updated_at = NOW(),
                    title = COALESCE(conversations.title, EXCLUDED.title)
                """, conversationId, title);

        // Save message
        String gapsJson = gaps != null && !gaps.isEmpty()
                ? String.join("||", gaps)
                : null;

        jdbc.update("""
                INSERT INTO messages (conversation_id, role, content, confidence, gaps)
                VALUES (?, ?, ?, ?, ?)
                """, conversationId, role, content, confidence, gapsJson);
    }

    /**
     * Gets all conversations ordered by most recent.
     */
    public List<Map<String, Object>> getConversations() {
        return jdbc.queryForList("""
                SELECT c.id, c.title, c.created_at, c.updated_at,
                       COUNT(m.id) as message_count
                FROM conversations c
                LEFT JOIN messages m ON m.conversation_id = c.id
                GROUP BY c.id, c.title, c.created_at, c.updated_at
                ORDER BY c.updated_at DESC
                LIMIT 50
                """);
    }

    /**
     * Gets all messages for a conversation.
     */
    public List<Map<String, Object>> getMessages(String conversationId) {
        return jdbc.queryForList("""
                SELECT role, content, confidence, gaps, created_at
                FROM messages
                WHERE conversation_id = ?
                ORDER BY created_at ASC
                """, conversationId);
    }

    /**
     * Deletes a conversation and all its messages.
     */
    public void deleteConversation(String conversationId) {
        jdbc.update("DELETE FROM conversations WHERE id = ?", conversationId);
        log.info("Deleted conversation: {}", conversationId);
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "New conversation";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}