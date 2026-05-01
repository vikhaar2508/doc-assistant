package com.confluence.docassistant.ingestion;

import com.confluence.docassistant.config.ConfluenceProperties;
import com.confluence.docassistant.model.ConfluencePage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.ArrayList;
import java.util.List;

/**
 * Responsible for all Confluence REST API communication.
 *
 * Handles:
 * - Paginated fetching of all pages in a space
 * - Fetching a single page by ID
 * - Error handling and logging per page
 *
 * Single Responsibility: HTTP + pagination only. No parsing, no storing.
 */
@Component
public class ConfluencePageFetcher {

    private static final Logger log = LoggerFactory.getLogger(ConfluencePageFetcher.class);

    private static final int PAGE_LIMIT = 25;
    private static final String EXPAND_FIELDS = "body.storage,_links,version,ancestors";

    private final WebClient confluenceWebClient;
    private final ConfluenceProperties props;

    public ConfluencePageFetcher(WebClient confluenceWebClient, ConfluenceProperties props) {
        this.confluenceWebClient = confluenceWebClient;
        this.props = props;
    }

    /**
     * Fetches ALL pages from the configured Confluence space using cursor pagination.
     * Automatically handles multi-page result sets.
     *
     * @return list of all pages with body content expanded
     */
    public List<ConfluencePage> fetchAllPagesInSpace() {
        log.info("Fetching all pages from space: {}", props.getSpaceKey());

        List<ConfluencePage> allPages = new ArrayList<>();
        int start = 0;

        while (true) {
            ConfluencePage.PageList batch = fetchBatch(start);

            if (batch == null || batch.getResults() == null || batch.getResults().isEmpty()) {
                log.debug("No more pages to fetch at offset {}", start);
                break;
            }

            List<ConfluencePage> results = batch.getResults();
            allPages.addAll(results);
            log.debug("Fetched batch of {} pages (total so far: {})", results.size(), allPages.size());

            // If we got fewer results than the limit, we've reached the last page
            if (results.size() < PAGE_LIMIT) {
                break;
            }

            start += PAGE_LIMIT;
        }

        log.info("Finished fetching. Total pages retrieved: {}", allPages.size());
        return allPages;
    }

    /**
     * Fetches a single page by its Confluence page ID.
     * Useful for incremental re-ingestion of specific pages.
     *
     * @param pageId the Confluence page ID
     * @return the page, or null if not found
     */
    public ConfluencePage fetchPageById(String pageId) {
        try {
            return confluenceWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/wiki/rest/api/content/{id}")
                            .queryParam("expand", EXPAND_FIELDS)
                            .build(pageId))
                    .retrieve()
                    .bodyToMono(ConfluencePage.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("Failed to fetch page {}: HTTP {} - {}", pageId, e.getStatusCode(), e.getMessage());
            return null;
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private ConfluencePage.PageList fetchBatch(int start) {
        try {
            return confluenceWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/wiki/rest/api/content")
                            .queryParam("spaceKey", props.getSpaceKey())
                            .queryParam("type", "page")
                            .queryParam("status", "current")
                            .queryParam("expand", EXPAND_FIELDS)
                            .queryParam("limit", PAGE_LIMIT)
                            .queryParam("start", start)
                            .build())
                    .retrieve()
                    .bodyToMono(ConfluencePage.PageList.class)
                    .block();

        } catch (WebClientResponseException e) {
            log.error("Failed to fetch page batch at offset {}: HTTP {} - {}",
                    start, e.getStatusCode(), e.getMessage());
            return null;
        }
    }
}
