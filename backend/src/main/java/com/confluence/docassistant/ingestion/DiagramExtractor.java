package com.confluence.docassistant.ingestion;

import com.confluence.docassistant.config.ConfluenceProperties;
import com.confluence.docassistant.config.VisionProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Extracts diagrams from Confluence pages and converts them to
 * plain text descriptions using the LLaVA vision model (via Ollama).
 *
 * Supports:
 *  - Image attachments embedded in pages (PNG, JPG, GIF, SVG)
 *  - Draw.io / Diagrams.net macros (label extraction from XML)
 *  - PlantUML macros (plain text — read directly)
 *  - Gliffy diagrams (downloads rendered PNG via attachment API)
 *
 * Flow:
 *  1. Parse HTML to find diagram elements (Jsoup)
 *  2. Download image bytes via Confluence attachment API
 *  3. Base64-encode image
 *  4. Send to LLaVA via Ollama /api/generate endpoint
 *  5. Return plain-text description for RAG ingestion
 *
 * Single Responsibility: diagram → text description only.
 */
@Component
public class DiagramExtractor {

    private static final Logger log = LoggerFactory.getLogger(DiagramExtractor.class);

    private static final List<String> IMAGE_EXTENSIONS = List.of("png", "jpg", "jpeg", "gif", "svg");
    private static final String ATTACHMENTS_PATH = "/wiki/rest/api/content/{pageId}/child/attachment";

    private final WebClient confluenceWebClient;
    private final WebClient ollamaWebClient;
    private final VisionProperties visionProps;
    private final ConfluenceProperties confluenceProps;
    private final ObjectMapper objectMapper;

    public DiagramExtractor(WebClient confluenceWebClient,
                            VisionProperties visionProps,
                            ConfluenceProperties confluenceProps) {
        this.confluenceWebClient = confluenceWebClient;
        this.visionProps         = visionProps;
        this.confluenceProps     = confluenceProps;
        this.objectMapper        = new ObjectMapper();

        // Separate WebClient for Ollama (different base URL)
        this.ollamaWebClient = WebClient.builder()
                .baseUrl(visionProps.getOllamaBaseUrl())
                .codecs(config -> config.defaultCodecs()
                        .maxInMemorySize(20 * 1024 * 1024)) // 20MB for images
                .build();
    }

    /**
     * Scans a parsed Confluence HTML document for diagrams,
     * downloads them, and returns plain-text descriptions.
     *
     * @param doc    Jsoup-parsed page HTML
     * @param pageId Confluence page ID (needed to fetch attachments)
     * @return list of diagram descriptions to append to page content
     */
    public List<String> extractDiagramDescriptions(Document doc, String pageId) {
        if (!visionProps.isEnabled()) {
            log.debug("Vision extraction disabled — skipping diagrams for page {}", pageId);
            return List.of();
        }

        List<String> descriptions = new ArrayList<>();

        // 1. Handle inline image attachments (<ac:image> tags)
        descriptions.addAll(extractInlineImages(doc, pageId));

        // 2. Handle Draw.io macros
        descriptions.addAll(extractDrawioMacros(doc));

        // 3. Handle PlantUML macros (plain text — no vision needed)
        descriptions.addAll(extractPlantUml(doc));

        // 4. Handle Gliffy macros (rendered as PNG attachment)
        descriptions.addAll(extractGliffyMacros(doc, pageId));

        log.debug("Extracted {} diagram descriptions from page {}", descriptions.size(), pageId);
        return descriptions;
    }

    // ── Inline Images ─────────────────────────────────────────────────────────

    /**
     * Confluence stores embedded images as:
     * <ac:image><ri:attachment ri:filename="flow.png"/></ac:image>
     */
    private List<String> extractInlineImages(Document doc, String pageId) {
        List<String> results = new ArrayList<>();

        Elements images = doc.select("ac|image, ac\\:image");

        for (Element image : images) {
            // Get filename from ri:attachment
            Element attachment = image.selectFirst("ri|attachment, ri\\:attachment");
            if (attachment == null) continue;

            String filename = attachment.attr("ri:filename");
            if (filename.isBlank()) continue;

            String ext = getExtension(filename).toLowerCase();
            if (!IMAGE_EXTENSIONS.contains(ext)) continue;

            log.debug("Found inline image: {} on page {}", filename, pageId);

            String description = downloadAndDescribe(pageId, filename);
            if (description != null) {
                results.add(String.format("[DIAGRAM: %s]\n%s", filename, description));
            }
        }

        return results;
    }

    // ── Draw.io Macros ────────────────────────────────────────────────────────

    /**
     * Draw.io stores diagram XML inside the macro body.
     * We extract shape labels from the XML — good enough for RAG
     * without needing vision AI.
     *
     * Confluence macro format:
     * <ac:structured-macro ac:name="drawio">
     *   <ac:parameter ac:name="diagramName">My Flow</ac:parameter>
     *   <ac:plain-text-body>...XML with mxCell labels...</ac:plain-text-body>
     * </ac:structured-macro>
     */
    private List<String> extractDrawioMacros(Document doc) {
        List<String> results = new ArrayList<>();

        for (Element macro : doc.select("ac|structured-macro[ac|name=drawio], ac\\:structured-macro[ac\\:name=drawio]")) {
            String diagramName = macro.select("ac|parameter[ac|name=diagramName], ac\\:parameter[ac\\:name=diagramName]")
                    .text().trim();

            String xmlBody = macro.select("ac|plain-text-body, ac\\:plain-text-body")
                    .text().trim();

            if (xmlBody.isBlank()) continue;

            // Extract text labels from Draw.io XML (mxCell value attributes)
            List<String> labels = extractDrawioLabels(xmlBody);

            if (!labels.isEmpty()) {
                String name = diagramName.isBlank() ? "Draw.io Diagram" : diagramName;
                results.add(String.format(
                        "[DIAGRAM: %s]\nThis diagram contains the following elements: %s",
                        name, String.join(", ", labels)
                ));
                log.debug("Extracted {} labels from Draw.io diagram: {}", labels.size(), name);
            }
        }

        return results;
    }

    /**
     * Parses Draw.io XML to extract visible text labels from shape nodes.
     * Draw.io uses mxCell elements with a 'value' attribute for labels.
     */
    private List<String> extractDrawioLabels(String xml) {
        List<String> labels = new ArrayList<>();
        try {
            // Use Jsoup to parse the XML (it handles XML well)
            org.jsoup.nodes.Document xmlDoc = org.jsoup.Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser());

            for (Element cell : xmlDoc.select("mxCell")) {
                String value = cell.attr("value").trim();
                // Skip empty labels and HTML-only labels
                if (!value.isBlank() && !value.startsWith("<") && value.length() > 1) {
                    labels.add(value);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse Draw.io XML: {}", e.getMessage());
        }
        return labels;
    }

    // ── PlantUML Macros ───────────────────────────────────────────────────────

    /**
     * PlantUML is stored as plain text — no vision needed.
     * The raw markup IS the description and LLMs understand it well.
     */
    private List<String> extractPlantUml(Document doc) {
        List<String> results = new ArrayList<>();

        for (Element macro : doc.select("ac|structured-macro[ac|name=plantuml], ac\\:structured-macro[ac\\:name=plantuml]")) {
            String umlText = macro.select("ac|plain-text-body, ac\\:plain-text-body").text().trim();

            if (!umlText.isBlank()) {
                results.add("[DIAGRAM: PlantUML Flow]\n" + umlText);
                log.debug("Extracted PlantUML diagram ({} chars)", umlText.length());
            }
        }

        return results;
    }

    // ── Gliffy Macros ─────────────────────────────────────────────────────────

    /**
     * Gliffy renders as a PNG attachment. We fetch the PNG
     * and describe it via LLaVA.
     */
    private List<String> extractGliffyMacros(Document doc, String pageId) {
        List<String> results = new ArrayList<>();

        for (Element macro : doc.select("ac|structured-macro[ac|name=gliffy], ac\\:structured-macro[ac\\:name=gliffy]")) {
            String diagramName = macro.select("ac|parameter[ac|name=name], ac\\:parameter[ac\\:name=name]")
                    .text().trim();

            if (diagramName.isBlank()) continue;

            // Gliffy creates a PNG attachment with the same name
            String pngFilename = diagramName + ".png";
            log.debug("Found Gliffy diagram, looking for attachment: {}", pngFilename);

            String description = downloadAndDescribe(pageId, pngFilename);
            if (description != null) {
                results.add(String.format("[DIAGRAM: %s]\n%s", diagramName, description));
            }
        }

        return results;
    }

    // ── Image Download + Vision ───────────────────────────────────────────────

    /**
     * Downloads an image attachment from Confluence and
     * sends it to LLaVA for description.
     *
     * @param pageId   Confluence page ID
     * @param filename attachment filename
     * @return plain text description, or null if failed
     */
    private String downloadAndDescribe(String pageId, String filename) {
        try {
            // Step 1: Get attachment download URL from Confluence API
            String downloadUrl = resolveAttachmentDownloadUrl(pageId, filename);
            if (downloadUrl == null) {
                log.warn("Could not resolve download URL for: {}", filename);
                return null;
            }

            // Step 2: Download image bytes
            byte[] imageBytes = confluenceWebClient.get()
                    .uri(downloadUrl)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block();

            if (imageBytes == null || imageBytes.length == 0) {
                log.warn("Downloaded empty image for: {}", filename);
                return null;
            }

            log.debug("Downloaded image {} ({} bytes), sending to LLaVA", filename, imageBytes.length);

            // Step 3: Describe with LLaVA
            return describeWithLlava(imageBytes, filename);

        } catch (Exception e) {
            log.warn("Failed to process diagram {}: {}", filename, e.getMessage());
            return null;
        }
    }

    /**
     * Calls Confluence attachment API to get the download URL for a named attachment.
     */
    private String resolveAttachmentDownloadUrl(String pageId, String filename) {
        try {
            AttachmentList result = confluenceWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(ATTACHMENTS_PATH)
                            .queryParam("filename", filename)
                            .queryParam("expand", "_links")
                            .build(pageId))
                    .retrieve()
                    .bodyToMono(AttachmentList.class)
                    .block();

            if (result == null || result.results == null || result.results.isEmpty()) {
                return null;
            }

            AttachmentResult attachment = result.results.get(0);
            if (attachment.links == null || attachment.links.download == null) return null;

            // Confluence returns a relative path — prepend base URL
            String downloadPath = attachment.links.download;
            return confluenceProps.getBaseUrl() + "/wiki" + downloadPath;

        } catch (Exception e) {
            log.warn("Failed to resolve attachment URL for {}/{}: {}", pageId, filename, e.getMessage());
            return null;
        }
    }

    /**
     * Sends image bytes to LLaVA via Ollama's /api/generate endpoint.
     * Uses the vision prompt from configuration.
     *
     * Ollama vision request format:
     * {
     *   "model": "llava",
     *   "prompt": "Describe this diagram...",
     *   "images": ["<base64>"],
     *   "stream": false
     * }
     */
    private String describeWithLlava(byte[] imageBytes, String filename) {
        try {
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);

            Map<String, Object> requestBody = Map.of(
                    "model",  visionProps.getModel(),
                    "prompt", visionProps.getPrompt(),
                    "images", List.of(base64Image),
                    "stream", false
            );

            OllamaResponse response = ollamaWebClient.post()
                    .uri("/api/generate")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(OllamaResponse.class)
                    .block();

            if (response == null || response.response == null || response.response.isBlank()) {
                log.warn("LLaVA returned empty response for: {}", filename);
                return null;
            }

            log.debug("LLaVA described {} in {} chars", filename, response.response.length());
            return response.response.trim();

        } catch (Exception e) {
            log.warn("LLaVA call failed for {}: {}", filename, e.getMessage());
            return null;
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1) : "";
    }

    // ── Response Models ───────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class OllamaResponse {
        public String response;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class AttachmentList {
        public List<AttachmentResult> results;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class AttachmentResult {
        public AttachmentLinks links;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class AttachmentLinks {
        public String download;
    }
}
