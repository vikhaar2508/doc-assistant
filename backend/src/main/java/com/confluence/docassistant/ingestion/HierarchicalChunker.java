package com.confluence.docassistant.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HierarchicalChunker v2 — format-agnostic chunking.
 *
 * Works regardless of how Confluence pages are written.
 * No reliance on headings. Tables are NEVER split.
 *
 * Strategy:
 * L1 — page summary (1 per page)
 * L2 — logical blocks: full tables, full numbered lists, paragraphs
 * L3 — atomic facts: individual table rows, individual steps, sentences
 *
 * Detection order (priority):
 * 1. Markdown tables (| col | col |) → always kept whole
 * 2. Key-value rows (→ key: val | key: val) → always kept whole
 * 3. Numbered lists (1. 2. 3.) → kept as one block
 * 4. Paragraphs → split by double newline
 * 5. Remaining text → kept as-is
 */
@Component
public class HierarchicalChunker {

    private static final Logger log = LoggerFactory.getLogger(HierarchicalChunker.class);

    // Detects markdown table rows
    private static final Pattern MD_TABLE_ROW  = Pattern.compile("^\\|.+\\|\\s*$", Pattern.MULTILINE);
    // Detects key-value rows from our Jsoup parser
    private static final Pattern KV_ROW        = Pattern.compile("^→.+$", Pattern.MULTILINE);
    // Detects numbered list items
    private static final Pattern NUMBERED_ITEM = Pattern.compile("^\\d+\\.\\s+.+$", Pattern.MULTILINE);
    // Detects headings (optional — used if present)
    private static final Pattern HEADING       = Pattern.compile("^#{1,3}\\s+.+$", Pattern.MULTILINE);
    // Semantic enrichment marker
    private static final String  ENRICHMENT_MARKER = "--- SEMANTIC ENRICHMENT ---";

    /**
     * Splits a document into L1, L2, and L3 chunks.
     * Works on any page format — headings optional.
     */
    public List<Document> chunk(Document document) {
        String pageTitle = (String) document.getMetadata().getOrDefault("title", "");
        String fullContent = document.getText();

        // Separate original content from enrichment
        String originalContent;
        String enrichmentContent;
        int enrichmentIdx = fullContent.indexOf(ENRICHMENT_MARKER);
        if (enrichmentIdx > 0) {
            originalContent   = fullContent.substring(0, enrichmentIdx).trim();
            enrichmentContent = fullContent.substring(enrichmentIdx).trim();
        } else {
            originalContent   = fullContent.trim();
            enrichmentContent = "";
        }

        List<Document> allChunks = new ArrayList<>();
        Map<String, Object> baseMeta = document.getMetadata();

        // ── L1: Page Summary ──────────────────────────────────────────────────
        allChunks.add(buildL1(pageTitle, enrichmentContent, originalContent, baseMeta));

        // ── Extract logical blocks ────────────────────────────────────────────
        List<Block> blocks = extractBlocks(originalContent, pageTitle);
        log.debug("Page '{}' → {} logical blocks", pageTitle, blocks.size());

        for (Block block : blocks) {
            // ── L2: Full Block ────────────────────────────────────────────────
            allChunks.add(buildL2(block, pageTitle, baseMeta));

            // ── L3: Atomic Facts ──────────────────────────────────────────────
            allChunks.addAll(buildL3(block, pageTitle, baseMeta));
        }

        log.debug("Page '{}' → {} total chunks", pageTitle, allChunks.size());
        return allChunks;
    }

    // ── L1 ────────────────────────────────────────────────────────────────────

    private Document buildL1(String pageTitle, String enrichment,
                             String original, Map<String, Object> baseMeta) {
        // L1 uses enrichment summary for best semantic matching
        String summary = extractSummary(enrichment, original, pageTitle);

        Map<String, Object> meta = new HashMap<>(baseMeta);
        meta.put("layer",   "L1");
        meta.put("section", "summary");

        return new Document(summary, meta);
    }

    // ── L2 ────────────────────────────────────────────────────────────────────

    private Document buildL2(Block block, String pageTitle,
                             Map<String, Object> baseMeta) {
        String l2Id = UUID.randomUUID().toString();

        // Prepend page context so LLM always knows what page this is from
        String content = "Page: " + pageTitle + "\n" +
                (block.heading().isBlank() ? "" : "Section: " + block.heading() + "\n") +
                "\n" + block.content();

        Map<String, Object> meta = new HashMap<>(baseMeta);
        meta.put("layer",   "L2");
        meta.put("type",    block.type().name());
        meta.put("section", block.heading());
        meta.put("l2Id",    l2Id);

        return new Document(content, meta);
    }

    // ── L3 ────────────────────────────────────────────────────────────────────

    private List<Document> buildL3(Block block, String pageTitle,
                                   Map<String, Object> baseMeta) {
        List<Document> l3 = new ArrayList<>();

        switch (block.type()) {
            case TABLE -> {
                // Each key-value row = one L3 chunk
                Matcher kv = KV_ROW.matcher(block.content());
                while (kv.find()) {
                    String row = kv.group().trim();
                    if (row.length() < 15) continue;

                    String content = "From '" + pageTitle + "': " + row;
                    Map<String, Object> meta = new HashMap<>(baseMeta);
                    meta.put("layer",   "L3");
                    meta.put("type",    "table-row");
                    meta.put("section", block.heading());
                    l3.add(new Document(content, meta));
                }
            }
            case LIST -> {
                // Each numbered item = one L3 chunk
                Matcher items = NUMBERED_ITEM.matcher(block.content());
                while (items.find()) {
                    String item = items.group().trim();
                    String content = "Step from '" + pageTitle + "': " + item;
                    Map<String, Object> meta = new HashMap<>(baseMeta);
                    meta.put("layer",   "L3");
                    meta.put("type",    "list-item");
                    meta.put("section", block.heading());
                    l3.add(new Document(content, meta));
                }
            }
            case PARAGRAPH -> {
                // Long paragraphs → split by sentence
                if (block.content().length() > 300) {
                    String[] sentences = block.content().split("(?<=[.!?])\\s+");
                    for (String sentence : sentences) {
                        if (sentence.trim().length() < 30) continue;
                        String content = "From '" + pageTitle + "': " + sentence.trim();
                        Map<String, Object> meta = new HashMap<>(baseMeta);
                        meta.put("layer",   "L3");
                        meta.put("type",    "sentence");
                        meta.put("section", block.heading());
                        l3.add(new Document(content, meta));
                    }
                }
            }
        }

        return l3;
    }

    // ── Block Extraction ──────────────────────────────────────────────────────

    /**
     * Extracts logical blocks from content.
     * Format-agnostic — works with or without headings.
     *
     * Detection priority:
     * 1. Tables (markdown + key-value rows) → TABLE block
     * 2. Numbered lists → LIST block
     * 3. Everything else → PARAGRAPH block
     */
    private List<Block> extractBlocks(String content, String pageTitle) {
        List<Block> blocks   = new ArrayList<>();
        String[]    lines    = content.split("\n");
        String      currentHeading = "";

        StringBuilder tableBuffer    = new StringBuilder();
        StringBuilder listBuffer     = new StringBuilder();
        StringBuilder paragraphBuffer = new StringBuilder();

        boolean inTable = false;
        boolean inList  = false;

        for (String line : lines) {
            String trimmed = line.trim();

            // Detect heading — update current section name
            if (HEADING.matcher(trimmed).matches()) {
                // Flush any pending buffers
                flushTable(tableBuffer, currentHeading, blocks);
                flushList(listBuffer, currentHeading, blocks);
                flushParagraph(paragraphBuffer, currentHeading, blocks);
                inTable = false;
                inList  = false;
                currentHeading = trimmed.replaceAll("^#+\\s*", "");
                continue;
            }

            // Detect table row (markdown or key-value)
            boolean isTableRow = MD_TABLE_ROW.matcher(trimmed).matches()
                    || KV_ROW.matcher(trimmed).matches()
                    || trimmed.startsWith("| ---"); // separator row

            // Detect numbered list item
            boolean isListItem = NUMBERED_ITEM.matcher(trimmed).matches();

            if (isTableRow) {
                // Flush non-table buffers
                if (!inTable) {
                    flushList(listBuffer, currentHeading, blocks);
                    flushParagraph(paragraphBuffer, currentHeading, blocks);
                    inTable = true;
                    inList  = false;
                }
                tableBuffer.append(line).append("\n");
            } else if (isListItem) {
                // Flush non-list buffers
                if (!inList) {
                    flushTable(tableBuffer, currentHeading, blocks);
                    flushParagraph(paragraphBuffer, currentHeading, blocks);
                    inList  = true;
                    inTable = false;
                }
                listBuffer.append(line).append("\n");
            } else {
                // Regular text — if we were in table/list, flush them
                if (inTable) {
                    flushTable(tableBuffer, currentHeading, blocks);
                    inTable = false;
                }
                if (inList) {
                    flushList(listBuffer, currentHeading, blocks);
                    inList = false;
                }

                // Paragraph break — flush paragraph
                if (trimmed.isEmpty() && paragraphBuffer.length() > 0) {
                    flushParagraph(paragraphBuffer, currentHeading, blocks);
                } else if (!trimmed.isEmpty()) {
                    paragraphBuffer.append(line).append("\n");
                }
            }
        }

        // Flush remaining buffers
        flushTable(tableBuffer, currentHeading, blocks);
        flushList(listBuffer, currentHeading, blocks);
        flushParagraph(paragraphBuffer, currentHeading, blocks);

        return blocks;
    }

    // ── Buffer Flushers ───────────────────────────────────────────────────────

    private void flushTable(StringBuilder buf, String heading, List<Block> blocks) {
        String content = buf.toString().trim();
        if (!content.isBlank()) {
            blocks.add(new Block(BlockType.TABLE, heading, content));
        }
        buf.setLength(0);
    }

    private void flushList(StringBuilder buf, String heading, List<Block> blocks) {
        String content = buf.toString().trim();
        if (!content.isBlank()) {
            blocks.add(new Block(BlockType.LIST, heading, content));
        }
        buf.setLength(0);
    }

    private void flushParagraph(StringBuilder buf, String heading, List<Block> blocks) {
        String content = buf.toString().trim();
        if (content.length() > 30) { // skip tiny fragments
            blocks.add(new Block(BlockType.PARAGRAPH, heading, content));
        }
        buf.setLength(0);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String extractSummary(String enrichment, String original, String title) {
        // Try to get SUMMARY from semantic enrichment first
        int idx = enrichment.indexOf("SUMMARY:");
        if (idx >= 0) {
            String after = enrichment.substring(idx + 8).trim();
            int end = after.indexOf("\n\n");
            String summary = end > 0 ? after.substring(0, end).trim() : after.trim();
            if (!summary.isBlank()) {
                return "Page: " + title + "\n\n" + summary + "\n\n" + enrichment;
            }
        }
        // Fallback — first 500 chars of original
        String preview = original.substring(0, Math.min(500, original.length()));
        return "Page: " + title + "\n\n" + preview + "\n\n" + enrichment;
    }

    // ── Types ─────────────────────────────────────────────────────────────────

    public enum BlockType { TABLE, LIST, PARAGRAPH }

    public record Block(BlockType type, String heading, String content) {}
}