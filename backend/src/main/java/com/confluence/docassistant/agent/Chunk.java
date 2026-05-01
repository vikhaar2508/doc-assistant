package com.confluence.docassistant.agent;

/**
 * Represents a retrieved document chunk with its relevance score.
 * Used throughout the agentic retrieval pipeline.
 */
public record Chunk(
        String id,
        String content,
        String title,
        String url,
        String spaceKey,
        double score
) {
    /**
     * Returns a formatted string for LLM context injection.
     * Includes source metadata so the LLM can cite correctly.
     */
    public String toContextString() {
        return String.format("""
                [SOURCE: %s]
                %s
                """, title, content);
    }
}
