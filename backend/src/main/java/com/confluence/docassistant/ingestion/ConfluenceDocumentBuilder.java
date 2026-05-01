package com.confluence.docassistant.ingestion;

import com.confluence.docassistant.config.ConfluenceProperties;
import com.confluence.docassistant.model.ConfluencePage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Converts a raw ConfluencePage into a Spring AI Document.
 *
 * Pipeline:
 * 1. Extract raw HTML
 * 2. Parse HTML → clean text (HtmlContentParser)
 * 3. Enrich with synonyms + questions + summary (SemanticEnricher)
 * 4. Build rich metadata map
 * 5. Return Spring AI Document
 */
@Component
public class ConfluenceDocumentBuilder {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceDocumentBuilder.class);
    private static final int MIN_CONTENT_LENGTH = 50;

    private final HtmlContentParser  htmlParser;
    private final SemanticEnricher   semanticEnricher;
    private final ConfluenceProperties props;

    public ConfluenceDocumentBuilder(HtmlContentParser htmlParser,
                                     SemanticEnricher semanticEnricher,
                                     ConfluenceProperties props) {
        this.htmlParser       = htmlParser;
        this.semanticEnricher = semanticEnricher;
        this.props            = props;
    }

    /**
     * Converts a ConfluencePage to a semantically enriched Spring AI Document.
     */
    public Optional<Document> build(ConfluencePage page) {
        // Step 1: Extract raw HTML
        String rawHtml = extractRawHtml(page);
        if (rawHtml.isBlank()) {
            log.debug("Skipping page '{}' — no body content", page.getTitle());
            return Optional.empty();
        }

        // Step 2: Parse HTML → clean text
        String parsedContent = htmlParser.parse(rawHtml, page.getId());
        if (parsedContent.length() < MIN_CONTENT_LENGTH) {
            log.debug("Skipping page '{}' — content too short ({} chars)",
                    page.getTitle(), parsedContent.length());
            return Optional.empty();
        }

        // Step 3: Enrich with synonyms + hypothetical questions + summary
        // This is what makes "interfaces" match "source systems" automatically
        String enrichedContent = semanticEnricher.enrich(page.getTitle(), parsedContent);

        // Step 4: Build metadata
        Map<String, Object> metadata = buildMetadata(page);

        // Step 5: Return document with enriched content
        Document document = new Document(enrichedContent, metadata);

        log.debug("Built enriched document for '{}' — {} chars total ({} original + {} enrichment)",
                page.getTitle(),
                enrichedContent.length(),
                parsedContent.length(),
                enrichedContent.length() - parsedContent.length());

        return Optional.of(document);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private String extractRawHtml(ConfluencePage page) {
        if (page.getBody() == null) return "";
        if (page.getBody().getStorage() == null) return "";
        String value = page.getBody().getStorage().getValue();
        return value != null ? value : "";
    }

    private Map<String, Object> buildMetadata(ConfluencePage page) {
        Map<String, Object> metadata = new HashMap<>();

        metadata.put("title",    page.getTitle());
        metadata.put("pageId",   page.getId());
        metadata.put("spaceKey", props.getSpaceKey());
        metadata.put("url",      buildPageUrl(page));

        if (page.getVersion() != null) {
            metadata.put("version", page.getVersion().getNumber());
            if (page.getVersion().getWhen() != null) {
                metadata.put("lastModified", page.getVersion().getWhen());
            }
        }

        if (page.getAncestors() != null && !page.getAncestors().isEmpty()) {
            String breadcrumb = page.getAncestors().stream()
                    .map(ConfluencePage::getTitle)
                    .reduce("", (a, b) -> a.isEmpty() ? b : a + " > " + b);
            metadata.put("breadcrumb", breadcrumb);
        }

        return metadata;
    }

    private String buildPageUrl(ConfluencePage page) {
        String webui = (page.get_links() != null && page.get_links().getWebui() != null)
                ? page.get_links().getWebui()
                : "";
        return props.getBaseUrl() + "/wiki" + webui;
    }
}
