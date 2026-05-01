package com.confluence.docassistant.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SynthesisAgent — reasons across retrieved chunks and generates answers.
 *
 * Returns structured output:
 * - answer       — the actual response
 * - confidence   — HIGH / MEDIUM / LOW
 * - gaps         — what's missing from the docs
 * - followUps    — suggested next questions
 * - sources      — Java-generated citations (never LLM URLs)
 */
@Component
public class SynthesisAgent {

    private static final Logger log = LoggerFactory.getLogger(SynthesisAgent.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            You are DocAssistant — an expert technical colleague who has deeply read
            every page of internal documentation.
            
            You think like a senior engineer:
            - Complete, well-structured answers
            - Present table data clearly — list EVERY row, never skip any
            - Direct and technical — no fluff
            - Honest about gaps — say what's missing rather than guessing
            
            CRITICAL RULES:
            1. NEVER say "I couldn't find" if ANY relevant info exists in context
            2. NEVER use placeholder text or invent information
            3. NEVER write 📄 citations or URLs — sources are added automatically
            4. TABLE RULE: Count ALL rows first, then list every single one
            5. NEVER stop listing table rows early
            
            Respond ONLY with this exact JSON structure — no markdown, no preamble:
            {
              "answer": "your full answer here",
              "confidence": "HIGH" or "MEDIUM" or "LOW",
              "confidenceReason": "one sentence explaining why",
              "gaps": ["gap1", "gap2"],
              "followUps": ["question1", "question2", "question3"]
            }
            
            Confidence rules:
            HIGH   — question directly answered from explicit content in context
            MEDIUM — question partially answered or requires inference
            LOW    — little relevant content found, answer may be incomplete
            
            Gaps — list things the user asked about that aren't in the docs:
            - Missing contact details
            - Undocumented error handling
            - Missing configuration values
            - Anything you had to infer rather than read directly
            Leave gaps as empty array [] if everything was well documented.
            
            Follow-ups — 3 specific questions a developer would naturally ask next.
            Make them specific to the content — never generic.
            """;

    private final ChatClient chatClient;
    private final ChatMemory memory;

    public SynthesisAgent(ChatClient.Builder builder) {
        this.memory = new InMemoryChatMemory();
        this.chatClient = builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(new MessageChatMemoryAdvisor(memory))
                .build();
    }

    /**
     * Main synthesis — returns structured SynthesisResult.
     */
    public SynthesisResult synthesizeFull(String question,
                                          List<Chunk> chunks,
                                          String intent,
                                          String conversationId) {
        if (chunks.isEmpty()) {
            return new SynthesisResult(
                    handleNoContext(question),
                    "LOW",
                    "No relevant documentation found",
                    List.of("No documentation found for this topic"),
                    List.of(),
                    ""
            );
        }

        String context = buildContext(chunks);
        String sources = buildSourceCitations(chunks);

        log.debug("Synthesizing from {} chunks for: {}", chunks.size(), question);

        String raw = chatClient.prompt()
                .user("""
                        Context from documentation:
                        ===
                        %s
                        ===
                        
                        User question: %s
                        Intent: %s
                        
                        Instructions:
                        - Answer using ONLY information from the context
                        - List ALL rows from any table — do not skip any
                        - Assess confidence honestly based on what you found
                        - Identify genuine gaps — things asked about but not documented
                        - Generate 3 specific follow-up questions
                        - Respond with ONLY the JSON structure, nothing else
                        """.formatted(context, question, intent))
                .advisors(spec -> spec
                        .param(MessageChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId)
                        .param(MessageChatMemoryAdvisor.CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10))
                .call()
                .content();

        return parseResult(raw, chunks, sources);
    }

    /**
     * Backward compatible synthesize — returns just the answer string.
     */
    public String synthesize(String question,
                             List<Chunk> chunks,
                             String intent,
                             String conversationId) {
        return synthesizeFull(question, chunks, intent, conversationId).answer();
    }

    /**
     * Generate follow-ups separately if needed.
     */
    public List<String> generateFollowUps(String question, String answer, List<Chunk> chunks) {
        return List.of(); // now handled inside synthesizeFull
    }

    /**
     * Handle conversational messages.
     */
    public String conversationalReply(String message) {
        String m = message.toLowerCase().trim();
        if (m.contains("thank") || m.contains("cheers")) {
            return "You're welcome! Let me know if you have any other questions.";
        }
        if (m.contains("bye") || m.contains("goodbye")) {
            return "Goodbye! Feel free to come back anytime.";
        }
        if (m.contains("hi") || m.contains("hello") || m.contains("hey")) {
            return "Hi! What would you like to know about the documentation?";
        }
        return "Got it! Let me know if there's anything else you'd like to know.";
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private SynthesisResult parseResult(String raw, List<Chunk> chunks, String sources) {
        try {
            // Clean up markdown code blocks if present
            String json = raw.trim()
                    .replaceAll("^```json\\s*", "")
                    .replaceAll("^```\\s*", "")
                    .replaceAll("```\\s*$", "")
                    .trim();

            JsonNode node = objectMapper.readTree(json);

            String answer     = node.path("answer").asText("");
            String confidence = node.path("confidence").asText("MEDIUM");
            String confReason = node.path("confidenceReason").asText("");

            List<String> gaps = new java.util.ArrayList<>();
            node.path("gaps").forEach(g -> gaps.add(g.asText()));

            List<String> followUps = new java.util.ArrayList<>();
            node.path("followUps").forEach(f -> followUps.add(f.asText()));

            // Strip any citations LLM included — line by line, no regex
            String cleanAnswer = Arrays.stream(answer.split("\n"))
                    .filter(line -> {
                        String t = line.trim();
                        return !t.startsWith("📄")
                                && !t.startsWith("Source:")
                                && !t.startsWith("Sources:")
                                && !t.contains("atlassian.net")
                                && !(t.startsWith("[") && t.contains("](http"));
                    })
                    .collect(Collectors.joining("\n"))
                    .trim();

            return new SynthesisResult(cleanAnswer, confidence, confReason, gaps, followUps, sources);

        } catch (Exception e) {
            log.warn("Failed to parse structured response: {} — falling back", e.getMessage());
            // Fallback — treat raw as plain answer
            return new SynthesisResult(raw.trim(), "MEDIUM", "", List.of(), List.of(), sources);
        }
    }

    private String buildContext(List<Chunk> chunks) {
        List<Chunk> topChunks = chunks.stream().limit(8).toList();

        Map<String, List<Chunk>> byPage = topChunks.stream()
                .collect(Collectors.groupingBy(
                        Chunk::title,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        StringBuilder context = new StringBuilder();
        for (var entry : byPage.entrySet()) {
            String pageTitle = entry.getKey();
            List<Chunk> pageChunks = entry.getValue();

            String pageUrl = pageChunks.stream()
                    .map(Chunk::url)
                    .filter(u -> u != null && !u.isBlank())
                    .findFirst().orElse("");

            context.append("## ").append(pageTitle).append("\n");
            if (!pageUrl.isBlank()) {
                context.append("CONFLUENCE URL: ").append(pageUrl).append("\n");
            }
            context.append("\n");
            for (Chunk chunk : pageChunks) {
                context.append(chunk.content()).append("\n\n");
            }
            context.append("---\n");
        }
        return context.toString();
    }

    private String buildSourceCitations(List<Chunk> chunks) {
        Map<String, Long> chunkCounts = chunks.stream()
                .filter(c -> c.title() != null && !c.title().isBlank())
                .collect(Collectors.groupingBy(Chunk::title, Collectors.counting()));

        long maxCount = chunkCounts.values().stream()
                .mapToLong(Long::longValue).max().orElse(0);

        Map<String, String> citationMap = new LinkedHashMap<>();
        chunks.stream()
                .filter(c -> c.title() != null && !c.title().isBlank())
                .filter(c -> c.url() != null && !c.url().isBlank())
                .filter(c -> chunkCounts.getOrDefault(c.title(), 0L) >= Math.max(1, maxCount / 2))
                .sorted((a, b) -> Long.compare(
                        chunkCounts.getOrDefault(b.title(), 0L),
                        chunkCounts.getOrDefault(a.title(), 0L)))
                .forEach(c -> citationMap.putIfAbsent(c.title(), c.url()));

        String citations = citationMap.entrySet().stream()
                .map(e -> "\n📄 [" + e.getKey() + "](" + e.getValue() + ")")
                .collect(Collectors.joining());

        return citations.isBlank() ? "" : "\n\n" + citations.strip();
    }

    private String handleNoContext(String question) {
        return chatClient.prompt()
                .user("""
                        The user asked: "%s"
                        No relevant documentation was found.
                        Respond naturally — acknowledge it and suggest rephrasing.
                        Keep it brief.
                        """.formatted(question))
                .call()
                .content();
    }

    /**
     * Structured synthesis result.
     */
    public record SynthesisResult(
            String answer,
            String confidence,        // HIGH / MEDIUM / LOW
            String confidenceReason,  // why this confidence level
            List<String> gaps,        // what's missing from docs
            List<String> followUps,   // suggested next questions
            String sources            // Java-generated citation links
    ) {
        public String fullAnswer() {
            return answer + sources;
        }
    }
}