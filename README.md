# DocAssistant — Enterprise Confluence RAG Chatbot

An enterprise-grade AI chatbot that answers questions from your Confluence documentation.
Built with Spring AI, Claude API (Anthropic), Ollama embeddings, PgVector, and Angular 21.

---

## Architecture Overview

```
INGESTION PIPELINE (runs at sync time)
──────────────────────────────────────
Confluence REST API
      ↓
HtmlContentParser (Jsoup)       — preserves tables, code blocks, headings
      ↓
SemanticEnricher                — LLM generates synonyms + 10 hypothetical Q&As + summary
      ↓
HierarchicalChunker             — L1 (summary) + L2 (full blocks) + L3 (atomic rows)
      ↓
nomic-embed-text (Ollama)       — 768-dim vectors, data never leaves machine
      ↓
PgVector (PostgreSQL)           — stored with rich metadata

QUERY PIPELINE (runs on every message)
──────────────────────────────────────
User question
      ↓
PlannerAgent (Claude)           — intent + 4 search queries + entity detection
      ↓
MetadataFilter                  — TABLE chunks fetched first (complete tables, never split)
      ↓
HybridSearchService             — vector search + keyword search + RRF reranking
      ↓
ParentChildRetriever            — L3 row → fetch L2 full table for context
      ↓
CrossEncoderReranker (Claude)   — scores each chunk 0-1 for relevance
      ↓
ReflectionAgent (Claude)        — enough context? retry if not
      ↓
SynthesisAgent (Claude)         — structured JSON: answer + confidence + gaps + followUps
      ↓
Java buildSourceCitations()     — real Confluence URLs (never trusted to LLM)
      ↓
Angular UI                      — streaming + confidence badge + feedback + history
```

---

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| Java | 17+ | Backend runtime |
| Maven | 3.8+ | Build tool |
| Docker Desktop | Latest | PostgreSQL + PgVector |
| Node.js | 20+ | Angular build |
| Ollama | Latest | Local embedding model |

---

## Step 1 — Install Ollama + Pull Models

Download from https://ollama.com then:

```bash
# Required — embedding model (data stays local)
ollama pull nomic-embed-text

# Optional — only needed if NOT using Claude API
ollama pull mistral
```

Verify:
```bash
curl http://localhost:11434
```

---

## Step 2 — Start PostgreSQL + PgVector

```bash
docker-compose up -d
docker ps   # verify doc-assistant-db is running
```

---

## Step 3 — Database Tables

All tables are created automatically on startup. Listed here for reference.

### vector_store (auto-created by Spring AI)
```sql
CREATE TABLE IF NOT EXISTS vector_store (
    id        UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    content   TEXT,
    metadata  JSONB,
    embedding VECTOR(768)
);
CREATE INDEX ON vector_store USING hnsw (embedding vector_cosine_ops);
```

### doc_feedback (auto-created by FeedbackService)
```sql
CREATE TABLE IF NOT EXISTS doc_feedback (
    id              SERIAL PRIMARY KEY,
    conversation_id TEXT,
    question        TEXT NOT NULL,
    answer          TEXT,
    rating          TEXT NOT NULL,   -- 'positive' or 'negative'
    comment         TEXT,
    confidence      TEXT,            -- HIGH / MEDIUM / LOW
    created_at      TIMESTAMP DEFAULT NOW()
);
```

### conversations (auto-created by ChatHistoryService)
```sql
CREATE TABLE IF NOT EXISTS conversations (
    id         TEXT PRIMARY KEY,
    title      TEXT,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
```

### messages (auto-created by ChatHistoryService)
```sql
CREATE TABLE IF NOT EXISTS messages (
    id              SERIAL PRIMARY KEY,
    conversation_id TEXT REFERENCES conversations(id) ON DELETE CASCADE,
    role            TEXT NOT NULL,   -- 'user' or 'assistant'
    content         TEXT NOT NULL,
    confidence      TEXT,            -- HIGH / MEDIUM / LOW
    gaps            TEXT,            -- pipe-separated list
    created_at      TIMESTAMP DEFAULT NOW()
);
```

### Useful Admin Queries
```sql
-- Chunk distribution per page
SELECT metadata->>'title' as page, metadata->>'type' as type, COUNT(*) as chunks
FROM vector_store
GROUP BY metadata->>'title', metadata->>'type'
ORDER BY metadata->>'title', metadata->>'type';

-- Feedback summary
SELECT rating, COUNT(*) FROM doc_feedback GROUP BY rating;

-- Failing questions (always thumbs down)
SELECT question, COUNT(*) as times FROM doc_feedback
WHERE rating = 'negative' GROUP BY question ORDER BY times DESC;

-- Delete boilerplate pages
DELETE FROM vector_store WHERE metadata->>'title' = 'Getting started in Confluence';
DELETE FROM vector_store WHERE metadata->>'title' = 'Overview';

-- Weekly quality trend
SELECT DATE_TRUNC('week', created_at),
       ROUND(100.0 * SUM(CASE WHEN rating='positive' THEN 1 END) / COUNT(*), 1) as positive_rate
FROM doc_feedback GROUP BY 1 ORDER BY 1;
```

---

## Step 4 — Choose Your AI Model

You have two options. Embeddings always stay local regardless of which you choose.

---

### Option A — Ollama (Free, Fully On-Prem) ✅ Recommended for getting started

No API key needed. Everything runs on your machine. Zero cost.

```bash
# Pull the best free model for this use case
ollama pull mistral-nemo     # 12B — best quality on 16GB RAM
# OR
ollama pull mistral           # 7B — lighter, still good
```

`application.yml`:
```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        model: mistral-nemo
        options:
          temperature: 0.7
      embedding:
        model: nomic-embed-text
    vectorstore:
      pgvector:
        initialize-schema: true
        index-type: hnsw
        distance-type: cosine_distance
        dimensions: 768
```

No changes needed to `pom.xml` — Ollama is already included.
Do NOT add `AiConfig.java` for this option.

| Model | RAM needed | Quality |
|-------|-----------|---------|
| mistral | 8GB | ⭐⭐⭐⭐ |
| mistral-nemo | 12GB | ⭐⭐⭐⭐⭐ |
| llama3.1 | 8GB | ⭐⭐⭐ |

---

### Option B — Claude API (Best Quality, Paid)

Dramatically better reasoning. ~$0.001 per question. $10 lasts months of testing.

Get API key from https://console.anthropic.com (requires $5+ credit purchase)

`application.yml`:
```yaml
spring:
  ai:
    anthropic:
      api-key: sk-ant-your-key-here
      chat:
        options:
          model: claude-haiku-4-5-20251001
          temperature: 0.7
    ollama:
      base-url: http://localhost:11434
      chat:
        enabled: false          # disable Ollama chat
      embedding:
        model: nomic-embed-text # embeddings always stay local
    vectorstore:
      pgvector:
        initialize-schema: true
        index-type: hnsw
        distance-type: cosine_distance
        dimensions: 768
```

Add to `backend/pom.xml` dependencies:
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-anthropic-spring-boot-starter</artifactId>
</dependency>
```

Also add `AiConfig.java` to `config/` folder (forces correct model name in Spring AI M6):
```java
@Configuration
public class AiConfig {
    @Value("${spring.ai.anthropic.api-key}")
    private String apiKey;

    @Bean @Primary
    public ChatModel primaryChatModel() {
        AnthropicApi api = new AnthropicApi(apiKey);
        return AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(AnthropicChatOptions.builder()
                        .model("claude-haiku-4-5-20251001")
                        .temperature(0.7)
                        .maxTokens(4096)
                        .build())
                .build();
    }
}
```

| Model | Cost | Quality |
|-------|------|---------|
| claude-haiku-4-5-20251001 | ~$0.001/query | ⭐⭐⭐⭐⭐ |
| claude-sonnet-4-5 | ~$0.005/query | ⭐⭐⭐⭐⭐⭐ |

---

### Option C — AWS Bedrock Claude (Enterprise)

For regulated environments — data stays within your AWS account (same as Kiro).
Requires AWS account with Bedrock access enabled.

```yaml
spring:
  ai:
    bedrock:
      aws:
        region: us-east-1
        access-key: ${AWS_ACCESS_KEY}
        secret-key: ${AWS_SECRET_KEY}
      anthropic:
        chat:
          model: anthropic.claude-3-5-sonnet-20241022-v2:0
    ollama:
      embedding:
        model: nomic-embed-text
```

> **Note:** Embeddings always use Ollama locally regardless of which chat model option you choose. Your Confluence content never goes to any API for embedding — only the retrieved text snippets go to the reasoning model.

---

## Step 5 — Configure Confluence

Edit `backend/src/main/resources/application.yml`:

```yaml
confluence:
  base-url: https://YOUR-SITE.atlassian.net
  username: your@email.com
  api-token: YOUR-API-TOKEN    # from id.atlassian.com/manage-profile/security/api-tokens
  space-key: YOUR-SPACE-KEY
```

---

## Step 6 — Start the Application

```bash
cd backend
mvn clean spring-boot:run
```

Maven automatically builds Angular first (via exec-maven-plugin), then starts Spring Boot.

Open http://localhost:8080

---

## Step 7 — Ingest Confluence

```bash
# Full sync (first time)
curl -X POST http://localhost:8080/api/ingest/confluence

# Incremental sync (only changed pages)
curl -X POST http://localhost:8080/api/ingest/confluence/incremental
```

Or use the sidebar buttons in the UI. Incremental sync also runs automatically at 2AM nightly.

---

## Project Structure

```
doc-assistant/
├── docker-compose.yml
├── init.sql
├── README.md
├── backend/
│   ├── pom.xml
│   └── src/main/java/com/confluence/docassistant/
│       ├── DocAssistantApplication.java
│       ├── agent/
│       │   ├── AgenticRAGOrchestrator.java   main pipeline coordinator
│       │   ├── PlannerAgent.java             intent + query generation
│       │   ├── HybridSearchService.java      vector + keyword + RRF
│       │   ├── MetadataFilter.java           targeted chunk retrieval
│       │   ├── ParentChildRetriever.java     L3 → L2 enrichment
│       │   ├── CrossEncoderReranker.java     relevance scoring
│       │   ├── ReflectionAgent.java          quality check + retry
│       │   ├── SynthesisAgent.java           structured answer generation
│       │   ├── ConversationContext.java      per-conversation state
│       │   ├── PageResolver.java             dynamic page detection
│       │   └── Chunk.java
│       ├── config/
│       │   ├── AppConfig.java               CORS + SPA fallback
│       │   ├── AiConfig.java                Anthropic primary model config
│       │   └── ConfluenceProperties.java
│       ├── controller/
│       │   ├── ChatController.java           POST /api/chat
│       │   ├── ChatHistoryController.java    GET/DELETE /api/history
│       │   ├── FeedbackController.java       POST /api/feedback
│       │   ├── AdminController.java          GET /api/admin/dashboard
│       │   ├── IngestionController.java      POST /api/ingest/*
│       │   └── HealthController.java         GET /api/health/ollama
│       ├── ingestion/
│       │   ├── HierarchicalChunker.java      L1/L2/L3 format-agnostic chunking
│       │   ├── HtmlContentParser.java        Jsoup table + code preservation
│       │   ├── SemanticEnricher.java         HyDE synonyms + hypothetical Q&As
│       │   ├── ConfluencePageFetcher.java
│       │   ├── ConfluenceDocumentBuilder.java
│       │   └── DiagramExtractor.java         LLaVA vision (optional)
│       ├── model/
│       │   ├── ConfluencePage.java
│       │   └── Feedback.java
│       └── service/
│           ├── ChatService.java              chat + history persistence
│           ├── ChatHistoryService.java       conversation + message storage
│           ├── FeedbackService.java          feedback storage + admin queries
│           ├── ConfluenceIngestionService.java
│           └── IncrementalSyncService.java   version-based incremental sync
└── frontend/
    └── src/app/chat/
        └── chat.component.ts               full UI
```

---

## API Reference

### Chat
```
POST /api/chat
Body:    { "message": "string", "conversationId": "string" }
Returns: { "response", "followUps", "confidence", "confidenceReason", "gaps", "conversationId" }
```

### History
```
GET    /api/history                   list conversations
GET    /api/history/{id}              get messages
DELETE /api/history/{id}              delete conversation
```

### Feedback
```
POST /api/feedback                    save 👍/👎
GET  /api/feedback/summary            total counts + positive rate
GET  /api/feedback/negative           all thumbs down
GET  /api/feedback/failing            questions that always fail
GET  /api/feedback/top-questions      most asked
```

### Admin
```
GET /api/admin/dashboard              all dashboard data
```

### Ingestion
```
POST /api/ingest/confluence           full sync
POST /api/ingest/confluence/incremental  changed pages only
POST /api/ingest/confluence/{pageId}  single page
```

---

## Chunking Strategy

| Layer | Content | Purpose |
|-------|---------|---------|
| L1 | Page summary + synonyms + hypothetical Q&As | Page-level matching |
| L2 | Full blocks — complete tables, lists, paragraphs | Section retrieval |
| L3 | Atomic facts — one row, one step, one sentence | Precise fact lookup |

Key rules:
- Tables are **never split** — all rows always in one L2 chunk
- Format-agnostic — works regardless of Confluence page structure
- TABLE chunks always fetched first to guarantee complete table data

---

## Pages Excluded From Ingestion

```java
// In ConfluenceIngestionService.java
private static final Set<String> SKIP_TITLES = Set.of(
    "Getting started in Confluence",
    "Overview",
    "Welcome to Confluence",
    "Confluence 101"
);
```

---

## Features

| Feature | Status |
|---------|--------|
| Hierarchical chunking L1/L2/L3 | ✅ |
| Hybrid search vector + keyword + RRF | ✅ |
| Agentic pipeline 8 best practices | ✅ |
| Confidence scoring HIGH/MEDIUM/LOW | ✅ |
| Documentation gap detection | ✅ |
| Follow-up question suggestions | ✅ |
| Clickable Confluence source links | ✅ |
| Implicit follow-up detection | ✅ |
| Per-conversation context (thread-safe) | ✅ |
| Chat history persistence | ✅ |
| Feedback system 👍👎 | ✅ |
| Admin dashboard | ✅ |
| Copy answer button | ✅ |
| Incremental sync nightly @2AM | ✅ |
| Boilerplate page filtering | ✅ |
| Claude API support | ✅ |
| Fully on-prem Ollama option | ✅ |
| Authentication / OIDC | 🔜 |
| Multi-tenancy | 🔜 |
| HTTPS / TLS | 🔜 |
| Input validation + rate limiting | 🔜 |

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `model not found` | Run `ollama pull <model-name>` |
| `UUID string too large` | Replace HierarchicalChunker — L1/L2 prefix in ID |
| `Two ChatModel beans found` | Add AiConfig.java with @Primary on Anthropic |
| `claude-3-5-sonnet-latest not found` | Set model in AiConfig.java programmatically |
| `credit balance too low` | Anthropic billing not synced — wait and retry |
| Getting Started polluting answers | Delete from vector_store by title |
| Answers missing last table row | Full sync to clear duplicate/old chunks |
| Angular not rebuilding | exec-maven-plugin missing from pom.xml |
| `build:prod` script missing | Add to frontend/package.json scripts |
| Compilation: illegal escape character | Use line-by-line filter instead of regex in Java |

---

## Environment

| Component | Technology | Location |
|-----------|-----------|----------|
| Chat reasoning | Claude Haiku (Anthropic) | Anthropic API |
| Embeddings | nomic-embed-text (Ollama) | Local — never leaves |
| Vector store | PgVector (PostgreSQL) | Local Docker |
| Backend | Spring Boot 3.3.5 + Spring AI 1.0.0-M6 | Local |
| Frontend | Angular 21 | Served from Spring Boot |
| Confluence | Atlassian Cloud | Your instance |