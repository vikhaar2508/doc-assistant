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
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Orchestrates the full Confluence ingestion pipeline.
 *
 * Pipeline:
 * 1. Fetch pages       (ConfluencePageFetcher)
 * 2. Parse HTML        (HtmlContentParser via ConfluenceDocumentBuilder)
 * 3. Semantic enrich   (SemanticEnricher via ConfluenceDocumentBuilder)
 * 4. Hierarchical chunk (HierarchicalChunker — L1, L2, L3 layers)
 * 5. Store in PgVector (VectorStore)
 */
@Service
public class ConfluenceIngestionService {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceIngestionService.class);

    // Confluence boilerplate pages — skip during ingestion
    private static final Set<String> SKIP_TITLES = Set.of(
            "Getting started in Confluence",
            "Overview",
            "Welcome to Confluence",
            "Confluence 101"
    );

    private final ConfluencePageFetcher     pageFetcher;
    private final ConfluenceDocumentBuilder documentBuilder;
    private final HierarchicalChunker       chunker;
    private final VectorStore               vectorStore;
    private final JdbcTemplate              jdbc;

    public ConfluenceIngestionService(ConfluencePageFetcher pageFetcher,
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

    public IngestionResult ingestSpace() {
        log.info("=== Starting full Confluence space ingestion ===");

        // Wipe existing data — full sync starts fresh
        int deleted = jdbc.update("DELETE FROM vector_store");
        log.info("Cleared {} existing chunks before full sync", deleted);

        // Step 1: Fetch
        List<ConfluencePage> allFetched = pageFetcher.fetchAllPagesInSpace();
        List<ConfluencePage> pages = allFetched.stream()
                .filter(p -> !SKIP_TITLES.contains(p.getTitle()))
                .toList();
        log.info("Fetched {} pages ({} skipped as boilerplate)",
                pages.size(), allFetched.size() - pages.size());

        // Steps 2-4: Parse → Enrich → Chunk
        List<Document> allChunks = new ArrayList<>();
        int skipped = 0;

        for (ConfluencePage page : pages) {
            Optional<Document> doc = documentBuilder.build(page);
            if (doc.isEmpty()) {
                skipped++;
                continue;
            }
            // Hierarchical chunking — L1 + L2 + L3
            List<Document> chunks = chunker.chunk(doc.get());
            allChunks.addAll(chunks);
            log.info("Page '{}' → {} chunks (L1+L2+L3)", page.getTitle(), chunks.size());
        }

        log.info("Built {} total chunks from {} pages ({} skipped)",
                allChunks.size(), pages.size() - skipped, skipped);

        // Step 5: Store
        if (!allChunks.isEmpty()) {
            vectorStore.add(allChunks);
            log.info("=== Ingestion complete: {} chunks stored ===", allChunks.size());
        }

        return new IngestionResult(pages.size(), pages.size() - skipped, allChunks.size());
    }

    public IngestionResult ingestPage(String pageId) {
        log.info("Ingesting single page: {}", pageId);

        ConfluencePage page = pageFetcher.fetchPageById(pageId);
        if (page == null) return new IngestionResult(0, 0, 0);

        return documentBuilder.build(page)
                .map(doc -> {
                    List<Document> chunks = chunker.chunk(doc);
                    vectorStore.add(chunks);
                    log.info("Ingested '{}' → {} chunks", page.getTitle(), chunks.size());
                    return new IngestionResult(1, 1, chunks.size());
                })
                .orElseGet(() -> new IngestionResult(1, 0, 0));
    }

    public record IngestionResult(int pagesFetched, int documentsBuilt, int chunksStored) {
        public String summary() {
            return String.format("Fetched %d pages → %d documents → %d chunks stored",
                    pagesFetched, documentsBuilt, chunksStored);
        }
    }
}