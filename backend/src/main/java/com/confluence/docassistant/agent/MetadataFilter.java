package com.confluence.docassistant.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MetadataFilter — targeted retrieval using metadata.
 *
 * Instead of always doing full semantic search,
 * sometimes we know EXACTLY what we want:
 *
 * - "Show me all apps" → filter by title LIKE 'Application Inventory'
 * - "What changed recently?" → filter by lastModified > 7 days ago
 * - "Show me the source systems section" → filter by section = 'Source Applications'
 * - "Only look at Combined Advices" → filter by title LIKE 'Combined Advices'
 *
 * MetadataFilter bypasses vector search entirely for these cases.
 * Result: faster, more precise, no similarity threshold issues.
 */
@Component
public class MetadataFilter {

    private static final Logger log = LoggerFactory.getLogger(MetadataFilter.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MetadataFilter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Retrieves chunks matching metadata criteria.
     */
    public List<Chunk> filter(FilterCriteria criteria) {
        log.debug("Metadata filter: {}", criteria);

        StringBuilder sql = new StringBuilder("""
                SELECT id::text, content, metadata, 1.0 as score
                FROM vector_store
                WHERE 1=1
                """);

        List<Object> params = new ArrayList<>();

        if (criteria.layer() != null) {
            sql.append(" AND metadata->>'layer' = ?");
            params.add(criteria.layer());
        }

        if (criteria.titleContains() != null) {
            sql.append(" AND LOWER(metadata->>'title') LIKE LOWER(?)");
            params.add("%" + criteria.titleContains() + "%");
        }

        if (criteria.section() != null) {
            sql.append(" AND LOWER(metadata->>'section') LIKE LOWER(?)");
            params.add("%" + criteria.section() + "%");
        }

        if (criteria.spaceKey() != null) {
            sql.append(" AND metadata->>'spaceKey' = ?");
            params.add(criteria.spaceKey());
        }

        if (criteria.limit() > 0) {
            sql.append(" LIMIT ?");
            params.add(criteria.limit());
        }

        return jdbc.query(sql.toString(),
                (rs, row) -> new Chunk(
                        rs.getString("id"),
                        rs.getString("content"),
                        extractMeta(rs.getString("metadata"), "title"),
                        extractMeta(rs.getString("metadata"), "url"),
                        extractMeta(rs.getString("metadata"), "spaceKey"),
                        1.0
                ),
                params.toArray()
        );
    }

    /**
     * Gets all L1 summaries — used for page-level questions
     * like "what documentation do we have about X?"
     */
    public List<Chunk> getAllPageSummaries() {
        return filter(FilterCriteria.builder()
                .layer("L1")
                .limit(50)
                .build());
    }

    /**
     * Gets all chunks for a specific page by title.
     */
    public List<Chunk> getPageChunks(String titleContains, String layer) {
        return filter(FilterCriteria.builder()
                .titleContains(titleContains)
                .layer(layer)
                .limit(20)
                .build());
    }

    /**
     * Gets TABLE type chunks for a page — these contain complete tables, never split.
     * Used to ensure full table data (all rows) reaches the LLM.
     */
    public List<Chunk> getTableChunks(String titleContains) {
        try {
            return jdbc.query("""
                    SELECT id::text, content, metadata, 1.0 as score
                    FROM vector_store
                    WHERE LOWER(metadata->>'title') LIKE LOWER(?)
                    AND metadata->>'type' = 'TABLE'
                    ORDER BY id
                    LIMIT 10
                    """,
                    (rs, row) -> new Chunk(
                            rs.getString("id"),
                            rs.getString("content"),
                            extractMeta(rs.getString("metadata"), "title"),
                            extractMeta(rs.getString("metadata"), "url"),
                            extractMeta(rs.getString("metadata"), "spaceKey"),
                            1.0
                    ),
                    "%" + titleContains + "%"
            );
        } catch (Exception e) {
            log.warn("getTableChunks failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ── FilterCriteria ────────────────────────────────────────────────────────

    public record FilterCriteria(
            String layer,
            String titleContains,
            String section,
            String spaceKey,
            int limit
    ) {
        public static Builder builder() { return new Builder(); }

        public static class Builder {
            private String layer;
            private String titleContains;
            private String section;
            private String spaceKey;
            private int limit = 20;

            public Builder layer(String v)         { this.layer = v; return this; }
            public Builder titleContains(String v) { this.titleContains = v; return this; }
            public Builder section(String v)       { this.section = v; return this; }
            public Builder spaceKey(String v)      { this.spaceKey = v; return this; }
            public Builder limit(int v)            { this.limit = v; return this; }

            public FilterCriteria build() {
                return new FilterCriteria(layer, titleContains, section, spaceKey, limit);
            }
        }
    }

    private String extractMeta(String json, String key) {
        if (json == null || json.isBlank()) return "";
        try {
            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            com.fasterxml.jackson.databind.JsonNode value = node.get(key);
            return value != null && !value.isNull() ? value.asText() : "";
        } catch (Exception e) {
            return "";
        }
    }
}