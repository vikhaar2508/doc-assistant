package com.confluence.docassistant.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * AgenticRAGOrchestrator — all 8 best practices.
 *
 * Conversation-aware retrieval:
 * - Per-conversation context stored in ConversationContext
 * - PlannerAgent detects follow-ups vs topic switches
 * - Thread-safe — multiple users supported simultaneously
 */
@Service
public class AgenticRAGOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgenticRAGOrchestrator.class);

    private static final int INITIAL_TOP_K   = 5;
    private static final int FULL_PAGE_TOP_K = 8;
    private static final int FINAL_TOP_K     = 8;

    private static final Set<String> CONVERSATIONAL = Set.of(
            "thanks", "thank you", "ok", "okay", "got it", "alright", "great",
            "perfect", "sure", "cool", "noted", "understood", "cheers", "bye",
            "goodbye", "hello", "hi", "hey", "good", "nice", "awesome",
            "sounds good", "no worries", "no problem", "that's all", "thats all",
            "done", "alright thanks", "ok thanks", "okay thanks",
            "thank you so much", "that helps"
    );

    private final PlannerAgent         planner;
    private final HybridSearchService  searchService;
    private final MetadataFilter       metadataFilter;
    private final ParentChildRetriever parentChildRetriever;
    private final CrossEncoderReranker reranker;
    private final ReflectionAgent      reflectionAgent;
    private final SynthesisAgent       synthesisAgent;
    private final ConversationContext  conversationContext;

    public AgenticRAGOrchestrator(PlannerAgent planner,
                                  HybridSearchService searchService,
                                  MetadataFilter metadataFilter,
                                  ParentChildRetriever parentChildRetriever,
                                  CrossEncoderReranker reranker,
                                  ReflectionAgent reflectionAgent,
                                  SynthesisAgent synthesisAgent,
                                  ConversationContext conversationContext) {
        this.planner             = planner;
        this.searchService       = searchService;
        this.metadataFilter      = metadataFilter;
        this.parentChildRetriever= parentChildRetriever;
        this.reranker            = reranker;
        this.reflectionAgent     = reflectionAgent;
        this.synthesisAgent      = synthesisAgent;
        this.conversationContext = conversationContext;
    }

    private boolean isConversational(String question) {
        String q = question.toLowerCase().trim()
                .replaceAll("[^a-z0-9 ]", "").trim();
        return CONVERSATIONAL.contains(q) || q.length() < 5;
    }

    public RAGResult answerWithFollowUps(String question, String conversationId) {
        long start = System.currentTimeMillis();
        log.info("=== Agentic RAG: '{}' [conv={}] ===", question, conversationId);

        // Short-circuit for conversational messages
        if (isConversational(question)) {
            log.debug("Conversational — skipping RAG");
            return new RAGResult(synthesisAgent.conversationalReply(question), List.of(), "HIGH", "", List.of());
        }

        // Build context hint from per-conversation state
        String contextHint = conversationContext.buildContextHint(conversationId);

        // Plan with conversation context
        PlannerAgent.SearchPlan plan = planner.plan(question, contextHint);
        log.info("Plan: intent='{}' entities={} needsFullPage={} isFollowUp={}",
                plan.intent(), plan.entities(), plan.needsFullPage(), plan.isFollowUp());

        // Retrieve chunks
        List<Chunk> chunks = retrieveChunks(question, plan, conversationId);

        // Generate structured answer with confidence + gaps
        SynthesisAgent.SynthesisResult result = synthesisAgent.synthesizeFull(
                question, chunks, plan.intent(), conversationId);

        log.info("=== Complete in {}ms — confidence={} gaps={} ===",
                System.currentTimeMillis() - start,
                result.confidence(),
                result.gaps().size());

        return new RAGResult(
                result.fullAnswer(),
                result.followUps(),
                result.confidence(),
                result.confidenceReason(),
                result.gaps()
        );
    }

    public String answer(String question, String conversationId) {
        return answerWithFollowUps(question, conversationId).answer();
    }

    private List<Chunk> retrieveChunks(String question,
                                       PlannerAgent.SearchPlan plan,
                                       String conversationId) {
        List<Chunk> chunks = new ArrayList<>();

        // ── Resolve target page ───────────────────────────────────────────────
        String targetPage = null;

        if (plan.needsFullPage()) {
            if (plan.entities() != null && !plan.entities().isEmpty()) {
                // Use first detected entity as page search term
                targetPage = plan.entities().get(0);
                log.info("Target page from entity: '{}'", targetPage);
            } else if (plan.isFollowUp()) {
                // Follow-up with no entity — use conversation context
                targetPage = conversationContext.getLastPage(conversationId);
                log.info("Target page from conversation context: '{}'", targetPage);
            }
        } else if (plan.isFollowUp() && !conversationContext.getLastPage(conversationId).isBlank()) {
            // Even if needsFullPage is false, if it's a follow-up use context
            targetPage = conversationContext.getLastPage(conversationId);
            log.info("Follow-up target page: '{}'", targetPage);
        }

        // ── Fetch from target page ────────────────────────────────────────────
        if (targetPage != null && !targetPage.isBlank()) {
            String[] words = targetPage.trim().split("\\s+");
            String fuzzy = words.length >= 2 ? words[0] + " " + words[1] : words[0];

            // Get ALL L2 chunks (tables + paragraphs + lists)
            List<Chunk> l2chunks = metadataFilter.getPageChunks(fuzzy, "L2");
            chunks.addAll(l2chunks);
            log.info("L2 chunks: {} for '{}'", l2chunks.size(), fuzzy);

            // Also explicitly get TABLE chunks
            List<Chunk> tableChunks = metadataFilter.getTableChunks(fuzzy);
            for (Chunk c : tableChunks) {
                if (chunks.stream().noneMatch(x -> x.id().equals(c.id()))) chunks.add(c);
            }
            log.info("After page fetch: {} chunks", chunks.size());

            // Update conversation context for next turn
            conversationContext.update(conversationId, targetPage, plan.intent());
        } else if (!plan.entities().isEmpty()) {
            // Has entities but needsFullPage is false — still update context
            conversationContext.update(conversationId, plan.entities().get(0), plan.intent());
        }

        // ── Hybrid search ─────────────────────────────────────────────────────
        List<Chunk> searchChunks = searchService.multiQuerySearch(plan.queries(), INITIAL_TOP_K);
        for (Chunk c : searchChunks) {
            if (chunks.stream().noneMatch(x -> x.id().equals(c.id()))) chunks.add(c);
        }
        log.info("After hybrid search: {} chunks", chunks.size());

        // ── Full page fallback ────────────────────────────────────────────────
        if (targetPage != null && chunks.size() < 3) {
            List<Chunk> fallback = searchService.getChunksByTitle(targetPage);
            for (Chunk c : fallback) {
                if (chunks.stream().noneMatch(x -> x.id().equals(c.id()))) chunks.add(c);
            }
        }

        // ── Parent-child ──────────────────────────────────────────────────────
        List<Chunk> enriched = parentChildRetriever.enrichWithParents(chunks);

        // ── Rerank ────────────────────────────────────────────────────────────
        List<Chunk> reranked = reranker.rerank(question, enriched, FULL_PAGE_TOP_K);

        // ── Reflect ───────────────────────────────────────────────────────────
        List<Chunk> reflected = reflectionAgent.reflect(question, reranked);

        // ── Prioritize target page in final selection ─────────────────────────
        if (targetPage != null && !targetPage.isBlank()) {
            String[] words = targetPage.trim().split("\\s+");
            String fuzzy = (words.length >= 2
                    ? words[0] + " " + words[1]
                    : words[0]).toLowerCase();

            List<Chunk> targetChunks = reflected.stream()
                    .filter(c -> c.title() != null &&
                            c.title().toLowerCase().contains(fuzzy))
                    .limit(FINAL_TOP_K)
                    .toList();

            if (targetChunks.size() >= 2) {
                log.info("Prioritized {} chunks from '{}'", targetChunks.size(), targetPage);
                return targetChunks;
            }
        }

        return reflected.stream().limit(FINAL_TOP_K).toList();
    }

    public record RAGResult(
            String answer,
            List<String> followUps,
            String confidence,
            String confidenceReason,
            List<String> gaps
    ) {}
}