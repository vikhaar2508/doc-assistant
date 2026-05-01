package com.confluence.docassistant.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * SemanticEnricher — runs at ingestion time, not query time.
 *
 * For every Confluence page, uses the LLM to automatically generate:
 *
 * 1. SYNONYMS      — alternative terms used in this page's domain
 *                    "source systems" → also means "interfaces", "integrations",
 *                    "upstream applications", "data feeds"
 *
 * 2. HYPOTHETICAL  — questions this page could answer (HyDE technique)
 * QUESTIONS          When a user asks a question, it matches these pre-generated
 *                    questions semantically — much better than matching raw content
 *
 * 3. PLAIN SUMMARY — 2-3 sentence plain English summary
 *                    Helps the LLM understand what the page is about at a glance
 *
 * All three are appended to the document content before embedding.
 * This means the VECTOR itself encodes multiple ways of asking
 * the same question — so "interfaces" matches "source systems" every time.
 *
 * This is the industry standard technique called HyDE
 * (Hypothetical Document Embeddings) combined with synonym expansion.
 */
@Component
public class SemanticEnricher {

    private static final Logger log = LoggerFactory.getLogger(SemanticEnricher.class);

    private final ChatClient chatClient;

    public SemanticEnricher(ChatClient.Builder builder) {
        // Separate ChatClient with no advisors — pure generation only
        this.chatClient = builder.build();
    }

    /**
     * Enriches page content with synonyms, hypothetical questions, and summary.
     *
     * The enriched text is APPENDED to the original content before embedding.
     * This means:
     * - Original content is preserved for accurate answer generation
     * - Enriched content improves retrieval accuracy
     *
     * @param pageTitle   title of the Confluence page
     * @param pageContent parsed plain text content of the page
     * @return original content + enriched semantic layer
     */
    public String enrich(String pageTitle, String pageContent) {
        log.debug("Enriching page: {}", pageTitle);

        try {
            String enrichment = chatClient.prompt()
                    .system("""
                            You are a semantic enrichment engine for a technical documentation RAG system.
                            Your job is to make documents findable using ANY phrasing a user might use.
                            Be thorough with synonyms — enterprise users use many different terms for the same concept.
                            Respond with plain text only. No JSON. No markdown. No explanation.
                            """)
                    .user("""
                            Analyze this technical documentation page and generate semantic enrichment.
                            
                            Page Title: %s
                            
                            Page Content:
                            %s
                            
                            Generate the following THREE sections exactly as shown:
                            
                            SYNONYMS:
                            List all alternative terms, synonyms, and related vocabulary used in this page.
                            Think about how different people might refer to the same concepts.
                            Example: "source systems" could also be called interfaces, integrations, 
                            upstream applications, data feeds, connected systems, external systems, dependencies
                            List one group per line: term = synonym1, synonym2, synonym3
                            
                            QUESTIONS:
                            List 10 different questions a user might ask that this page answers.
                            Use varied phrasing — some formal, some casual, some technical.
                            These questions should cover ALL the information on this page.
                            One question per line.
                            
                            SUMMARY:
                            Write a 3 sentence plain English summary of what this page contains.
                            Focus on what questions it answers and what information it provides.
                            """.formatted(pageTitle, truncate(pageContent, 2000)))
                    .call()
                    .content();

            String enrichedContent = pageContent + "\n\n" +
                    "--- SEMANTIC ENRICHMENT ---\n" +
                    enrichment;

            log.debug("Enriched page '{}' — added {} chars", pageTitle,
                    enrichedContent.length() - pageContent.length());

            return enrichedContent;

        } catch (Exception e) {
            // Never fail ingestion due to enrichment failure
            log.warn("Enrichment failed for '{}': {} — using original content",
                    pageTitle, e.getMessage());
            return pageContent;
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /**
     * Truncates content to avoid exceeding LLM context window during enrichment.
     * Full content is still stored — only truncated for the enrichment prompt.
     */
    private String truncate(String content, int maxChars) {
        if (content.length() <= maxChars) return content;
        return content.substring(0, maxChars) + "\n... [truncated for enrichment]";
    }
}
