package com.confluence.docassistant.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ReflectionAgent — the quality gate in agentic RAG.
 *
 * After initial retrieval, the reflection agent asks:
 * "Do I have enough context to answer this question well?"
 *
 * If not — it generates NEW search queries and tries again.
 * This is the key difference between basic RAG and agentic RAG.
 *
 * A standard RAG just answers from whatever it found.
 * An agentic RAG KNOWS when it doesn't have enough and tries harder.
 */
@Component
public class ReflectionAgent {

    private static final Logger log = LoggerFactory.getLogger(ReflectionAgent.class);
    private static final int MAX_REFLECTION_ROUNDS = 2;

    private final ChatClient chatClient;
    private final HybridSearchService searchService;

    public ReflectionAgent(ChatClient.Builder builder, HybridSearchService searchService) {
        this.chatClient    = builder.build();
        this.searchService = searchService;
    }

    /**
     * Evaluates retrieved chunks and potentially searches for more.
     *
     * @param question      original user question
     * @param initialChunks chunks retrieved by the planner
     * @return enriched list of chunks (may include additional searches)
     */
    public List<Chunk> reflect(String question, List<Chunk> initialChunks) {
        List<Chunk> currentChunks = new java.util.ArrayList<>(initialChunks);

        for (int round = 0; round < MAX_REFLECTION_ROUNDS; round++) {
            ReflectionResult result = evaluate(question, currentChunks);

            log.debug("Reflection round {}: sufficient={}, reason={}",
                    round + 1, result.sufficient(), result.reason());

            if (result.sufficient()) {
                log.debug("Reflection satisfied after {} round(s)", round + 1);
                break;
            }

            if (result.additionalQueries().isEmpty()) {
                log.debug("No additional queries suggested, stopping reflection");
                break;
            }

            // Search with additional queries
            log.debug("Searching with {} additional queries", result.additionalQueries().size());
            List<Chunk> additionalChunks = searchService.multiQuerySearch(
                    result.additionalQueries(), 5
            );

            // Merge — avoid duplicates
            for (Chunk chunk : additionalChunks) {
                boolean alreadyPresent = currentChunks.stream()
                        .anyMatch(c -> c.id().equals(chunk.id()));
                if (!alreadyPresent) {
                    currentChunks.add(chunk);
                }
            }
        }

        return currentChunks;
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private ReflectionResult evaluate(String question, List<Chunk> chunks) {
        if (chunks.isEmpty()) {
            return new ReflectionResult(
                    false,
                    "No context found at all",
                    List.of(question + " overview",
                            question + " details",
                            question + " documentation")
            );
        }

        String contextSummary = chunks.stream()
                .limit(5)
                .map(c -> "Page: " + c.title() + "\nContent: " +
                        c.content().substring(0, Math.min(200, c.content().length())) + "...")
                .reduce("", (a, b) -> a + "\n---\n" + b);

        String response = chatClient.prompt()
                .system("""
                        You are a retrieval quality evaluator.
                        Respond with JSON only. No explanation.
                        """)
                .user("""
                        Question: "%s"
                        
                        Retrieved context summary:
                        %s
                        
                        Evaluate if this context is sufficient to answer the question fully.
                        
                        Respond with this exact JSON:
                        {
                          "sufficient": true/false,
                          "reason": "brief reason",
                          "additionalQueries": ["query1", "query2"]
                        }
                        
                        Set sufficient=true if context clearly contains the answer.
                        Set sufficient=false if important information seems missing.
                        If sufficient=false, provide 2 additional search queries to find missing info.
                        If sufficient=true, additionalQueries should be empty array.
                        """.formatted(question, contextSummary))
                .call()
                .content();

        return parseReflection(response);
    }

    private ReflectionResult parseReflection(String response) {
        try {
            String json = response.trim();
            // Simple JSON parsing
            boolean sufficient = json.contains("\"sufficient\":true") ||
                                 json.contains("\"sufficient\": true");

            String reason = extractJsonString(json, "reason");

            List<String> queries = extractJsonStringArray(json, "additionalQueries");

            return new ReflectionResult(sufficient, reason, queries);
        } catch (Exception e) {
            log.warn("Failed to parse reflection response: {}", e.getMessage());
            return new ReflectionResult(true, "Parse error - proceeding", List.of());
        }
    }

    private String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start == -1) return "";
        start += search.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : "";
    }

    private List<String> extractJsonStringArray(String json, String key) {
        String search = "\"" + key + "\":[";
        int start = json.indexOf(search);
        if (start == -1) return List.of();
        start += search.length();
        int end = json.indexOf("]", start);
        if (end == -1) return List.of();

        String arrayContent = json.substring(start, end);
        return java.util.Arrays.stream(arrayContent.split(","))
                .map(s -> s.trim().replace("\"", ""))
                .filter(s -> !s.isBlank())
                .toList();
    }

    // ── Result record ─────────────────────────────────────────────────────────

    public record ReflectionResult(
            boolean sufficient,
            String reason,
            List<String> additionalQueries
    ) {}
}
