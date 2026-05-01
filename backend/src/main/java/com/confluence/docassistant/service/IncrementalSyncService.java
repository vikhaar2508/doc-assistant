package com.confluence.docassistant.service;

import com.confluence.docassistant.ingestion.ConfluenceDocumentBuilder;
import com.confluence.docassistant.ingestion.ConfluencePageFetcher;
import com.confluence.docassistant.ingestion.HierarchicalChunker;
import com.confluence.docassistant.model.ConfluencePage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * IncrementalSyncService — only re-ingests pages that changed.
 *
 * 500 pages, 1 changed → only 1 page re-ingested → seconds not hours.
 * Runs automatically at 2AM every night via @Scheduled.
 */
@Service
public class IncrementalSyncService {

    private static final Logger log = LoggerFactory.getLogger(IncrementalSyncService.class);

    private final ConfluencePageFetcher     pageFetcher;
    private final ConfluenceDocumentBuilder documentBuilder;
    private final HierarchicalChunker       chunker;
    private final VectorStore               vectorStore;
    private final JdbcTemplate              jdbc;

    public IncrementalSyncService(ConfluencePageFetcher pageFetcher,
                                   ConfluenceDocumentBuilder documentBuilder,
                                   HierarchicalChunker chunker,
                                   VectorStore vectorStore,
                                   JdbcTemplate jdbc) {
        this.pageFetcher     = pageFetcher;
        this.documentBuilder = documentBuilder;
        this.chunker         = chunker;
        this.vectorStore     = vectorStore;
        this.jdbc            = jdbc;
    }

    @Scheduled(cron = "0 0 2 * * *") // 2AM every night
    public SyncResult syncIncremental() {
        log.info("=== Starting incremental sync ===");

        List<ConfluencePage> confluencePages = pageFetcher.fetchAllPagesInSpace();
        Map<String, Integer> storedVersions  = getStoredVersions();

        log.info("Confluence: {} pages | PgVector: {} pages stored",
                confluencePages.size(), storedVersions.size());

        int added = 0, updated = 0, deleted = 0, skipped = 0;
        Set<String> confluenceIds = new HashSet<>();

        for (ConfluencePage page : confluencePages) {
            confluenceIds.add(page.getId());
            int confluenceVersion = page.getVersion() != null ? page.getVersion().getNumber() : 1;
            int storedVersion     = storedVersions.getOrDefault(page.getId(), -1);

            if (storedVersion == -1) {
                log.info("New page: '{}' — ingesting", page.getTitle());
                if (ingestPage(page)) added++;
            } else if (confluenceVersion > storedVersion) {
                log.info("Updated: '{}' v{} → v{}", page.getTitle(), storedVersion, confluenceVersion);
                deletePageChunks(page.getId());
                if (ingestPage(page)) updated++;
            } else {
                skipped++;
            }
        }

        // Detect deleted pages
        for (String storedId : storedVersions.keySet()) {
            if (!confluenceIds.contains(storedId)) {
                log.info("Page deleted from Confluence — removing: {}", storedId);
                deletePageChunks(storedId);
                deleted++;
            }
        }

        log.info("=== Incremental sync: +{} new, ~{} updated, -{} deleted, {} unchanged ===",
                added, updated, deleted, skipped);

        return new SyncResult(added, updated, deleted, skipped);
    }

    private Map<String, Integer> getStoredVersions() {
        try {
            return jdbc.query("""
                    SELECT DISTINCT
                        metadata->>'pageId' as page_id,
                        (metadata->>'version')::int as version
                    FROM vector_store
                    WHERE metadata->>'pageId' IS NOT NULL
                    AND metadata->>'version' IS NOT NULL
                    """,
                    (rs, row) -> Map.entry(rs.getString("page_id"), rs.getInt("version"))
            ).stream().collect(Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue,
                    Math::max
            ));
        } catch (Exception e) {
            log.warn("Failed to get stored versions: {}", e.getMessage());
            return Map.of();
        }
    }

    private void deletePageChunks(String pageId) {
        try {
            int count = jdbc.update("DELETE FROM vector_store WHERE metadata->>'pageId' = ?", pageId);
            log.debug("Deleted {} chunks for pageId: {}", count, pageId);
        } catch (Exception e) {
            log.error("Failed to delete chunks for {}: {}", pageId, e.getMessage());
        }
    }

    private boolean ingestPage(ConfluencePage page) {
        try {
            return documentBuilder.build(page).map(doc -> {
                List<Document> chunks = chunker.chunk(doc);
                vectorStore.add(chunks);
                log.debug("Ingested '{}' → {} chunks", page.getTitle(), chunks.size());
                return true;
            }).orElse(false);
        } catch (Exception e) {
            log.error("Failed to ingest '{}': {}", page.getTitle(), e.getMessage());
            return false;
        }
    }

    public record SyncResult(int added, int updated, int deleted, int skipped) {
        public String summary() {
            return String.format("+%d new, ~%d updated, -%d deleted, %d unchanged",
                    added, updated, deleted, skipped);
        }
    }
}
