# STRATEGOS SDD v4.0 — Local-first Web + Graphiti Memory + MCP Platform

Specification-driven development document. The spec (sections 1–6) is as agreed on 20 Nov 2026. Section 7 records how it was
built and where the build differs from the text.

## 1. Objective and architectural mandates

A local-first, server-ready, LLM-agnostic strategic companion and game-theory simulation platform on the existing Kotlin/JVM
Strategos R0–R5 engine (`com.drkkr.strategos.domain` and `framework`).

- **Single source of truth.** One Kotlin/JVM service and storage layer serve the browser/mobile web UI (HTTP/JSON) and the MCP
  server (stdio for Claude Desktop/Code, Streamable HTTP for remote clients).
- **Engine-first determinism, never bypassed.** Every turn runs the three-stage pipeline:
  1. **Call 1, extractor and intent router.** Players, options tagged `repair` / `boundary` / `reactive`, categorical cell
     outcomes, observed reactions, and the intent (standard, what-if, compare, top options, counterpart simulation, plan ahead).
     No advice.
  2. **Stage 2, deterministic Kotlin engine.** Payoffs from categorical rubrics, Bayesian updates of the counterpart's
     responsiveness priors, and the R0–R5 solvers (GameIR, mixed Nash, IEDS, archetype projection, Shapley, mechanism design,
     domain-gated relational repair, inferred stress test).
  3. **Call 2, memory-aware companion coach.** Gets the user turn, retrieved temporal memory and the `EngineDirective`. Calm
     grounding without lecturing, the engine's options and trade-offs stated clearly, never re-asks an answered fact, never
     shows internal ids or audit strings.
- **Zero re-architecture from localhost to server.** Storage, graph memory, LLM providers and auth/CORS are interfaces and
  environment configuration.
- **Day-1 LLM simplicity, LLM-agnostic.** DeepSeek (`deepseek-chat`) by default, with a static system prefix for KV cache hits,
  behind an `LlmProvider` interface.

## 2. Architecture

```
Clients: web/mobile browser UI  ·  Claude Desktop / Claude Code / remote MCP clients
              │ REST (HTTP/JSON)                         │ MCP (stdio / Streamable HTTP)
              ▼                                          ▼
Strategos JVM server (Ktor, localhost:8080 → Docker on a server)
  Orchestration: TurnPipeline · intent routing · context assembly · coach guard
  Engine (unchanged, compiled from app/src/main/kotlin/{domain,framework})
  Ports: MemoryService (GraphitiMemoryAdapter + InMemoryTemporalFallback) · LlmProvider (DeepSeek, any OpenAI-compatible)
              ▼
Persistence: Graphiti + FalkorDB (temporal episodes; Voyage AI embeddings) · SQLite locally / Postgres on a server (projects, threads, turns, journal)
```

## 3. Data and memory

### 3.1 Entities
- **Project**: id, title, domainType (PERSONAL_RELATIONSHIP, COMMERCIAL_NEGOTIATION, COALITION_ORG, GENERAL), the authoritative
  scenario (with negotiation context and Bayesian priors), trust, tension, timestamps, archived.
- **TopicThread**: id, projectId, title, rollingSummary (SITUATION, ENGINE_ADVISED, USER_TRIED, OPEN), turn count, last active.
- **TurnRecord**: id, threadId, projectId, turnIndex, userMessage, extracted sketch, engine directive, reply, trust, tension, time.
- **JournalEntry**: id, projectId?, content (encrypted at rest when `JOURNAL_ENCRYPTION_KEY` is set), affected projects,
  Do / Don't post-note, significant-shift flag, createdAt.

### 3.2 Temporal memory (`MemoryService`)
- Namespaces: `strategos_proj_<projectId>` per project, `strategos_journal_<userId>` for reflections.
- **Write (after the reply, never blocking):** each turn is an episode (`thread_<threadId>_turn_<n>`, body, source, reference
  time) with structured facts (who wants/fears what, last reaction, what was advised). Every 6 turns the thread's rolling
  summary is updated.
- **Read (before Call 1 and Call 2):** last 4 turns, the rolling summary, the current fact ledger for the people in the scenario
  plus facts and episodes relevant to the message, superseded facts labelled as no longer true, and the scenario state (known
  facts, walk-away, priors, trust/tension).

### 3.3 Companion contract (Call 2)
System message 1 is static (prefix cache). Grounded reassurance first; the engine's move as concrete action and exact words,
within the walk-away; continuity from memory without re-asking; traps from `blockedOrDominatedMoves`.

## 4. API and MCP

### 4.1 REST
| Method | Path | What it does |
|---|---|---|
| GET | `/api/health` | engine, database, memory and provider status |
| GET/POST | `/api/projects` | list / create |
| GET/PATCH/DELETE | `/api/projects/{id}` | state (scenario digest, facts, priors, scored options, post-notes, threads) / rename, archive / delete |
| GET/POST | `/api/projects/{id}/threads` | list / create named threads |
| GET/POST | `/api/threads/{threadId}/messages` | history / run a turn (three stages; memory written in the background) |
| GET | `/api/threads/{threadId}/context` | rolling summary, recent turns, retrieved memory |
| POST | `/api/threads/{threadId}/quick-check` | outcome feedback → Bayesian priors + an outcome episode |
| GET | `/api/memory/search` | temporal fact search |
| GET/POST | `/api/journal` | list / save ("Recorded privately." at once; screening after) |
| GET/PUT | `/api/settings/llm` | provider, model, base URL, key (key held in memory only) |

### 4.2 MCP tools
`list_projects`, `create_project`, `get_project_state`, `list_threads`, `open_thread_context`, `search_long_term_memory`,
`log_interaction_and_analyze`, `run_what_if`, `compare_moves`, `simulate_multi_step`, `log_outcome_feedback`,
`log_journal_reflection`. Same service methods as the REST API.

## 5. Acceptance tests (`server/src/test/.../SddAcceptanceTest.kt`)
| ID | Check | Pass criteria |
|---|---|---|
| SDD-01 | Engine determinism | A reactive move with short-term relief but a ruptured bond scores below repair and boundary; never recommended. |
| SDD-02 | Month-long recall | A week-1 fear ("terrified of missing Q3 payroll") survives 20+ noise turns and reaches Call 2 in week 4 as settled. |
| SDD-03 | Temporal invalidation | A week-3 stance supersedes the week-1 one; the old one is marked replaced. |
| SDD-04 | Bayesian shift | Three repairs met with withdrawal drop `probSoftensOnRepair` below 0.40 and flip the pick from repair to boundary. |
| SDD-05 | Domain and floor gating | B2B price talk (walk-away 27L, target 29L): no bond warning, pick ≥ 27L, no raw ids. |
| SDD-06 | Journal post-note | "Recorded privately." at once; a Do / Don't lands on the commercial project. |
| SDD-07 | Provider swap | DeepSeek swapped for another OpenAI-compatible adapter; a turn succeeds with no other change. |
| SDD-08 | MCP parity | A turn logged over MCP is the same turn, directive and state over HTTP. |

## 6. Phases
1. Localhost JVM backend, memory and DeepSeek pipeline. 2. Web/mobile UI and MCP. 3. Server deployment and more providers.

## 7. Implementation notes (20 Nov 2026)

**Built:** `server/` (Ktor), `web/` (React + TypeScript + Vite), `graphiti-service/` (Python, graphiti-core 0.31.2),
`docker-compose.yml`, `.env.example` additions.

Where the build follows the codebase rather than the letter of the spec:
- **The engine is not copied.** `:server` compiles `app/src/main/kotlin/{domain,framework}` directly (they import nothing from
  Android), so the app and the server always run the same solvers.
- **Categorical payoffs already existed.** Call 1 labels every cell per dimension (`++` … `--`, ArgonPrompts rule 4) and Kotlin
  turns labels into numbers, applies the shadow-of-the-future cap to reactive moves and the Bayesian nudge. The server uses the
  app's extraction prompt unchanged; `DeterministicPayoffCalculator` reads the stored result back as one net score per option.
- **Stage 2 mirrors the app's turn** (`OneShotTurn.engineGroundedReply`), step for step. Not yet ported: Power Mode, the
  red-team challenge, the cross-project matcher and the plan-ahead close. Long term, the turn logic should move into a shared
  Android-free module so the two can't drift.
- **Call 2 uses the app's coach prompt and guard** (implements the engine's move, no named laws, walk-away kept private,
  placeholders stripped); an unusable reply shows the engine's own text.
- **Graphiti runs behind a small service** (`graphiti-service/app.py`) because the stock Graphiti REST image can't split the LLM
  (DeepSeek) from the embedder. Embeddings: Voyage AI (`voyage-4-lite`, 1024 dims; chosen 20 Nov for server use), with a local
  Ollama model as an option (`EMBEDDING_PROVIDER=ollama`). The adapter always also writes the deterministic fact ledger
  (`InMemoryTemporalFallback`, persisted in SQLite), and reads merge both; if Graphiti is down, the ledger answers alone.
- **MCP is implemented directly on JSON-RPC 2.0** (protocol 2025-06-18): the official Kotlin SDK needs Kotlin 2, and the app is on
  Kotlin 1.9.24.
- **Persistence is plain JDBC** (no ORM): the same SQL runs on SQLite and Postgres.
- **The API key is never written to disk** (CLAUDE.md): from the environment, or set at runtime in Settings, held in memory.
- **Engine fix found by SDD-05:** the commitment ladder sent raw action ids to the coach (normal-form games). It now sends the
  moves' labels (`GameModelService`). This also fixes the Android app.
