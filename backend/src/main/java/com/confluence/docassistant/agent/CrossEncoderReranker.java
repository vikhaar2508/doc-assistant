package com.confluence.docassistant.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * CrossEncoderReranker — the quality filter before synthesis.
 *
 * Vector search retrieves by similarity.
 * But similarity ≠ relevance.
 *
 * Example:
 *   Question: "What feed type does MPF use?"
 *   Vector search might return chunks about:
 *     - MPF general info (similarity 0.8) ← relevant
 *     - Feed types in general (similarity 0.75) ← less relevant
 *     - File drop documentation (similarity 0.72) ← less relevant
 *     - MPF feed type: File drop CSV (similarity 0.71) ← MOST relevant but ranked 4th!
 *
 * CrossEncoder reads each chunk in context of the question
 * and re-scores by ACTUAL relevance — not just word similarity.
 *
 * After reranking:
 *     - MPF feed type: File drop CSV → score 0.95 → rank 1 ✅
 *
 * This is what Cohere Rerank, BGE Reranker, and ms-marco do in production.
 * We implement it using the local LLM as the cross-encoder.
 */
@Component
public class CrossEncoderReranker {

    private static final Logger log = LoggerFactory.getLogger(CrossEncoderReranker.class);
    private static final int BATCH_SIZE = 5; // score 5 chunks at a time

    private final ChatClient chatClient;

    public CrossEncoderReranker(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * Re-ranks chunks by relevance to the question.
     *
     * @param question the user's question
     * @param chunks   chunks to re-rank (from hybrid search)
     * @param topK     how many to return after re-ranking
     * @return re-ranked chunks, most relevant first
     */
    public List<Chunk> rerank(String question, List<Chunk> chunks, int topK) {
        if (chunks.isEmpty()) return chunks;

        log.debug("Reranking {} chunks for: {}", chunks.size(), question);

        // Score all chunks
        List<ScoredChunk> scored = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i += BATCH_SIZE) {
            List<Chunk> batch = chunks.subList(i, Math.min(i + BATCH_SIZE, chunks.size()));
            List<ScoredChunk> batchScores = scoreBatch(question, batch);
            scored.addAll(batchScores);
        }

        // Sort by relevance score descending
        List<Chunk> reranked = scored.stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(topK)
                .map(ScoredChunk::chunk)
                .collect(Collectors.toList());

        log.debug("Reranking complete — top chunk: '{}' score={}",
                reranked.isEmpty() ? "none" : reranked.get(0).title(),
                scored.stream().mapToDouble(ScoredChunk::score).max().orElse(0));

        return reranked;
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private List<ScoredChunk> scoreBatch(String question, List<Chunk> batch) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                Score each text chunk for relevance to the question.
                Score from 0.0 (not relevant) to 1.0 (perfectly answers the question).
                Respond with ONLY scores, one per line, in order.
                Example response for 3 chunks:
                0.9
                0.3
                0.7
                
                Question: %s
                
                """.formatted(question));

        for (int i = 0; i < batch.size(); i++) {
            prompt.append("Chunk %d:\n%s\n\n".formatted(
                    i + 1,
                    batch.get(i).content().substring(0, Math.min(300, batch.get(i).content().length()))
            ));
        }

        try {
            String response = chatClient.prompt()
                    .system("You are a relevance scorer. Respond with decimal scores only, one per line.")
                    .user(prompt.toString())
                    .call()
                    .content();

            List<Double> scores = parseScores(response, batch.size());

            List<ScoredChunk> result = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                result.add(new ScoredChunk(batch.get(i), scores.get(i)));
            }
            return result;

        } catch (Exception e) {
            log.warn("Reranking batch failed: {} — using original order", e.getMessage());
            return batch.stream()
                    .map(c -> new ScoredChunk(c, c.score()))
                    .collect(Collectors.toList());
        }
    }

    private List<Double> parseScores(String response, int expectedCount) {
        List<Double> scores = new ArrayList<>();

        String[] lines = response.trim().split("\n");
        for (String line : lines) {
            try {
                String cleaned = line.trim()
                        .replaceAll("[^0-9.]", "")
                        .trim();
                if (!cleaned.isBlank()) {
                    double score = Double.parseDouble(cleaned);
                    scores.add(Math.max(0.0, Math.min(1.0, score)));
                }
            } catch (NumberFormatException ignored) {}
        }

        // Pad with 0.5 if we got fewer scores than expected
        while (scores.size() < expectedCount) {
            scores.add(0.5);
        }

        return scores.subList(0, expectedCount);
    }

    private record ScoredChunk(Chunk chunk, double score) {}
}
