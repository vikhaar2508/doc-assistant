package com.confluence.docassistant.ingestion;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Converts raw Confluence HTML storage format into clean, structured plain text
 * that preserves semantic meaning for RAG ingestion.
 *
 * Handles:
 * - Tables → row-per-line format with headers repeated (chunk-safe)
 * - Code blocks → clearly labelled
 * - Headings → preserved as section markers
 * - Lists → converted to readable bullet lines
 * - Confluence macros → stripped cleanly
 */
@Component
public class HtmlContentParser {

    private final DiagramExtractor diagramExtractor;

    public HtmlContentParser(DiagramExtractor diagramExtractor) {
        this.diagramExtractor = diagramExtractor;
    }

    /**
     * Converts Confluence HTML to clean text including diagram descriptions.
     *
     * @param html   raw Confluence storage HTML
     * @param pageId Confluence page ID needed to fetch diagram attachments
     */
    public String parse(String html, String pageId) {
        if (html == null || html.isBlank()) return "";

        Document doc = Jsoup.parse(html);
        StringBuilder output = new StringBuilder();

        // Step 1: Process all text-based elements
        processElement(doc.body(), output);

        // Step 2: Append diagram descriptions (vision AI)
        List<String> diagrams = diagramExtractor.extractDiagramDescriptions(doc, pageId);
        if (!diagrams.isEmpty()) {
            output.append("\n\n## Diagrams\n\n");
            diagrams.forEach(d -> output.append(d).append("\n\n"));
        }

        return normalizeWhitespace(output.toString());
    }

    // ── Element Processing ────────────────────────────────────────────────────

    private void processElement(Element element, StringBuilder output) {
        for (Element child : element.children()) {
            String tag = child.tagName().toLowerCase();

            switch (tag) {
                case "h1", "h2", "h3", "h4", "h5", "h6" ->
                        appendHeading(child, output);

                case "table" ->
                        appendTable(child, output);

                case "ul", "ol" ->
                        appendList(child, output);

                case "pre", "code" ->
                        appendCode(child, output);

                case "p" ->
                        appendParagraph(child, output);

                // Confluence info/note/warning macros
                case "div" -> {
                    String cls = child.className();
                    if (cls.contains("confluence-information-macro")) {
                        appendMacro(child, output);
                    } else {
                        processElement(child, output);
                    }
                }

                // Skip purely structural wrappers — recurse into them
                case "tbody", "thead", "section", "article", "main" ->
                        processElement(child, output);

                // Skip navigation, scripts, styles
                case "nav", "script", "style", "meta" -> { /* skip */ }

                default -> {
                    String text = child.text().trim();
                    if (!text.isEmpty()) {
                        output.append(text).append("\n");
                    }
                }
            }
        }
    }

    // ── Headings ──────────────────────────────────────────────────────────────

    /**
     * Headings become section markers so the LLM understands document structure.
     * Example: "## Source Applications" helps the AI answer "tell me about source apps"
     */
    private void appendHeading(Element heading, StringBuilder output) {
        String text = heading.text().trim();
        if (text.isEmpty()) return;

        int level = Integer.parseInt(heading.tagName().substring(1));
        String prefix = "#".repeat(level);

        output.append("\n").append(prefix).append(" ").append(text).append("\n\n");
    }

    // ── Tables ────────────────────────────────────────────────────────────────

    /**
     * Tables are the most important element for RAG correctness.
     *
     * Strategy: Each data row is written as a self-contained line that includes
     * the column headers. This means even if the chunk splitter cuts in the
     * middle of a table, every chunk is still independently meaningful.
     *
     * Input table:
     *   | Source App | Data Provided     | Frequency |
     *   | MPF        | Mortgage data     | Daily     |
     *   | CRS        | Collateral records| Weekly    |
     *
     * Output:
     *   Source App: MPF | Data Provided: Mortgage data | Frequency: Daily
     *   Source App: CRS | Data Provided: Collateral records | Frequency: Weekly
     */
    private void appendTable(Element table, StringBuilder output) {
        output.append("\n");

        // Extract headers from <th> elements
        List<String> headers = new ArrayList<>();
        Element headerRow = table.selectFirst("tr");
        if (headerRow != null) {
            for (Element th : headerRow.select("th")) {
                headers.add(th.text().trim());
            }
        }

        // If no <th> found, use first <tr> as headers
        if (headers.isEmpty() && headerRow != null) {
            for (Element td : headerRow.select("td")) {
                headers.add(td.text().trim());
            }
        }

        // Also write a clean markdown table for the LLM's structural understanding
        if (!headers.isEmpty()) {
            output.append("| ")
                    .append(String.join(" | ", headers))
                    .append(" |\n");
            output.append("| ")
                    .append(headers.stream().map(h -> "---").collect(Collectors.joining(" | ")))
                    .append(" |\n");
        }

        // Process each data row
        Elements rows = table.select("tr");
        boolean firstRow = true;

        for (Element row : rows) {
            // Skip the header row itself
            if (firstRow && !row.select("th").isEmpty()) {
                firstRow = false;
                continue;
            }
            firstRow = false;

            Elements cells = row.select("td");
            if (cells.isEmpty()) continue;

            List<String> cellValues = new ArrayList<>();
            for (Element cell : cells) {
                cellValues.add(cell.text().trim());
            }

            // Write as markdown row
            output.append("| ")
                    .append(String.join(" | ", cellValues))
                    .append(" |\n");

            // ALSO write as key:value line — this is what makes chunking safe
            // Each row is fully self-describing even without the header context
            if (!headers.isEmpty()) {
                List<String> kvPairs = new ArrayList<>();
                for (int i = 0; i < Math.min(headers.size(), cellValues.size()); i++) {
                    if (!cellValues.get(i).isEmpty()) {
                        kvPairs.add(headers.get(i) + ": " + cellValues.get(i));
                    }
                }
                if (!kvPairs.isEmpty()) {
                    output.append("→ ").append(String.join(" | ", kvPairs)).append("\n");
                }
            }
        }

        output.append("\n");
    }

    // ── Lists ─────────────────────────────────────────────────────────────────

    /**
     * Converts <ul>/<ol> into readable bullet lines.
     */
    private void appendList(Element list, StringBuilder output) {
        output.append("\n");
        int counter = 1;
        boolean isOrdered = list.tagName().equals("ol");

        for (Element li : list.select("> li")) {
            String text = li.text().trim();
            if (!text.isEmpty()) {
                if (isOrdered) {
                    output.append(counter++).append(". ").append(text).append("\n");
                } else {
                    output.append("- ").append(text).append("\n");
                }
            }
        }
        output.append("\n");
    }

    // ── Code Blocks ───────────────────────────────────────────────────────────

    /**
     * Code blocks are labelled clearly so the LLM knows it's reading code,
     * not prose — improves answer accuracy for technical questions.
     */
    private void appendCode(Element code, StringBuilder output) {
        String text = code.text().trim();
        if (text.isEmpty()) return;

        output.append("\n[CODE BLOCK]\n")
                .append(text)
                .append("\n[END CODE BLOCK]\n\n");
    }

    // ── Paragraphs ────────────────────────────────────────────────────────────

    private void appendParagraph(Element p, StringBuilder output) {
        String text = p.text().trim();
        if (!text.isEmpty()) {
            output.append(text).append("\n\n");
        }
    }

    // ── Confluence Macros ─────────────────────────────────────────────────────

    /**
     * Confluence info/note/warning macro boxes contain important context.
     * We extract their text and label them so the AI understands the intent.
     */
    private void appendMacro(Element div, StringBuilder output) {
        String cls = div.className();
        String label = "NOTE";

        if (cls.contains("warning")) label = "WARNING";
        else if (cls.contains("tip"))     label = "TIP";
        else if (cls.contains("note"))    label = "NOTE";
        else if (cls.contains("info"))    label = "INFO";

        String text = div.text().trim();
        if (!text.isEmpty()) {
            output.append("\n[").append(label).append("] ").append(text).append("\n\n");
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String normalizeWhitespace(String text) {
        return text
                .replaceAll("[ \\t]+", " ")        // collapse horizontal whitespace
                .replaceAll("\n{3,}", "\n\n")        // max 2 blank lines
                .replaceAll("(?m)^[ \\t]+", "")     // trim line-leading spaces
                .trim();
    }
}
