package com.confluence.docassistant.service;

import com.confluence.docassistant.model.Feedback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * FeedbackService — stores and retrieves answer feedback.
 *
 * Creates feedback table on startup if it doesn't exist.
 * Every 👍/👎 is stored with question, answer, rating, confidence.
 *
 * Admin queries:
 * - Get all negative feedback → tells you what to fix
 * - Get feedback by page → tells you which pages have bad answers
 * - Get confidence distribution → tells you where docs are thin
 */
@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);
    private final JdbcTemplate jdbc;

    public FeedbackService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        createTableIfNotExists();
    }

    private void createTableIfNotExists() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS doc_feedback (
                    id              SERIAL PRIMARY KEY,
                    conversation_id TEXT,
                    question        TEXT NOT NULL,
                    answer          TEXT,
                    rating          TEXT NOT NULL,
                    comment         TEXT,
                    confidence      TEXT,
                    created_at      TIMESTAMP DEFAULT NOW()
                )
                """);
        log.info("Feedback table ready");
    }

    public void save(Feedback feedback) {
        jdbc.update("""
                INSERT INTO doc_feedback
                    (conversation_id, question, answer, rating, comment, confidence, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                feedback.conversationId(),
                feedback.question(),
                feedback.answer(),
                feedback.rating(),
                feedback.comment(),
                feedback.confidence(),
                LocalDateTime.now()
        );
        log.info("Feedback saved: {} — {}", feedback.rating(), feedback.question());
    }

    /**
     * All negative feedback — questions that got thumbs down.
     * Most recent first. Used to identify what needs fixing.
     */
    public List<Map<String, Object>> getNegativeFeedback() {
        return jdbc.queryForList("""
                SELECT question, answer, comment, confidence, created_at
                FROM doc_feedback
                WHERE rating = 'negative'
                ORDER BY created_at DESC
                LIMIT 100
                """);
    }

    /**
     * Feedback summary — total counts, thumbs up/down rate.
     */
    public Map<String, Object> getSummary() {
        return jdbc.queryForMap("""
                SELECT
                    COUNT(*) as total,
                    SUM(CASE WHEN rating = 'positive' THEN 1 ELSE 0 END) as positive,
                    SUM(CASE WHEN rating = 'negative' THEN 1 ELSE 0 END) as negative,
                    ROUND(100.0 * SUM(CASE WHEN rating = 'positive' THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 1) as positive_rate
                FROM doc_feedback
                """);
    }

    /**
     * Most asked questions — shows what users care about most.
     */
    public List<Map<String, Object>> getTopQuestions() {
        return jdbc.queryForList("""
                SELECT question, COUNT(*) as count,
                       SUM(CASE WHEN rating = 'positive' THEN 1 ELSE 0 END) as positive,
                       SUM(CASE WHEN rating = 'negative' THEN 1 ELSE 0 END) as negative
                FROM doc_feedback
                GROUP BY question
                ORDER BY count DESC
                LIMIT 20
                """);
    }

    /**
     * Questions with only negative feedback — content gaps to fix.
     */
    public List<Map<String, Object>> getFailingQuestions() {
        return jdbc.queryForList("""
                SELECT question, COUNT(*) as attempts, MAX(created_at) as last_asked
                FROM doc_feedback
                WHERE rating = 'negative'
                  AND question NOT IN (
                      SELECT question FROM doc_feedback WHERE rating = 'positive'
                  )
                GROUP BY question
                ORDER BY attempts DESC
                LIMIT 20
                """);
    }
}