# Strategos 2.0 (Argon High Edition) — Test Case Catalog

> Companion to `BLUEPRINT_v2.0.md`. Every automated case below must be implemented as a JUnit / Robolectric test method named with its exact `TC_*` prefix. All 328 existing baseline tests must also remain green.
>
> Run full suite: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && sh ./gradlew :app:testDebugUnitTest`

---

## 1. DeepSeek Transport, Reasoning Strip & Prefix Cache Metrics (Phase A.1)

| ID | Setup | Expected |
|---|---|---|
| `TC_DS_01_defaultConfigIsDeepSeek` | Inspect `AgentConfig.BASE_URL` and `AgentConfig.DEFAULT_MODEL` | `https://api.deepseek.com/v1` and `"deepseek-chat"`; `MODEL_OPTIONS` contains `"deepseek-chat"` and `"deepseek-reasoner"` |
| `TC_DS_02_chatCompletionsParsesCacheHitTokens` | MockWebServer returns `/chat/completions` 200 with `"usage":{"prompt_tokens":1000,"prompt_cache_hit_tokens":800,"completion_tokens":150}` | `LlmResult.Ok` has `usage.input == 1000`, `usage.cached == 800`, `usage.output == 150` |
| `TC_DS_03_jsonObjectModeHeaderAndBody` | Call `ChatCompletionsClient.create` with `LlmRequest(..., jsonObjectMode = true, model = "deepseek-chat")` | Request JSON body contains `"response_format":{"type":"json_object"}` |
| `TC_DS_04_reasonerStripsThinkTags` | Assistant message contains `"<think>\nLet me analyze the chicken game...\n</think>\n{\"title\":\"Weekend Plan\"}"` | `OutputItems.assistantText()` returns `"{\"title\":\"Weekend Plan\"}"` with `<think>` block stripped |
| `TC_DS_05_priceTableDeepSeekAndLegacy` | Compute cost for `1_000_000` input (`500_000` cached) + `100_000` output on `"deepseek-chat"`, and verify `"openai/gpt-oss-20b"` still resolves | Both return non-null, accurate USD estimates |

---

## 2. Room Database v2 Schema, Migration & Multi-Thread Storage (Phase A.2 & A.3)

| ID | Setup | Expected |
|---|---|---|
| `TC_DB2_01_scenarioCascadeDeletesJournalPostNotesAndTrajectory` | Insert a `ScenarioEntity`, 2 named `ConversationEntity` threads, 1 `JournalInsightPostNoteEntity`, 2 `TrajectoryPointEntity` rows, and 1 `QuickCheckFeedbackEntity`; delete the scenario | All child threads, post-notes, trajectory points, and quick-checks for that scenario are cascade-deleted; global `JournalEntryEntity` remains intact |
| `TC_DB2_02_journalCryptoEncryptsAtRest` | Encrypt `"I felt panicked when he didn't text back"` via `XorBase64TestCipher` and store in `JournalEntryEntity.encryptedText` | Raw column value in SQLite does NOT contain `"panicked"` or `"text back"`; decrypting restores the exact string |
| `TC_DB2_03_trajectorySeriesOrdering` | Insert 3 `TrajectoryPointEntity` rows at `t = 1000, 3000, 2000` | `TrajectoryDao.forScenario` returns them in chronological order (`1000, 2000, 3000`) and `latestForScenario` returns `t = 3000` |
| `TC_DB2_04_postNoteLifecycle` | Insert a `JournalInsightPostNoteEntity`; call `markPostNoteApplied` then `dismissPostNote` | `activePostNotes(scenarioId)` excludes dismissed notes; applied flag persists |
| `TC_DB2_05_migration1To2PreservesData` | Verify `DbFactory.MIGRATION_1_2` SQL statements execute cleanly on a v1 SQLite table set | Existing `conversation` rows gain `title = 'Main Thread'`, `topic_tag = 'GENERAL'`, `is_pinned = 0`; new tables exist |
| `TC_THREAD_01_createMultipleNamedThreadsPerProject` | Create Project `S1`; call `createNamedThread(S1, "Weekend Plan", "SCHEDULE")` and `createNamedThread(S1, "In-Laws Boundary", "FAMILY")` | `listThreadsForScenario(S1)` returns both active threads with their distinct titles and IDs |
| `TC_THREAD_02_isolatedMessagesSharedScenario` | Append messages to Thread A and Thread B under Project `S1` | `messages(threadA)` contains only Thread A messages; both threads read/write the same `S1` `ScenarioEntity` |
| `TC_THREAD_03_renameAndPinThread` | Rename Thread A to `"Resolved Weekend Plan"` and pin Thread B | `listThreadsForScenario(S1)` returns pinned Thread B first and shows updated title on Thread A |
| `TC_THREAD_04_legacyGetOrCreateActiveStillWorks` | Call `getOrCreateActive(S1, ...)` used by existing v2.0 tests | Returns the most recently updated active thread (or creates `"Main Thread"`), keeping all 328 existing tests green |

---

## 3. Sigmoid Trajectory & Structured Quick-Check Math (Phase B)

| ID | Setup | Expected |
|---|---|---|
| `TC_TRAJ_01_neutralBaselineIsMidpoint` | `TrajectoryCalculator.computeSigmoid(trustScore = 50.0, tensionScore = 50.0, consistencyStreak = 0)` | Returns `50.0` |
| `TC_TRAJ_02_positiveBehaviorIncreasesSigmoid` | Compare `(trust = 65, tension = 35, streak = 3)` vs. `(trust = 50, tension = 50, streak = 0)` | Sigmoid progress increases to `> 75.0` and phase label is `"Compounding Trust & Stability"` |
| `TC_TRAJ_03_highTensionTriggersFragilePhase` | `buildSnapshot(trustScore = 60.0, tensionScore = 78.0, previousSigmoid = 52.0, consistencyStreak = 0, activeArchetype = "CHICKEN_GAME")` | `phaseLabel` is `"Fragile / De-escalate First"`; `momentumDelta < 0` |
| `TC_TRAJ_04_projectionsDivergeMonotonically` | Inspect `positiveProjection` (5 points) and `negativeProjection` (5 points) from `buildSnapshot(55.0, 55.0, ...)` | Every step in `positiveProjection` increases; every step in `negativeProjection` decreases |
| `TC_TRAJ_05_clampingBounds` | Pass extreme values `(trust = 150.0, tension = -20.0, streak = 99)` and `(trust = -50.0, tension = 200.0, streak = -5)` | `sigmoidProgress` always stays within `[0.0, 100.0]`, never `NaN` or `Infinity` |
| `TC_QC_01_didAsSuggestedAndDeEscalated` | Run `QuickCheckUpdater` with `DID_AS_SUGGESTED`, `DE_ESCALATED`, `GROUNDED` on a `CHICKEN_GAME` at `(trust = 50, tension = 65, streak = 1)` | `trust == 58.0`, `tension == 53.0`, `streak == 2`, `activeArchetype == "STAG_HUNT"` |
| `TC_QC_02_heldBoundaryDespiteCounterpartEscalation` | Run `QuickCheckUpdater` with `DID_AS_SUGGESTED`, `ESCALATED_ATTACKED`, `GROUNDED` at `(trust = 55, tension = 65, streak = 2)` | Tension rises to `75.0` ($\ge 70$), `activeArchetype` shifts to `"RELATIONAL_REPAIR"`, and `streak` still increments to `3` because the user held their boundary |
| `TC_QC_03_skippedReactiveResetsStreak` | Run `QuickCheckUpdater` with `SKIPPED_REACTIVE`, `ESCALATED_ATTACKED`, `ANXIOUS_REGRETFUL` at `(trust = 60, tension = 50, streak = 4)` | `trust == 51.0`, `tension == 64.0`, `streak == 0` |
| `TC_QC_04_updatesScenarioTrustBaseline` | Apply `QuickCheckUpdater` to `Fx.loanFamily()` | Counterpart stakeholder's trust relationship score in `Scenario` reflects the updated normalized `trust / 100.0` |
| `TC_QC_05_repositoryPersistsQuickCheckAndTrajectoryPoint` | Call `TrajectoryRepository.submitQuickCheck(...)` with zero LLM calls queued in `FakeLlmClient` | `QuickCheckFeedbackEntity` and new `TrajectoryPointEntity` are saved in Room; `FakeLlmClient.requests` is empty (Invariant `I12`) |

---

## 4. Prompts, Clear-Win Coach Guide & Silent Journal Screener (Phase C)

| ID | Setup | Expected |
|---|---|---|
| `TC_PROMPT_01_sketchParsesCoachGuideAndDeltas` | Parse a JSON response containing `archetype`, `trust_delta`, `tension_delta`, and `coach_guide {what_happened, clear_win_move, exact_words, do_this, dont_do_this}` | `ModelSketch.parse` populates all `coachGuide` fields, `trustDelta`, `tensionDelta`, and `archetype` |
| `TC_PROMPT_02_plainResultIncludesClearWinDoAndDont` | Build `PlainResult` from a scenario + report + `CoachGuideSketch` | Output contains `"What's happening underneath:"`, `"Clear-win move:"`, `"What to say:"`, `"Do this:"`, and `"Don't do this:"` alongside `"Options to consider"` |
| `TC_PROMPT_03_backwardCompatibleSketchWithoutCoachGuide` | Parse a legacy v2.0 sketch JSON that has NO `coach_guide` or `trust_delta` fields | Parses cleanly with defaults (`coachGuide = null`, `trustDelta = 0.0`); all existing `ModelSketchTest` cases pass |
| `TC_PROMPT_04_noBannedJargonInPrompts` | Verify `ArgonPrompts.INTERACTION_COACH_PROMPT` and `JOURNAL_SCREENER_PROMPT` | Explicitly forbid outputting `"Nash"`, `"payoff matrix"`, `"dominant strategy"` to the user and forbid judging the user in the journal |
| `TC_JRN_01_recordEntryIsInstantAndEncrypted` | Call `JournalRepository.recordEntry("I panicked and wanted to text him 5 times", "ANXIOUS", scenarioId)` | Returns `"Recorded privately 🍃"` immediately; stored `encryptedText` in DB is encrypted; `observeEntries` decrypts it for display |
| `TC_JRN_02_silentScreenerCreatesPostNoteOnHighSignificance` | `FakeLlmClient` returns a `JOURNAL_SCREENER_PROMPT` JSON with 1 `HIGH` impact for `scenarioId` | Background screener inserts `JournalInsightPostNoteEntity` with `"Based on your journal entry: ..."` headline, `doThis`, and `dontDoThis`; updates entry `syncStatus = "SCREENED"` |
| `TC_JRN_03_ordinaryVentingCreatesZeroPostNotes` | `FakeLlmClient` returns `"impacts": []` for `"Tired after a long commute today"` | Entry is marked `"SCREENED"`; `activePostNotes(scenarioId)` is empty; no pop-up is shown |
| `TC_JRN_04_offlineOrErrorLeavesEntrySafeAsPendingScreen` | `FakeLlmClient` returns `networkDown()` when recording a journal entry | `recordEntry` still succeeds and returns `"Recorded privately 🍃"`; entry has `syncStatus = "PENDING_SCREEN"`; no error is thrown |
| `TC_JRN_05_retryPendingScreensRecoversWhenOnline` | Enqueue a valid screener reply after `TC_JRN_04` and call `retryPendingScreens()` | The pending entry transitions to `"SCREENED"` and its post-note is created |
| `TC_JRN_06_applyPostNoteUpdatesMemoryAndMarksApplied` | Call `JournalRepository.applyPostNote(postNoteId)` | `postNote.applied == true` and a `PREFERENCE` item with the `doThis` / `dontDoThis` guidance is added to `MemoryRepository` for that project |

---

## 5. Prefix-Cached Context Assembler & Multi-Thread Controller (Phase D)

| ID | Setup | Expected |
|---|---|---|
| `TC_CTX2_01_prefixOrderStaticFirstDynamicLast` | Build context for Thread A when Project has 1 active Journal Post-Note, a Trajectory snapshot, and a sibling Thread B with a summary | Message 0 is static system prompt; Message 1 is project + trajectory + post-note + sibling thread summary; final message is the newest user input |
| `TC_CTX2_02_enforcesRequestTokenBudget` | Seed a thread with 30 long messages and 3 sibling threads | `TokenEstimator.estimate` of the assembled request is `<= AgentConfig.MAX_REQUEST_TOKEN_BUDGET` (`2,800`) |
| `TC_CTX2_03_oneShotTurnUpdatesTrajectoryAndCoachGuide` | Send a user message in `OneShotTurn` where `FakeLlmClient` returns a sketch with `trust_delta = 6`, `tension_delta = -8`, `archetype = "STAG_HUNT"`, and `coach_guide` | Scenario updates, a new `TrajectoryPointEntity` (`source = "THREAD_TURN"`) is saved with updated trust/tension/sigmoid, and the assistant reply includes the `coach_guide` Do's and Don'ts |
| `TC_CTX2_04_journalSafetyKeywordShowsSupportCardImmediately` | Call `recordJournalEntry` with a crisis phrase while `FakeLlmClient` is offline | `safetyTriggered == true` immediately before any network call |
| `TC_CTRL2_01_createAndSwitchNamedThreadsInController` | In `CounselController`, create `"Holiday Boundary"` thread, send a message, then switch back to `"Main Thread"` | `CounselUiState.threads` lists both threads; switching updates `CounselUiState.items` to the selected thread's messages while keeping `activeScenario` shared |
| `TC_CTRL2_02_overviewStateExposesTrajectoryAndPostNotes` | Record a high-significance journal entry and a quick-check for the active project | `CounselUiState.trajectory` reflects the updated sigmoid snapshot and `CounselUiState.postNotes` contains the unread `"Based on your journal entry"` card |
| `TC_CTRL2_03_applyPostNoteFromController` | Call `counsel.applyJournalPostNote(noteId)` | Post-note is marked applied, added to project memory, and included in the next turn's context |
| `TC_CTRL2_04_submitQuickCheckFromController` | Call `counsel.submitQuickCheck("DID_AS_SUGGESTED", "DE_ESCALATED", "GROUNDED", "Went well")` | Updates `CounselUiState.trajectory` immediately with zero LLM requests |

---

## 6. Manual End-to-End Checklist with DeepSeek API Key (`CP-FINAL`)

1. [ ] **Setup:** Open **Settings**, paste your DeepSeek API key (`sk-...`), tap **Save and connect**, and confirm status shows **Connected** with `deepseek-chat`.
2. [ ] **Space 1 (Named Project & Thread):** Create a relationship project, log a real conversation in `"Main Thread"`, and verify the reply gives:
   - *What's happening underneath*
   - *Clear-win move* & *What to say*
   - *Do this* & *Don't do this*
   Create a second named thread (`+ Thread` $\rightarrow$ *"Weekend Plan"*) and verify you can switch between both threads seamlessly.
3. [ ] **Space 2 (Non-Judgmental Journal):** Open the **Journal** tab, write an honest reflection about an emotional trigger (e.g., *"I hate when they go silent, it makes me want to send 5 angry texts"*), tap **Record Reflection**, and verify it immediately says **"Recorded privately 🍃"** without lecturing you.
4. [ ] **Space 3 (Overview, Post-Note & Sigmoid Graph):** Open the **Overview** tab for your project and verify:
   - The **"Based on your journal entry"** post-note card appears at the top with a practical Do / Don't adjustment.
   - The **Sigmoid Trajectory Graph** renders your current position along the S-curve with green positive (↗) and red reactive (↘) projection lines.
   - Tapping the **10-Second Quick-Check** chips and hitting **Update Trajectory** immediately shifts your Trust, Tension, and Sigmoid curve.
   - Tapping any earlier thread in the **Conversation Threads** list jumps straight back into that chat.