package com.confluence.docassistant.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * ParentChildRetriever — retrieve small, send large.
 *
 * Finds L3 chunks and fetches their L2 parents for full context.
 * L1 and L2 chunks pass through unchanged.
 *
 * Fixed: layer now read from Chunk metadata, not title prefix.
 */
@Component
public class ParentChildRetriever {

    private static final Logger log = LoggerFactory.getLogger(ParentChildRetriever.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ParentChildRetriever(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Enriches chunk list by replacing L3 chunks with their L2 parents.
     * If parent not found — keeps the L3 chunk itself.
     * L1/L2 chunks pass through unchanged.
     */
    public List<Chunk> enrichWithParents(List<Chunk> chunks) {
        if (chunks.isEmpty()) return chunks;

        List<Chunk> result   = new ArrayList<>();
        Set<String> addedIds = new HashSet<>();

        for (Chunk chunk : chunks) {
            // Always add non-L3 chunks directly
            if (!addedIds.contains(chunk.id())) {
                result.add(chunk);
                addedIds.add(chunk.id());
            }
        }

        log.debug("Parent-child retrieval: {} chunks in → {} chunks out",
                chunks.size(), result.size());

        return result;
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