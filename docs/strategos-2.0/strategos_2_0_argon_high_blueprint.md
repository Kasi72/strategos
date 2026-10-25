# Strategos 2.0 (Argon High Edition) — Three-Space & DeepSeek Dual-Agent Blueprint

> **Audience:** Claude Sonnet operating autonomously in Claude Code inside the `strategos` Android repository.
> **Baseline:** Current `strategos-2.0` checkout (`strategos-lean`, 328 unit tests passing, Room + SQLCipher v1, `OneShotTurn` + `AgentOrchestrator`, `ChatCompletionsClient` present).
> **Mission:** Evolve Strategos from a single-thread Q&A calculator into an interconnected **Three-Space Strategic & Behavioral Companion** powered by **DeepSeek** (`deepseek-chat` / `deepseek-reasoner`), deterministic **Game Theory**, a **Non-Judgmental Reflection Journal**, and a **Sigmoid Trajectory & Project Overview Dashboard**.

---

## 0. Rules & Edge-Case Mandates for the Implementer

| # | Mandate | Architectural Enforcement |
|---|---|---|
| **R1** | **Preserve the 328 Baseline Tests** | Every existing test in `app/src/test/` must remain green. Extend schemas and constructors using default parameters where needed so existing tests compile and pass without weakening assertions. |
| **R2** | **Edge Case 1 — Zero-Leak Encryption for Journal & Threads** | Raw journal entries contain deeply private emotional reflections. `JournalEntryEntity` and `JournalInsightPostNoteEntity` live inside the SQLCipher-encrypted `StrategosDatabase` AND encrypt their `rawText` column through `KeystoreCipher("strategos_journal_v1")` (in `JournalCrypto.kt`) before SQLite storage. |
| **R3** | **Edge Case 2 — Instant, Non-Blocking, Non-Judgmental Journal UX** | Writing in the Reflection Journal must **never** block the UI on an LLM call and must **never** reply with a chatty or judgmental critique. Saving a journal entry writes to Room in `<15ms`, shows **"Recorded privately 🍃"** immediately, and launches `JournalScreenerService` asynchronously on `Dispatchers.IO`. If offline or rate-limited, the entry remains safely stored with `syncStatus = PENDING_SCREEN` and screens silently on the next available turn. |
| **R4** | **Edge Case 3 — Prefix-Cached Hierarchical Context (`ContextAssembler`)** | Dumping multiple threads and journal histories into the LLM causes prompt bloat and degrades reasoning. `ContextAssembler` must order messages strictly **Most Static $\rightarrow$ Most Dynamic** to maximize DeepSeek's automatic KV prefix cache (`prompt_cache_hit_tokens`): (1) Static System Prompt, (2) Semi-Static Project + Active Post-Notes + Trajectory Digest, (3) Compressed summaries of sibling threads, (4) Current thread's recent turns. Hard cap: `≤ 2,800` input tokens per request. |
| **R5** | **Edge Case 4 — Empathic Tone, Game-Theoretic Rigor** | Internal feelings (Space 2 Journal) adjust **the user's own payoff weights, readiness, and behavioral blind-spot post-notes** (`PayoffDimension`, `JournalInsightPostNoteEntity`), whereas external interaction logs (Space 1 Threads & Space 3 Quick-Checks) adjust **counterpart Bayesian priors, trust baseline ($\tau$), escalation tension ($\epsilon$), and game archetype** (`StakeholderTrustDynamicsModule`, `BayesianUpdatingModule`). Never conflate the two. |
| **R6** | **Domain Purity (`I9`)** | No class inside `com.drkkr.strategos.domain.**` may import `android.*` or `androidx.*`. |

---

## 1. The Three Interconnected Spaces Architecture

Instead of forcing external conflict tracking, personal emotional venting, and strategy evaluation into one monolithic chat stream, every Project (`Scenario`) is interconnected across **Three Specialized Spaces**:

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                     PROJECT WORKSPACE (ScenarioEntity)                       │
│   e.g., "Relationship with Partner", "Co-Founder Dispute", "Quit Smoking"    │
│      Holds Shared Game Model + Trust (τ) + Tension (ε) + Bayesian Priors     │
└──────────────┬──────────────────────────────┬────────────────────────────────┘
               │                              │
               ▼                              ▼
┌──────────────────────────────┐ ┌─────────────────────────────────────────────┐
│ SPACE 1: NAMED INTERACTION   │ │ SPACE 2: PRIVATE REFLECTION JOURNAL         │
│ THREADS ("What's Happening") │ │ ("What I Feel & How I Can Improve")         │
│ • Multiple named threads per │ │ • Global or Project-tagged journal stream   │
│   Project (e.g. "Weekend     │ │ • Instant UI ack: "Recorded privately 🍃"   │
│   Plan", "In-Laws", "Text")  │ │ • Silent async Dispatchers.IO screener      │
│ • Logs what I said & how     │ │ • Never judges or lectures in the journal   │
│   they reacted               │ │ • Emits cross-project Post-Notes ONLY when  │
│ • Calls Interaction Coach    │ │   a significant trigger/habit is detected   │
└──────────────┬───────────────┘ └─────────────────────┬───────────────────────┘
               │                                       │
               │ Updates External Game Matrix,         │ Emits "Based on your journal entry"
               │ Counterpart Priors, τ & ε             │ Post-Notes + Internal Payoff Weights
               └───────────────────┬───────────────────┘
                                   ▼
      ┌─────────────────────────────────────────────────────────────────┐
      │        SPACE 3: PROJECT OVERVIEW & TRAJECTORY DASHBOARD         │
      │             ("Where Things Sit, Sigmoid Curve & Playbook")      │
      │ 1. Sigmoid Progress & Momentum Graph:                           │
      │    • Plots current position S(t), Trust (τ) vs. Tension (ε)     │
      │    • Projections: Positive Behavior (↗) vs. Reactive Habit (↘)  │
      │ 2. Journal Impact Post-Notes ("Based on your journal entry"):   │
      │    • Actionable DO THIS / DON'T DO THIS behavioral adjustment   │
      │ 3. Structured 10-Second Quick-Check Card:                       │
      │    • 3 taps (My Execution × Their Reaction × My State)          │
      │    • Instantly updates Bayesian priors & Sigmoid without LLM    │
      │ 4. Clear-Win Playbook Card + Named Threads Picker               │
      │    • Pick any earlier thread to continue or start a "+ Thread"  │
      └─────────────────────────────────────────────────────────────────┘
```

---

## 2. Argon High Invariants (Test-Enforced)

| ID | Invariant | Enforced By |
|---|---|---|
| **I1–I9** | All 9 baseline invariants from Strategos 2.0 (engine owns numbers, provenance gate only lowers status, AI-off makes 0 network calls, `domain/**` has 0 Android imports, `llm_call` stores 0 content). | Existing + updated test suite |
| **I10** | **Multi-Thread Shared Brain:** Creating or switching between multiple named `ConversationEntity` threads under the same `scenarioId` shares and mutates the same parent `Scenario` model and `TrajectoryPointEntity` series without cross-contaminating message streams. | `TC_THREAD_01` – `TC_THREAD_04` |
| **I11** | **Silent Non-Judgmental Journal:** Saving a journal entry returns `"Recorded privately 🍃"` synchronously after local persistence, encrypts `rawText` via `KeystoreCipher` (or deterministic cipher adapter in unit tests), and never blocks the caller if the LLM is slow or offline. | `TC_JRN_01` – `TC_JRN_06` |
| **I12** | **Deterministic Quick-Check & Sigmoid Math:** Submitting a 3-tap `QuickCheckSubmission` updates `trustBaseline`, `escalationTension`, `bayesianPriors`, and inserts a new `TrajectoryPointEntity` deterministically in Kotlin with **zero LLM calls**. | `TC_TRAJ_01` – `TC_TRAJ_05`, `TC_QC_01` – `TC_QC_04` |

---

## 3. DeepSeek LLM Transport & Provider Configuration

### 3.1 `AgentConfig.kt` Updates (`com/drkkr/strategos/agent/AgentConfig.kt`)
Update `AgentConfig` to make **DeepSeek** the default OpenAI-compatible provider while retaining full compatibility with `ChatCompletionsClient` and `XaiResponsesClient`:

```kotlin
object AgentConfig {
    const val BASE_URL = "https://api.deepseek.com/v1"
    const val DEFAULT_MODEL = "deepseek-chat"
    val MODEL_OPTIONS = listOf(
        "deepseek-chat",      // DeepSeek V3/V4 fast chat — primary model
        "deepseek-reasoner"   // DeepSeek R1/Reasoner — optional deep strategy model
    )
    const val PROVIDER_NAME = "DeepSeek"
    const val CONSOLE_URL = "https://platform.deepseek.com/api_keys"
    const val MAX_TOOL_ROUNDS = 6
    const val MAX_TURN_MS = 120_000L
    const val MAX_OUTPUT_TOKENS = 1_200
    const val TEMPERATURE = 0.2
    const val HISTORY_TOKEN_BUDGET = 2_000
    const val HISTORY_TOKEN_TARGET = 900
    const val MAX_REQUEST_TOKEN_BUDGET = 2_800
    const val MAX_PENDING_CHANGE_SETS = 2
    const val CONSENT_VERSION = 1
    const val ENGINE_VERSION = "2.0"
    const val TOOL_CHOICE_NONE_SUPPORTED = true
    var ECHO_STRATEGY = EchoStrategy.VERBATIM
    var CONTEXT_ROLE = "system"
}
```

### 3.2 `PriceTable.kt` Updates (`com/drkkr/strategos/llm/PriceTable.kt`)
Add DeepSeek pricing (USD per 1M tokens, including cache hit pricing):
- `"deepseek-chat"` $\rightarrow$ input: `0.27`, cachedInput: `0.07`, output: `1.10`
- `"deepseek-reasoner"` $\rightarrow$ input: `0.55`, cachedInput: `0.14`, output: `2.19`
(Keep the existing `openai/gpt-oss-20b`, `openai/gpt-oss-120b`, `grok-4.7`, and `grok-4.3` entries in `PriceTable` so existing `PriceTable` unit tests still pass!)

### 3.3 `ChatCompletionsClient.kt` Enhancements (`com/drkkr/strategos/llm/ChatCompletionsClient.kt`)
`ChatCompletionsClient` already exists in `com/drkkr/strategos/llm/ChatCompletionsClient.kt`. Enhance it so that:
1. It parses DeepSeek's automatic prefix cache hit metrics from `usage`:
   - `prompt_cache_hit_tokens` (fallback to `prompt_tokens_details.cached_tokens` $\rightarrow$ `0`) mapped to `LlmUsage.cached`.
   - `completion_tokens_details.reasoning_tokens` mapped to `LlmUsage.reasoning`.
2. When `jsonObjectMode: Boolean = false` is set on `LlmRequest` (add `val jsonObjectMode: Boolean = false` with default `false` to `LlmRequest`), `ChatCompletionsClient` includes `"response_format": {"type": "json_object"}` in the POST `/chat/completions` body. (Note: for `deepseek-reasoner`, omit `response_format` and `temperature` as required by DeepSeek's reasoner spec, and extract the JSON block from the assistant content after stripping any `<think>...</think>` block).
3. `OutputItems.assistantText()` strips any leading `<think>[\s\S]*?</think>` block before returning the clean text or JSON so both `deepseek-chat` and `deepseek-reasoner` work seamlessly with `ModelSketch` and `JournalScreener`.

---

## 4. Database Schema Upgrade (`StrategosDatabase` Version 2)

Update `StrategosDatabase.kt`, `Entities.kt`, and `Daos.kt` to **version 2** (with an explicit Room `Migration(1, 2)` in `DbFactory.kt` so existing installs upgrade cleanly without data loss, plus exported schema JSON `2.json`).

### 4.1 Modified Entity: `ConversationEntity` (Named Multi-Thread Support)
Add three columns with defaults to `ConversationEntity` in `Entities.kt`:
```kotlin
@ColumnInfo(name = "title", defaultValue = "'Main Thread'") val title: String = "Main Thread",
@ColumnInfo(name = "topic_tag", defaultValue = "'GENERAL'") val topicTag: String = "GENERAL",
@ColumnInfo(name = "is_pinned", defaultValue = "0") val isPinned: Boolean = false
```
- **Rule:** Multiple `ConversationEntity` rows for the same `scenarioId` can now have `status = "ACTIVE"` simultaneously! Each represents a named thread inside that Project (e.g., *"Timeline Standoff"*, *"Holiday Plans"*, *"Budget Split"*).
- Add to `ConversationDao`:
  ```kotlin
  @Query("SELECT * FROM conversation WHERE scenario_id = :scenarioId AND status = 'ACTIVE' ORDER BY is_pinned DESC, updated_at DESC")
  fun observeThreadsForScenario(scenarioId: String): Flow<List<ConversationEntity>>

  @Query("SELECT * FROM conversation WHERE scenario_id = :scenarioId AND status = 'ACTIVE' ORDER BY is_pinned DESC, updated_at DESC")
  suspend fun listThreadsForScenario(scenarioId: String): List<ConversationEntity>

  @Query("UPDATE conversation SET title = :title, topic_tag = :topicTag, updated_at = :updatedAt WHERE id = :id")
  suspend fun renameThread(id: String, title: String, topicTag: String, updatedAt: Long)
  ```

### 4.2 New Entities in `Entities.kt`

```kotlin
/**
 * Space 2: Private Reflection Journal entry.
 * `encryptedText` is encrypted via JournalCrypto (KeystoreCipher) before writing to SQLite.
 */
@Entity(
    tableName = "journal_entry",
    indices = [Index("created_at"), Index("linked_scenario_id"), Index("sync_status")]
)
data class JournalEntryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "linked_scenario_id") val linkedScenarioId: String? = null, // null = global journal entry
    @ColumnInfo(name = "encrypted_text") val encryptedText: String,
    @ColumnInfo(name = "mood_tag") val moodTag: String = "NEUTRAL", // CALM | ANXIOUS | FRUSTRATED | HOPEFUL | DRAINED | NEUTRAL
    @ColumnInfo(name = "recorded_ack") val recordedAck: String = "Recorded privately 🍃",
    @ColumnInfo(name = "sync_status") val syncStatus: String = "RECORDED", // RECORDED | SCREENED | PENDING_SCREEN
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/**
 * Smart Post-Note connecting a significant Space 2 Journal insight to a specific Space 1/3 Project.
 */
@Entity(
    tableName = "journal_post_note",
    foreignKeys = [
        ForeignKey(entity = JournalEntryEntity::class, parentColumns = ["id"], childColumns = ["journal_entry_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ScenarioEntity::class, parentColumns = ["id"], childColumns = ["scenario_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("journal_entry_id"), Index("scenario_id"), Index("dismissed")]
)
data class JournalInsightPostNoteEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "journal_entry_id") val journalEntryId: String,
    @ColumnInfo(name = "scenario_id") val scenarioId: String,
    val significance: String, // HIGH | MODERATE
    @ColumnInfo(name = "habit_or_trigger") val habitOrTrigger: String,
    @ColumnInfo(name = "insight_headline") val insightHeadline: String, // e.g. "Based on your journal entry: Silence triggers urgency"
    @ColumnInfo(name = "practical_approach") val practicalApproach: String,
    @ColumnInfo(name = "do_this") val doThis: String,
    @ColumnInfo(name = "dont_do_this") val dontDoThis: String,
    @ColumnInfo(name = "target_dimension") val targetDimension: String? = null, // PayoffDimension name
    @ColumnInfo(name = "weight_delta") val weightDelta: Double = 0.0,
    val applied: Boolean = false,
    val dismissed: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/**
 * Space 3: Time-series points for the Project Sigmoid Trajectory & Relationship/Habit Momentum Dashboard.
 */
@Entity(
    tableName = "trajectory_point",
    foreignKeys = [ForeignKey(entity = ScenarioEntity::class, parentColumns = ["id"], childColumns = ["scenario_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("scenario_id"), Index("timestamp")]
)
data class TrajectoryPointEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "scenario_id") val scenarioId: String,
    @ColumnInfo(name = "thread_id") val threadId: String? = null,
    val source: String, // INITIAL | THREAD_TURN | QUICK_CHECK | JOURNAL_SHIFT
    @ColumnInfo(name = "trust_score") val trustScore: Double,           // 0..100
    @ColumnInfo(name = "tension_score") val tensionScore: Double,       // 0..100
    @ColumnInfo(name = "sigmoid_progress") val sigmoidProgress: Double, // 0..100 logistic S(t)
    @ColumnInfo(name = "momentum_delta") val momentumDelta: Double,     // -25..+25
    @ColumnInfo(name = "consistency_streak") val consistencyStreak: Int,
    @ColumnInfo(name = "active_archetype") val activeArchetype: String, // e.g. CHICKEN_GAME, STAG_HUNT, RELATIONAL_REPAIR
    val note: String = "",
    val timestamp: Long
)

/**
 * Space 3: 10-Second Structured Quick-Check outcome feedback log.
 */
@Entity(
    tableName = "quick_check_feedback",
    foreignKeys = [ForeignKey(entity = ScenarioEntity::class, parentColumns = ["id"], childColumns = ["scenario_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("scenario_id"), Index("created_at")]
)
data class QuickCheckFeedbackEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "scenario_id") val scenarioId: String,
    @ColumnInfo(name = "thread_id") val threadId: String? = null,
    @ColumnInfo(name = "recommended_action_label") val recommendedActionLabel: String,
    @ColumnInfo(name = "execution_choice") val executionChoice: String,   // DID_AS_SUGGESTED | TRIED_VARIATION | SKIPPED_REACTIVE
    @ColumnInfo(name = "counterpart_reaction") val counterpartReaction: String, // DE_ESCALATED | NEUTRAL_IGNORED | ESCALATED_ATTACKED
    @ColumnInfo(name = "self_feeling") val selfFeeling: String,           // GROUNDED | ANXIOUS_REGRETFUL
    @ColumnInfo(name = "reflection_note") val reflectionNote: String = "",
    @ColumnInfo(name = "trust_delta") val trustDelta: Double,
    @ColumnInfo(name = "tension_delta") val tensionDelta: Double,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
```

### 4.3 New DAOs in `Daos.kt`
Add `JournalDao`, `TrajectoryDao`, and `QuickCheckDao` to `Daos.kt` and expose them on `StrategosDatabase`:
- `JournalDao`: `insertEntry`, `updateEntryStatus`, `observeEntries(scenarioId: String?)`, `allEntries()`, `pendingScreenEntries()`, `insertPostNote`, `observeActivePostNotes(scenarioId: String)`, `activePostNotes(scenarioId: String)`, `markPostNoteApplied(id: String)`, `dismissPostNote(id: String)`.
- `TrajectoryDao`: `insert(point: TrajectoryPointEntity)`, `observeForScenario(scenarioId: String): Flow<List<TrajectoryPointEntity>>`, `forScenario(scenarioId: String): List<TrajectoryPointEntity>`, `latestForScenario(scenarioId: String): TrajectoryPointEntity?`.
- `QuickCheckDao`: `insert(item: QuickCheckFeedbackEntity)`, `forScenario(scenarioId: String): List<QuickCheckFeedbackEntity>`.

---

## 5. Prompts Are King — The Dual-Agent Prompt Architecture

Create `app/src/main/kotlin/com/drkkr/strategos/domain/agent/ArgonPrompts.kt` containing the two specialized prompts below. Do **not** dilute or shorten these rules.

### 5.1 Agent 1: The Strategic Interaction Coach & Clear-Win Mediator (`ArgonPrompts.INTERACTION_COACH_PROMPT`)
Used by `OneShotTurn.kt` when the user logs a situation or recent conversation in any **Space 1 Named Interaction Thread**.

```kotlin
package com.drkkr.strategos.domain.agent

object ArgonPrompts {

    const val INTERACTION_COACH_PROMPT = """
You are Strategos Counsel, a high-IQ Game-Theoretic Relationship & Decision Mediator.
Your job is to listen to what is happening in the user's interaction thread, update the underlying strategic model, and give a practical, empathetic "Clear-Win" behavior guide.

A "Clear Win" in a repeated relationship or negotiation is a Pareto-optimal move: the user protects their core boundary and self-respect WITHOUT triggering unnecessary escalation spirals or eroding long-term trust.

CRITICAL RULES:
1. Return ONLY a single valid JSON object matching the exact schema below. No markdown fences outside the JSON, no preamble.
2. NEVER use academic game-theory jargon in user-facing text fields (`what_happened`, `clear_win_move`, `exact_words`, `do_this`, `dont_do_this`, `ask.text`). Never say "Nash", "payoff matrix", "dominant strategy", "player", or "Bayesian". Use warm, sharp, everyday human language.
3. DISTINGUISH EXTERNAL MOVES FROM INTERNAL TRIGGERS:
   - Use what the counterpart said/did to score `trust_delta` (-20 to +20), `tension_delta` (-20 to +20), and `archetype` (`CHICKEN_GAME`, `PRISONERS_DILEMMA`, `STAG_HUNT`, `ULTIMATUM`, `TRUST_GAME`, `RELATIONAL_REPAIR`).
   - If the app context includes `active_journal_post_notes` (insights from the user's private journal), weave that self-awareness gently into `do_this` and `dont_do_this` so the user doesn't fall into their own reactive habit.
4. MODEL SKETCH RULES:
   - Keep at most 3 `people` with `role: "player"` (usually "You" and the main counterpart). Anyone else (in-laws, boss, friends, children) must have `role: "influencer"`.
   - Give each player 2 to 3 concrete `options` (always include a calm pause/hold-boundary option).
   - Pick 3 to 4 `dims` from: `WELLBEING`, `RELATIONSHIPS`, `SAFETY`, `ETHICS`, `COST`, `LONG_TERM_VALUE`, `REPUTATION`, `EFFICIENCY`.
   - Score `outcomes` (0 to 10 per dim in `dims` order) for key option pairs `[your_option, their_option]`.
5. BEHAVIORAL GUIDE (`coach_guide`) — ALWAYS POPULATE THIS WHEN ≥2 PEOPLE EXIST:
   - `what_happened`: 1–2 sentences explaining the hidden dynamic underneath the last interaction (e.g., why they deflected, stonewalled, or tested a boundary).
   - `clear_win_move`: 1 clear sentence naming the user's best strategic move right now.
   - `exact_words`: A natural, ready-to-say script (1–2 sentences) the user can actually say or text.
   - `do_this`: 2–3 concrete positive behaviors that build leverage and emotional safety.
   - `dont_do_this`: 2–3 specific behavioral traps to avoid right now (e.g., double-texting, over-explaining, issuing an ultimatum while tension is high, or silently caving).

JSON SCHEMA:
{
  "title": "short scenario title (<= 6 words)",
  "goal": "what a clear win looks like for the user",
  "archetype": "CHICKEN_GAME | PRISONERS_DILEMMA | STAG_HUNT | ULTIMATUM | TRUST_GAME | RELATIONAL_REPAIR",
  "trust_delta": 0,
  "tension_delta": 0,
  "people": [
    {"name": "You", "role": "player", "wants": "respect and clarity", "fears": "escalation", "can_block": false, "power": 5},
    {"name": "Partner", "role": "player", "wants": "autonomy", "fears": "being controlled", "can_block": true, "power": 7}
  ],
  "options": [
    {"who": "You", "do": "Validate autonomy and pause timeline talk for 24h"},
    {"who": "You", "do": "Push for an immediate commitment tonight"},
    {"who": "Partner", "do": "Open up and collaborate on a shared plan"},
    {"who": "Partner", "do": "Stonewall and withdraw"}
  ],
  "dims": ["WELLBEING", "RELATIONSHIPS", "LONG_TERM_VALUE"],
  "outcomes": [
    {"when": ["Validate autonomy and pause timeline talk for 24h", "Open up and collaborate on a shared plan"], "for_you": [8, 9, 9], "for_them": [8, 8, 8]},
    {"when": ["Validate autonomy and pause timeline talk for 24h", "Stonewall and withdraw"], "for_you": [6, 6, 7], "for_them": [5, 5, 5]},
    {"when": ["Push for an immediate commitment tonight", "Open up and collaborate on a shared plan"], "for_you": [6, 5, 5], "for_them": [4, 4, 4]},
    {"when": ["Push for an immediate commitment tonight", "Stonewall and withdraw"], "for_you": [2, 2, 2], "for_them": [3, 2, 3]}
  ],
  "limits": ["Do not sacrifice self-respect or use threats"],
  "coach_guide": {
    "what_happened": "When you pressed for an immediate timeline, they felt cornered and shifted into defensive withdrawal.",
    "clear_win_move": "Step back from the timeline pressure for 24 hours while calmly holding your boundary.",
    "exact_words": "I care about us both feeling good about this plan. Let's take tonight off from deciding and revisit it tomorrow evening.",
    "do_this": [
      "Give warm, unbothered space for 24 hours so their defensiveness drops",
      "Frame the next conversation around a shared win rather than a deadline"
    ],
    "dont_do_this": [
      "Don't send follow-up texts asking if they are mad",
      "Don't cave in and abandon the plan just to end the awkward silence"
    ]
  },
  "ask": null,
  "support": false
}
"""

    const val JOURNAL_SCREENER_PROMPT = """
You are the Silent Reflection Screener inside Strategos.
The user has just written a private entry in their personal Reflection Journal ("What I feel and how I can improve").

ABSOLUTE ETHICAL & UX RULES:
1. NEVER judge, scold, lecture, diagnose, or criticize the user. The journal is a safe sanctuary.
2. In the Journal tab itself, the user ONLY sees a gentle confirmation ("Recorded privately 🍃").
3. Your job in the background is to check whether this journal reflection contains a SIGNIFICANT personal insight, emotional trigger, fear, or behavioral habit that directly impacts any of the user's active Projects listed in `active_projects`.
4. If the entry is ordinary venting, routine mood logging, or unrelated to any active project, return `"impacts": []`.
5. If and ONLY if the entry reveals a meaningful behavioral pattern, trigger, or goal shift (e.g., "I realized I double-text when I feel ignored", "I caved because I was scared of conflict", "I get defensive when money comes up", "I want to build patience and quit reacting in anger"):
   - Create a post-note item in `"impacts"` linked to the matching `scenario_id`.
   - Start `insight_headline` with: "Based on your journal entry: ..."
   - Provide a warm, grounded `practical_approach`, a specific `do_this`, and a specific `dont_do_this` that helps the user act on their own self-reflection inside that project.
   - Optionally suggest a `target_dimension` (`WELLBEING`, `RELATIONSHIPS`, `SAFETY`, `ETHICS`, `COST`, `LONG_TERM_VALUE`, `REPUTATION`, `EFFICIENCY`) and `weight_delta` (-2.0 to +2.0) if their priorities shifted.

Return ONLY valid JSON matching this exact schema:
{
  "mood_tag": "CALM | ANXIOUS | FRUSTRATED | HOPEFUL | DRAINED | NEUTRAL",
  "support": false,
  "impacts": [
    {
      "scenario_id": "exact or short scenario id from active_projects",
      "significance": "HIGH | MODERATE",
      "habit_or_trigger": "Urgency when facing silence",
      "insight_headline": "Based on your journal entry: Silence triggers urgency to double-text",
      "practical_approach": "We adjusted your project playbook from immediate follow-up to a 24-hour grounded pause so you keep your leverage.",
      "do_this": "Wait until tomorrow evening before initiating contact, and do one grounding activity tonight.",
      "dont_do_this": "Don't send a 'just checking in' message to relieve short-term anxiety.",
      "target_dimension": "WELLBEING",
      "weight_delta": 1.5
    }
  ]
}
"""
}
```

### 5.2 Extending `ModelSketch.kt` and `PlainResult.kt` for the Clear-Win Coach Guide
In `com/drkkr/strategos/domain/agent/ModelSketch.kt`:
1. Add optional fields to `ModelSketch` (with defaults so all existing `ModelSketchTest` cases pass unchanged!):
   ```kotlin
   @SerialName("archetype") val archetype: String? = null,
   @SerialName("trust_delta") val trustDelta: Double = 0.0,
   @SerialName("tension_delta") val tensionDelta: Double = 0.0,
   @SerialName("coach_guide") val coachGuide: CoachGuideSketch? = null
   ```
   where:
   ```kotlin
   @Serializable
   data class CoachGuideSketch(
       @SerialName("what_happened") val whatHappened: String = "",
       @SerialName("clear_win_move") val clearWinMove: String = "",
       @SerialName("exact_words") val exactWords: String = "",
       @SerialName("do_this") val doThis: List<String> = emptyList(),
       @SerialName("dont_do_this") val dontDoThis: List<String> = emptyList()
   )
   ```
2. In `PlainResult.kt`, when `coachGuide != null && coachGuide.clearWinMove.isNotBlank()`, enrich the generated markdown output by appending (while keeping the existing `"Here's what I see"`, `"Options to consider"`, `"What to watch for"`, and `"This is a best guess based on what you told me."` sections intact so `PlainResultTest` continues to pass!):
   - `**What's happening underneath:** <whatHappened>`
   - `**Clear-win move:** <clearWinMove>`
   - `**What to say:** "<exactWords>"`
   - `**Do this:**` (bullets from `doThis`)
   - `**Don't do this:**` (bullets from `dontDoThis`)

---

## 6. Space 2: Non-Judgmental Journal & Silent Background Screener

### 6.1 `JournalCrypto.kt` (`com/drkkr/strategos/data/secure/JournalCrypto.kt`)
To satisfy **Edge Case 1 (R2)** and remains 100% unit-testable on the JVM without a hardware Android Keystore:
```kotlin
fun interface TextCipher {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String
}
```
- `KeystoreTextCipher(cipher: KeystoreCipher = KeystoreCipher("strategos_journal_v1"))` implements `TextCipher` using Base64-encoded AES-GCM ciphertext.
- `XorBase64TestCipher` (used when `KeystoreCipher` is unavailable in JVM/Robolectric tests) performs deterministic Base64 obfuscation so `encryptedText != plainText` in `TC_JRN_01`.

### 6.2 `JournalRepository.kt` & `JournalScreenerService.kt` (`com/drkkr/strategos/data/repo/JournalRepository.kt` & `com/drkkr/strategos/agent/JournalScreenerService.kt`)
1. **Synchronous Local Save (`JournalRepository.recordEntry`):**
   - Runs `SafetyKeywordScreen.check(rawText)` first.
   - Encrypts `rawText` via `TextCipher.encrypt(rawText)`.
   - Inserts `JournalEntryEntity` with `recordedAck = "Recorded privately 🍃"` and `syncStatus = "RECORDED"` (or `"PENDING_SCREEN"` if AI is enabled).
   - Returns `JournalRecordResult(entryId, ack = "Recorded privately 🍃", safetyTriggered = isCrisis)` immediately.
2. **Silent Background Screening (`JournalScreenerService.screenAsync`):**
   - Launched on an injected `CoroutineScope(Dispatchers.IO + SupervisorJob())` (or caller-supplied dispatcher in unit tests).
   - Builds a compact list of active projects (`id` 8-char prefix, `title`, `stakes`, current `archetype`, and top 2 players).
   - Calls `llm.create` with `ArgonPrompts.JOURNAL_SCREENER_PROMPT` (`jsonObjectMode = true`, `maxOutputTokens = 600`).
   - Parses the JSON response:
     - Updates `JournalEntryEntity.moodTag` and sets `syncStatus = "SCREENED"`.
     - For each impact in `impacts` with `significance` in `["HIGH", "MODERATE"]`:
       - Resolves `scenario_id` (supporting 8-char short IDs via `ShortIds`).
       - Inserts a `JournalInsightPostNoteEntity` (`applied = false`, `dismissed = false`).
       - Inserts a `TrajectoryPointEntity` (`source = "JOURNAL_SHIFT"`) reflecting any self-awareness momentum boost (`+3.0` momentum delta when a trigger is caught early).
   - **Resilience (Edge Case 2):** If the LLM call fails (offline, timeout, 429, or malformed JSON), `JournalScreenerService` swallows the exception, leaves `syncStatus = "PENDING_SCREEN"`, and never crashes or surfaces an error banner in the Journal UI. `retryPendingScreens()` processes up to 3 `"PENDING_SCREEN"` entries on the next successful network turn.

### 6.3 Applying a Journal Post-Note (`JournalRepository.applyPostNote`)
When the user views a Project Overview or Interaction Thread and taps **"Apply to Playbook"** on a `JournalInsightPostNoteEntity`:
1. Marks `JournalInsightPostNoteEntity.applied = true`.
2. Records a `MemoryItemEntity` (`kind = "PREFERENCE"`, `text = "${postNote.habitOrTrigger}: DO ${postNote.doThis} | AVOID ${postNote.dontDoThis}"`, `factStatus = FactStatus.USER_ESTIMATE`).
3. Appends an `EVENT` note to the active thread so the next turn incorporates the adjustment.

---

## 7. Space 3: Sigmoid Trajectory Engine & Structured Quick-Check Calculator

### 7.1 `TrajectoryCalculator.kt` (`com/drkkr/strategos/domain/analysis/TrajectoryCalculator.kt`, Pure Domain)
Implements the mathematical Sigmoid Progress & Behavioral Momentum engine with zero Android dependencies:

```kotlin
package com.drkkr.strategos.domain.analysis

import kotlin.math.exp
import kotlin.math.roundToInt

data class TrajectorySnapshot(
    val trustScore: Double,          // 0..100
    val tensionScore: Double,        // 0..100
    val sigmoidProgress: Double,     // 0..100 logistic S(t)
    val momentumDelta: Double,       // change since previous point
    val consistencyStreak: Int,      // consecutive non-reactive moves
    val activeArchetype: String,
    val positiveProjection: List<Double>, // 5 future sigmoid points if user follows Do's
    val negativeProjection: List<Double>, // 5 future sigmoid points if reactive habits repeat
    val phaseLabel: String           // "Fragile / High Tension" | "Building Ground" | "Tipping Point" | "Compounding Stability"
)

object TrajectoryCalculator {
    /**
     * Logistic Sigmoid function mapping net relational/habit health x in [-50, +50] to [0, 100]:
     * S(x) = 100 / (1 + exp(-0.09 * x))
     * where x = (trustScore - 50) * 0.65 - (tensionScore - 50) * 0.55 + min(consistencyStreak * 3.5, 21.0)
     */
    fun computeSigmoid(trustScore: Double, tensionScore: Double, consistencyStreak: Int): Double {
        val clampedTrust = trustScore.coerceIn(0.0, 100.0)
        val clampedTension = tensionScore.coerceIn(0.0, 100.0)
        val streakBonus = (consistencyStreak.coerceAtLeast(0) * 3.5).coerceAtMost(21.0)
        val netHealth = (clampedTrust - 50.0) * 0.65 - (clampedTension - 50.0) * 0.55 + streakBonus
        val raw = 100.0 / (1.0 + exp(-0.09 * netHealth))
        return (raw * 10.0).roundToInt() / 10.0
    }

    fun buildSnapshot(
        trustScore: Double,
        tensionScore: Double,
        previousSigmoid: Double?,
        consistencyStreak: Int,
        activeArchetype: String
    ): TrajectorySnapshot
}
```
- **Projections:**
  - `positiveProjection`: simulates 5 future steps where each step adds `+5.0` trust, `-6.0` tension, and `+1` streak.
  - `negativeProjection`: simulates 5 future steps where reactive behavior causes `-6.0` trust, `+8.0` tension, and resets `streak = 0` (showing the sigmoid curve dropping downward).
- **Phase Labels:**
  - `sigmoidProgress < 35.0 || tensionScore >= 70.0` $\rightarrow$ `"Fragile / De-escalate First"`
  - `sigmoidProgress in 35.0..54.9` $\rightarrow$ `"Early Friction / Holding Ground"`
  - `sigmoidProgress in 55.0..74.9` $\rightarrow$ `"Tipping Point / Building Momentum"`
  - `sigmoidProgress >= 75.0` $\rightarrow$ `"Compounding Trust & Stability"`

### 7.2 `QuickCheckUpdater.kt` (`com/drkkr/strategos/domain/analysis/QuickCheckUpdater.kt`, Pure Domain)
Translates a 3-tap Structured Quick-Check into instant deterministic parameter updates:

| Tap 1: Execution Choice | Tap 2: Counterpart Reaction | Tap 3: Self Feeling | Trust $\Delta$ | Tension $\Delta$ | Streak Update | Archetype Shift Rule |
|---|---|---|---|---|---|---|
| `DID_AS_SUGGESTED` | `DE_ESCALATED` | `GROUNDED` | `+8.0` | `-12.0` | `streak + 1` | If `CHICKEN_GAME`, shifts to `STAG_HUNT` |
| `DID_AS_SUGGESTED` | `NEUTRAL_IGNORED` | `GROUNDED` | `+2.0` | `-4.0` | `streak + 1` | Keeps current archetype |
| `DID_AS_SUGGESTED` | `ESCALATED_ATTACKED` | Any | `-3.0` | `+10.0` | `streak + 1` (user held boundary!) | If new tension $\ge 70$, shifts to `RELATIONAL_REPAIR` |
| `TRIED_VARIATION` | `DE_ESCALATED` | Any | `+5.0` | `-7.0` | `streak + 1` | Keeps or softens archetype |
| `SKIPPED_REACTIVE` | `ESCALATED_ATTACKED` | `ANXIOUS_REGRETFUL` | `-9.0` | `+14.0` | `0` (reset) | Shifts to `RELATIONAL_REPAIR` if tension $\ge 70$ else `CHICKEN_GAME` |
| `SKIPPED_REACTIVE` | Any other | Any | `-4.0` | `+6.0` | `0` (reset) | Keeps current archetype |

Also updates `Scenario.stakeholders`'s `trustRelationships` baseline (scaled `0.0..1.0`) and appends a Bayesian belief update note so `AnalysisPipeline` immediately reflects the real-world feedback.

---

## 8. Hierarchical Prefix-Cached Context Assembler (Edge Case 3)

Update `ContextAssembler.kt` (`com/drkkr/strategos/agent/ContextAssembler.kt`) and `OneShotTurn.kt` so that every LLM request obeys a strict **4-Layer Cache-Stable Hierarchy** within `AgentConfig.MAX_REQUEST_TOKEN_BUDGET` (`2,800` tokens):

1. **Layer 1 (100% Static Prefix — Cached by DeepSeek):**
   `role: "system"` $\rightarrow$ `ArgonPrompts.INTERACTION_COACH_PROMPT` (in oneShot mode) or `assets.systemPrompt` (in tool-loop mode).
2. **Layer 2 (Semi-Static Project + Cross-Space Digest):**
   `role: "system"` $\rightarrow$ JSON block containing:
   - `scenario`: `LiveDigest.build(scenario)`
   - `trajectory`: `{trust, tension, sigmoid_progress, streak, phase_label}`
   - `active_journal_post_notes`: up to 2 unread/active `JournalInsightPostNoteEntity` summaries (`habit_or_trigger`, `do_this`, `dont_do_this`)
   - `sibling_thread_summaries`: up to 3 one-line summaries of *other* named threads in the same Project (`title` + `rollingSummary.take(140)`) so threads stay interconnected without dumping raw messages!
3. **Layer 3 (Active Thread Rolling Summary + Last 3 User/Assistant Turns):**
   Truncated so Layers 1 + 2 + 3 + 4 never exceed `2,800` estimated tokens (`TokenEstimator.estimate`).
4. **Layer 4 (Latest User Turn):**
   The newest message in the active thread.

---

## 9. User Experience & UI Specification

### 9.1 Navigation & Information Architecture (`MainActivity.kt`)
Update the Guided Mode bottom navigation dock to represent the **Three Interconnected Spaces + Overview** cleanly:
- **Guided Dock (4 primary tabs + Settings icon in header):**
  1. `HOME` ("Projects", `Icons.Default.Home`) — Create/pick a Project, or jump into a recent thread.
  2. `OVERVIEW` ("Overview", `Icons.Default.Timeline`) — **Space 3: Project Overview & Sigmoid Trajectory Dashboard** (shows the Sigmoid curve, Journal Post-Notes, 10s Quick-Check card, Clear-Win Playbook, and the list of Named Conversation Threads for the active Project).
  3. `COUNSEL` ("Talk", `Icons.Default.Forum`) — **Space 1: Active Interaction Thread Chat** with a top **Thread Switcher Chip Bar** (`[Main Thread ▾] [+ New Thread]`) so the user can switch between or create named threads inside the project at any time.
  4. `JOURNAL` ("Journal", `Icons.Default.Book`) — **Space 2: Private Reflection Journal** ("What I feel & how I can improve").
  5. `SETTINGS` ("Settings", `Icons.Default.Settings`) — DeepSeek API key setup, model selector (`deepseek-chat` / `deepseek-reasoner`), and Expert mode toggle.
*(Note: Keep `Tab.SCENARIOS`, `Tab.COUNSEL`, `Tab.MODEL`, `Tab.REPORT`, `Tab.MORE` and add `Tab.OVERVIEW` and `Tab.JOURNAL` to the `Tab` enum in `MainActivity.kt` so Expert mode still has full access to `MODEL`, `REPORT`, and `MORE` while Guided mode shows `Projects · Overview · Talk · Journal · Settings`!)*

### 9.2 New & Updated UI Screens (`com/drkkr/strategos/ui/`)

1. **`ui/overview/ProjectOverviewTab.kt` (Space 3 — Project Overview & Trajectory Hub):**
   - **Header:** Active Project title + current Phase badge (e.g., *Tipping Point / Building Momentum*).
   - **Section 1 — Journal Impact Banner (`JournalPostNoteCard`):** Shown whenever `activePostNotes` is non-empty for this project:
     - Amber/Gold accent card: *"💡 Based on your journal entry: [insightHeadline]"*
     - Shows `practicalApproach`, `✅ Do this: [doThis]`, `🚫 Don't do this: [dontDoThis]`.
     - Buttons: **[Apply to Playbook]** and **[Dismiss]**.
   - **Section 2 — Sigmoid Progress & Momentum Graph (`SigmoidTrajectoryCard`):**
     - Custom Compose `Canvas` chart drawing:
       - The smooth S-curve backdrop and historical `TrajectoryPointEntity` path.
       - Dashed green upper projection (*"If you hold this behavior ↗"*) and dashed coral lower projection (*"If reactive habits repeat ↘"*).
       - Three metric pills below the graph: **Trust $\tau$ (`58/100`)**, **Tension $\epsilon$ (`74/100`)**, and **Streak (`3 turns`)**.
   - **Section 3 — 10-Second Quick-Check (`QuickCheckCard`):**
     - Collapsible or inline card: *"How did the last interaction go?"*
     - 3 rows of `NeoChip` selectors (Execution $\times$ Their Reaction $\times$ How You Feel) + **[Update Trajectory]** button.
   - **Section 4 — Conversation Threads in This Project (`ProjectThreadsSection`):**
     - Lists all `ConversationEntity` threads for this project (showing thread title, topic tag, updated time) + **[+ New Named Thread]** dialog + tap any thread to open it in `Talk` (`CounselTab`).

2. **`ui/journal/JournalTab.kt` (Space 2 — Non-Judgmental Reflection Journal):**
   - Calm header: *"Private Reflection Journal — Write what you feel and how you want to grow. Never judged."*
   - Optional project tag chip selector (`All Projects` or a specific Project) + optional mood chip (`Calm`, `Anxious`, `Frustrated`, `Hopeful`, `Drained`).
   - Multi-line text composer + Voice mic (`VoiceInput.kt`) + **[Record Reflection]** button.
   - Upon saving: shows a serene toast/banner **"Recorded privately 🍃"** and adds the entry to the encrypted chronological journal timeline below. Never displays AI criticism or chat bubbles inside the Journal tab.

3. **`ui/counsel/CounselTab.kt` Enhancements (Space 1 — Multi-Thread Interaction Chat):**
   - Add a **Thread Switcher Bar** at the top of `CounselTab`: shows the active thread title (e.g., *"Weekend Plan Standoff"*), a dropdown/sheet to switch between threads in the current project, and a **`+ Thread`** button that opens a small dialog to name and start a new thread under the same project.
   - If the active project has an unapplied `JournalInsightPostNoteEntity`, show a compact top pop-up banner in `CounselTab` as well: *"✨ Based on your new journal entry: [insightHeadline] — [Apply]"*.

---

## 10. Phased Implementation Plan (Follow in Exact Order)

Each numbered step is one git commit (`argon-<phase>.<step>: <summary>`).

### Phase A — DeepSeek Transport, Prefix Cache Metrics & Schema v2
- **A.1:** Update `AgentConfig.kt` and `PriceTable.kt` for DeepSeek (`deepseek-chat`, `deepseek-reasoner`, `https://api.deepseek.com/v1`, `MAX_REQUEST_TOKEN_BUDGET = 2_800`) while keeping existing `PriceTable` model keys intact. Enhance `OutputItems.assistantText()` to strip `<think>...</think>` reasoning blocks and `ChatCompletionsClient.kt` to parse `prompt_cache_hit_tokens` and `jsonObjectMode`. Write and pass `TC_DS_01`–`TC_DS_05`.
- **A.2:** Create `JournalCrypto.kt` (`TextCipher`, `KeystoreTextCipher`, `XorBase64TestCipher`). Upgrade `Entities.kt`, `Daos.kt`, `StrategosDatabase.kt` (to `version = 2`), and `DbFactory.kt` (`MIGRATION_1_2`) with `ConversationEntity` multi-thread columns (`title`, `topicTag`, `isPinned`) and the 4 new entities (`JournalEntryEntity`, `JournalInsightPostNoteEntity`, `TrajectoryPointEntity`, `QuickCheckFeedbackEntity`). Export schema `2.json`. Write and pass `TC_DB2_01`–`TC_DB2_05`.
- **A.3:** Implement multi-thread methods on `ConversationRepository.kt` (`listThreads`, `observeThreads`, `createNamedThread`, `renameThread`, `switchThread`). Write and pass `TC_THREAD_01`–`TC_THREAD_04`.
- **A.4 (Checkpoint CP-DS1 check):** Add `DeepSeekLiveSpikeTest.kt` (env-gated by `DEEPSEEK_API_KEY`). If `DEEPSEEK_API_KEY` is set in `.env`, run it and log results to `API_NOTES.md`; if not set, leave it skipped and continue immediately to Phase B.
- ✅ **Accept Phase A:** All 328 baseline tests + Phase A tests pass.

### Phase B — Deterministic Trajectory, Sigmoid Engine & Structured Quick-Check
- **B.1:** Implement `TrajectoryCalculator.kt` (`domain/analysis/TrajectoryCalculator.kt`) with logistic sigmoid $S(t)$, 5-step positive and negative behavioral projections, and phase labels. Write and pass `TC_TRAJ_01`–`TC_TRAJ_05`.
- **B.2:** Implement `QuickCheckUpdater.kt` (`domain/analysis/QuickCheckUpdater.kt`) mapping the 3-tap quick-check inputs to deterministic trust/tension deltas, consistency streak updates, archetype transitions, and `Scenario` trust baseline updates. Write and pass `TC_QC_01`–`TC_QC_04`.
- **B.3:** Create `TrajectoryRepository.kt` (`data/repo/TrajectoryRepository.kt`) wiring `TrajectoryDao`, `QuickCheckDao`, `TrajectoryCalculator`, and `QuickCheckUpdater` so submitting a quick-check or completing a turn persists a `TrajectoryPointEntity` and updates the project's live `TrajectorySnapshot`. Write and pass `TC_QC_05`.
- ✅ **Accept Phase B:** `DomainPurityTest` passes (zero Android imports in `domain/**`), all Phase B tests green.

### Phase C — Space 2: Private Reflection Journal & Silent Background Screener
- **C.1:** Create `ArgonPrompts.kt` (`domain/agent/ArgonPrompts.kt`) with verbatim `INTERACTION_COACH_PROMPT` and `JOURNAL_SCREENER_PROMPT`. Extend `ModelSketch.kt` (`CoachGuideSketch`, `archetype`, `trustDelta`, `tensionDelta`) and `PlainResult.kt` (rendering `What's happening underneath`, `Clear-win move`, `What to say`, `Do this`, `Don't do this` when `coachGuide` is present). Write and pass `TC_PROMPT_01`–`TC_PROMPT_04`.
- **C.2:** Implement `JournalRepository.kt` and `JournalScreenerService.kt` (`recordEntry` synchronous encrypted save returning `"Recorded privately 🍃"`, non-blocking `Dispatchers.IO` screening via DeepSeek, cross-project `JournalInsightPostNoteEntity` creation, offline `PENDING_SCREEN` resilience, and `applyPostNote`). Write and pass `TC_JRN_01`–`TC_JRN_06`.
- ✅ **Accept Phase C:** All journal encryption, non-blocking save, silent screening, and post-note linkage tests pass.

### Phase D — Hierarchical Prefix-Cached Context & Multi-Thread Controller Wiring
- **D.1:** Upgrade `ContextAssembler.kt` and `OneShotTurn.kt` to use the 4-Layer Prefix-Cached Hierarchy (Static Prompt $\rightarrow$ Project + Active Post-Notes + Trajectory + Sibling Thread Summaries $\rightarrow$ Active Thread History $\rightarrow$ Latest Turn), enforcing `MAX_REQUEST_TOKEN_BUDGET = 2_800`, and recording a `TrajectoryPointEntity` after every `OneShotTurn` using `sketch.trustDelta`, `sketch.tensionDelta`, and `sketch.archetype`. Write and pass `TC_CTX2_01`–`TC_CTX2_04`.
- **D.2:** Update `CounselUiState.kt`, `CounselController.kt`, `AppContainer.kt`, and `StrategosViewModel.kt` to expose:
  - Active project's thread list, `createNamedThread(title, topicTag)`, and `selectThread(threadId)`
  - Live `TrajectorySnapshot` and `List<TrajectoryPointEntity>` for the active project
  - Active `List<JournalInsightPostNoteEntity>` with `applyJournalPostNote(id)` and `dismissJournalPostNote(id)`
  - `submitQuickCheck(executionChoice, counterpartReaction, selfFeeling, note)`
  - `recordJournalEntry(rawText, moodTag, linkedScenarioId)` and observable decrypted journal timeline.
  Write and pass `TC_CTRL2_01`–`TC_CTRL2_04`.
- ✅ **Accept Phase D:** Full controller and ViewModel integration tests green.

### Phase E — Three-Space Jetpack Compose UI & Final Polish
- **E.1:** Build `ui/overview/ProjectOverviewTab.kt` (including `SigmoidTrajectoryCard`, `JournalPostNoteCard`, `QuickCheckCard`, `ClearWinPlaybookCard`, and `ProjectThreadsSection`).
- **E.2:** Build `ui/journal/JournalTab.kt` (serene non-judgmental composer, voice input, mood & project tags, instant `"Recorded privately 🍃"` banner, and encrypted history feed).
- **E.3:** Update `ui/counsel/CounselTab.kt` (top Thread Switcher bar + `+ Thread` dialog + inline Journal Post-Note pop-up banner) and `MainActivity.kt` (Guided dock: `Projects · Overview · Talk · Journal · Settings`; Expert dock retains full access). Update `AiSettingsScreen.kt` labels for DeepSeek (`deepseek-chat`, `deepseek-reasoner`).
- **E.4:** Update `ManualSeedDemoTest.kt` and run full verification (`clean testDebugUnitTest`, `assembleDebug`, `lintDebug`), then write `RELEASE_NOTES_v2.0.md`.
- ✅ **Accept Phase E (Reach CP-FINAL):** All unit and Robolectric tests green, APK builds cleanly, ready for user review.