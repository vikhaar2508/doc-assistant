package com.confluence.docassistant.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PlannerAgent — understands intent and generates search queries.
 *
 * Uses conversation context to detect follow-up questions vs topic switches.
 * LLM decides whether the question continues the current topic or starts fresh.
 *
 * Zero hardcoded page names. Zero hardcoded domain knowledge.
 */
@Component
public class PlannerAgent {

    private static final Logger log = LoggerFactory.getLogger(PlannerAgent.class);

    private final ChatClient   chatClient;
    private final ObjectMapper objectMapper;

    public PlannerAgent(ChatClient.Builder builder) {
        this.chatClient   = builder.build();
        this.objectMapper = new ObjectMapper();
    }

    public SearchPlan plan(String question) {
        return plan(question, "");
    }

    public SearchPlan plan(String question, String contextHint) {
        log.debug("Planning: '{}'", question);

        String response = chatClient.prompt()
                .system("""
                        You are a search query planner for a technical documentation system.
                        Respond with valid JSON only. No explanation, no markdown, no code blocks.
                        
                        INTENT MAPPING — treat these as content retrieval:
                        "Do you have X?"     → retrieve X
                        "Is there a guide?"  → retrieve the guide
                        "Tell me about X"    → retrieve X
                        "Show me X"          → retrieve X
                        
                        FOLLOW-UP DETECTION:
                        If conversation context is provided, determine if the question is:
                        a) A follow-up about the same topic (no new subject mentioned)
                           → keep the same entities from context
                           → isFollowUp: true
                        b) A topic switch (different subject clearly mentioned)
                           → use new entities
                           → isFollowUp: false
                        
                        Follow-up signals (no subject = follow-up):
                        "what happens if there's a failure?"
                        "who gets notified?"
                        "what is the error process?"
                        "tell me more"
                        "what about the contacts?"
                        
                        Topic switch signals (clear new subject):
                        "what is the LDAP URL?"           → new topic: LDAP/Elytron
                        "show me the deployment steps"    → new topic: Deployment Runbook
                        "what apps are not started?"      → new topic: Application Inventory
                        "EAP migration"                   → new topic: Application Inventory
                        "migration status"                → new topic: Application Inventory
                        "which apps are complete"         → new topic: Application Inventory
                        "PrimeFaces" or "RichFaces"       → new topic: PrimeFaces Migration
                        "CDI" or "bean scope"             → new topic: CDI Bean Migration
                        
                        KEY RULE: If the question contains a TECHNICAL DOMAIN term
                        that clearly belongs to a different topic than the context — 
                        treat it as a topic switch even if no page name is mentioned.
                        
                        SYNONYM AWARENESS:
                        interfaces = source systems = integrations = upstream = data feeds
                        screens = pages = views = UI = forms
                        jobs = batch = scheduled tasks = processes
                        config = configuration = setup = settings
                        app = application = system = service = module
                        
                        PAGE TITLE: always null — system resolves page automatically.
                        """)
                .user((contextHint + """
                        
                        Question: "%s"
                        
                        Respond with exactly this JSON:
                        {
                          "intent": "one sentence describing what user wants",
                          "entities": ["specific names or systems mentioned or inherited from context"],
                          "queries": ["query1", "query2", "query3", "query4"],
                          "needsFullPage": true or false,
                          "isFollowUp": true or false,
                          "pageTitle": null
                        }
                        
                        Rules:
                        - entities: include names from question OR from context if follow-up
                        - queries: 4 queries using synonyms and different angles
                        - needsFullPage: true if about specific app/system or asking all details
                        - isFollowUp: true if continuing previous topic, false if new topic
                        - pageTitle: ALWAYS null
                        """).formatted(question))
                .call()
                .content();

        return parsePlan(response, question);
    }

    private SearchPlan parsePlan(String response, String fallbackQuestion) {
        try {
            String json = response.trim()
                    .replaceAll("^```json\\s*", "")
                    .replaceAll("^```\\s*", "")
                    .replaceAll("```\\s*$", "")
                    .trim();
            return objectMapper.readValue(json, SearchPlan.class);
        } catch (Exception e) {
            log.warn("Failed to parse planner response: {}", e.getMessage());
            return new SearchPlan(
                    "Answer user question",
                    List.of(),
                    List.of(fallbackQuestion,
                            fallbackQuestion + " details",
                            fallbackQuestion + " overview"),
                    false,
                    false,
                    null
            );
        }
    }

    public record SearchPlan(
            String intent,
            List<String> entities,
            List<String> queries,
            boolean needsFullPage,
            boolean isFollowUp,
            String pageTitle
    ) {
        public SearchPlan() { this("", List.of(), List.of(), false, false, null); }
    }
}