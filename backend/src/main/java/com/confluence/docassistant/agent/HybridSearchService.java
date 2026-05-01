package com.confluence.docassistant.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Hybrid search combining:
 * 1. Vector search (semantic similarity via pgvector)
 * 2. Full-text search (keyword matching via PostgreSQL FTS)
 * 3. Reciprocal Rank Fusion (RRF) re-ranking
 *
 * This is what production RAG systems use.
 * Pure vector search misses exact names like "Combined Advices".
 * Pure keyword search misses semantic meaning.
 * Combined = best of both worlds.
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);
    private static final int RRF_K = 60; // standard RRF constant

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HybridSearchService(JdbcTemplate jdbc, EmbeddingModel embeddingModel) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
    }

    /**
     * Main entry point. Runs hybrid search for a single query.
     *
     * @param query the search query
     * @param topK  number of results to return
     * @return ranked list of chunks
     */
    public List<Chunk> search(String query, int topK) {
        log.debug("Hybrid search: '{}' topK={}", query, topK);

        List<Chunk> vectorResults  = vectorSearch(query, topK * 2);
        List<Chunk> keywordResults = keywordSearch(query, topK * 2);

        List<Chunk> reranked = rerankWithRRF(vectorResults, keywordResults);
        List<Chunk> top = reranked.stream().limit(topK).toList();

        log.debug("Hybrid search returned {} chunks (vector={}, keyword={})",
                top.size(), vectorResults.size(), keywordResults.size());

        return top;
    }

    /**
     * Multi-query search — runs hybrid search for multiple queries
     * and merges results. Used by the planner agent.
     *
     * @param queries list of search queries (from query expansion)
     * @param topK    total results to return
     * @return deduplicated, ranked list of chunks
     */
    public List<Chunk> multiQuerySearch(List<String> queries, int topK) {
        log.debug("Multi-query search: {} queries", queries.size());

        Map<String, Chunk> allChunks = new LinkedHashMap<>();
        Map<String, Double> combinedScores = new HashMap<>();

        for (int qi = 0; qi < queries.size(); qi++) {
            String query = queries.get(qi);
            List<Chunk> results = search(query, topK);

            // Apply position-based weighting — first query is most important
            double queryWeight = 1.0 / (qi + 1);

            for (int ri = 0; ri < results.size(); ri++) {
                Chunk chunk = results.get(ri);
                double score = queryWeight * (1.0 / (RRF_K + ri + 1));
                combinedScores.merge(chunk.id(), score, Double::sum);
                allChunks.putIfAbsent(chunk.id(), chunk);
            }
        }

        return combinedScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> allChunks.get(e.getKey()))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Fetches ALL chunks for a specific page title.
     * Used when the agent determines a specific app/page is being asked about.
     */
    public List<Chunk> getChunksByTitle(String title) {
        log.debug("Fetching all chunks for page: {}", title);

        // Use first two words for fuzzy match
        // "Combined Advices Interfaces" → search for "Combined Advices"
        String[] words = title.trim().split("\\s+");
        String fuzzyTerm = words.length >= 2
                ? words[0] + " " + words[1]
                : words[0];

        log.debug("Fuzzy title search term: '{}'", fuzzyTerm);

        return jdbc.query("""
                SELECT id::text, content, metadata
                FROM vector_store
                WHERE LOWER(metadata->>'title') LIKE LOWER(?)
                ORDER BY id
                """,
                chunkRowMapper(),
                "%" + fuzzyTerm + "%"
        );
    }

    // ── Vector Search ─────────────────────────────────────────────────────────

    private List<Chunk> vectorSearch(String query, int topK) {
        try {
            float[] embedding = embeddingModel.embed(query);
            String vectorStr = toVectorString(embedding);

            return jdbc.query("""
                    SELECT id::text, content, metadata,
                           1 - (embedding <=> '%s'::vector) as score
                    FROM vector_store
                    WHERE 1 - (embedding <=> '%s'::vector) > 0.2
                    ORDER BY embedding <=> '%s'::vector
                    LIMIT %d
                    """.formatted(vectorStr, vectorStr, vectorStr, topK),
                    scoredChunkRowMapper()
            );
        } catch (Exception e) {
            log.warn("Vector search failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Full Text Search ──────────────────────────────────────────────────────

    private List<Chunk> keywordSearch(String query, int topK) {
        try {
            // Use websearch_to_tsquery for natural language queries
            return jdbc.query("""
                    SELECT id::text, content, metadata,
                           ts_rank(to_tsvector('english', content),
                                   websearch_to_tsquery('english', ?)) as score
                    FROM vector_store
                    WHERE to_tsvector('english', content) @@
                          websearch_to_tsquery('english', ?)
                    ORDER BY score DESC
                    LIMIT ?
                    """,
                    scoredChunkRowMapper(),
                    query, query, topK
            );
        } catch (Exception e) {
            log.warn("Keyword search failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ── RRF Re-ranking ────────────────────────────────────────────────────────

    /**
     * Reciprocal Rank Fusion — standard algorithm for combining ranked lists.
     * Chunks appearing in BOTH vector and keyword results rank highest.
     * Score = 1/(k+rank_in_vector) + 1/(k+rank_in_keyword)
     */
    private List<Chunk> rerankWithRRF(List<Chunk> vectorResults, List<Chunk> keywordResults) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, Chunk>  chunks = new HashMap<>();

        for (int i = 0; i < vectorResults.size(); i++) {
            Chunk c = vectorResults.get(i);
            scores.merge(c.id(), 1.0 / (RRF_K + i + 1), Double::sum);
            chunks.putIfAbsent(c.id(), c);
        }

        for (int i = 0; i < keywordResults.size(); i++) {
            Chunk c = keywordResults.get(i);
            scores.merge(c.id(), 1.0 / (RRF_K + i + 1), Double::sum);
            chunks.putIfAbsent(c.id(), c);
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> chunks.get(e.getKey()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    // ── Row Mappers ───────────────────────────────────────────────────────────

    private RowMapper<Chunk> chunkRowMapper() {
        return (rs, row) -> new Chunk(
                rs.getString("id"),
                rs.getString("content"),
                extractMetadata(rs.getString("metadata"), "title"),
                extractMetadata(rs.getString("metadata"), "url"),
                extractMetadata(rs.getString("metadata"), "spaceKey"),
                1.0
        );
    }

    private RowMapper<Chunk> scoredChunkRowMapper() {
        return (rs, row) -> new Chunk(
                rs.getString("id"),
                rs.getString("content"),
                extractMetadata(rs.getString("metadata"), "title"),
                extractMetadata(rs.getString("metadata"), "url"),
                extractMetadata(rs.getString("metadata"), "spaceKey"),
                rs.getDouble("score")
        );
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        return sb.append("]").toString();
    }

    private String extractMetadata(String metadataJson, String key) {
        if (metadataJson == null || metadataJson.isBlank()) return "";
        try {
            JsonNode node = objectMapper.readTree(metadataJson);
            JsonNode value = node.get(key);
            return value != null && !value.isNull() ? value.asText() : "";
        } catch (Exception e) {
            return "";
        }
    }
}