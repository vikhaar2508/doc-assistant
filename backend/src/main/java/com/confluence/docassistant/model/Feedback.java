package com.confluence.docassistant.model;

import java.time.LocalDateTime;

public record Feedback(
        String conversationId,
        String question,
        String answer,
        String rating,        // "positive" or "negative"
        String comment,       // optional user comment
        String confidence,    // HIGH / MEDIUM / LOW
        LocalDateTime createdAt
) {}