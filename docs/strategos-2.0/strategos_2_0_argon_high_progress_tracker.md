# Strategos 2.0 (Argon High Edition) — Implementation Progress

Branch: `strategos-2.0`
Architecture Specification: `docs/strategos-2.0/BLUEPRINT_v2.0.md`
Test Catalog: `docs/strategos-2.0/TEST_CASES_v2.0.md`
Execution Prompt: `docs/strategos-2.0/SONNET_PROMPT_v2.0.md`

## Baseline Status (Pre-Argon High Upgrade)
- **Status:** GREEN (328 unit tests passing, 0 failures, 1 skipped env-gated live spike).
- **Toolchain:**
  - `export JAVA_HOME=/opt/homebrew/opt/openjdk@17`
  - Run Gradle via `sh ./gradlew :app:testDebugUnitTest` and `sh ./gradlew :app:assembleDebug`.

## Next Step to Execute
👉 **Start at Step `A.4`** (env-gated DeepSeek live spike).

---

## Phased Step Tracker

| Step | Phase | Description | Status | Tests Added | Notes / BLUEPRINT-QUESTIONs |
|---|---|---|---|---|---|
| **A.1** | Phase A | DeepSeek config, price table, `<think>` strip, `prompt_cache_hit_tokens` & `jsonObjectMode` (`TC_DS_01`–`05`) | done | 7 | `DeepSeekTransportTest`. User approved editing `AiSettingsTest.TC_MODEL_01` (default model moved to deepseek-chat). gpt-oss ids kept in `MODEL_OPTIONS`. Spec files are the `strategos_2_0_argon_high_*` names. |
| **A.2** | Phase A | `JournalCrypto` (`TextCipher`) + Room `StrategosDatabase` v2 entities, DAOs & `MIGRATION_1_2` (`TC_DB2_01`–`05`) | done | 5 | `JournalDbTest`; schema `2.json` exported; `TextCipher` is a plain interface (BLUEPRINT-QUESTION: `fun interface` can't have 2 methods). |
| **A.3** | Phase A | Named multi-thread support in `ConversationRepository` (`TC_THREAD_01`–`04`) | done | 4 | `ThreadTest`; `switchThread` bumps `updatedAt` above the newest sibling so it wins `getOrCreateActive`. |
| **A.4** | Phase A | Env-gated `DeepSeekLiveSpikeTest` (`CP-DS1` check) | pending | 0 | If `DEEPSEEK_API_KEY` is absent in `.env`, leave gated and continue to Phase B. |
| **B.1** | Phase B | Pure domain `TrajectoryCalculator` with Sigmoid $S(t)$ & +/- projections (`TC_TRAJ_01`–`05`) | pending | 0 | Must have zero `android.*` imports (`DomainPurityTest`). |
| **B.2** | Phase B | Pure domain `QuickCheckUpdater` 3-tap deterministic math (`TC_QC_01`–`04`) | pending | 0 | Updates trust, tension, streak, archetype, and `Scenario` trust baseline. |
| **B.3** | Phase B | `TrajectoryRepository` wiring DAOs + calculators (`TC_QC_05`) | pending | 0 | Zero LLM calls on quick-check submission (`I12`). |
| **C.1** | Phase C | `ArgonPrompts` (`INTERACTION_COACH_PROMPT` + `JOURNAL_SCREENER_PROMPT`), `CoachGuideSketch`, & `PlainResult` enrichment (`TC_PROMPT_01`–`04`) | pending | 0 | Preserve existing `PlainResult` sections so `PlainResultTest` stays green. |
| **C.2** | Phase C | `JournalRepository` & `JournalScreenerService` instant encrypted save + async `Dispatchers.IO` screener + post-notes (`TC_JRN_01`–`06`) | pending | 0 | Instant `"Recorded privately 🍃"`, resilient `PENDING_SCREEN` fallback when offline. |
| **D.1** | Phase D | 4-Layer Prefix-Cached `ContextAssembler` + `OneShotTurn` trajectory & post-note integration (`TC_CTX2_01`–`04`) | pending | 0 | Enforce `MAX_REQUEST_TOKEN_BUDGET = 2_800`. |
| **D.2** | Phase D | `CounselUiState`, `CounselController`, `AppContainer` & `StrategosViewModel` wiring for threads, journal, post-notes & trajectory (`TC_CTRL2_01`–`04`) | pending | 0 | Expose reactive flows for all Three Spaces. |
| **E.1** | Phase E | Space 3 UI: `ProjectOverviewTab` (`SigmoidTrajectoryCard`, `JournalPostNoteCard`, `QuickCheckCard`, `ProjectThreadsSection`) | pending | 0 | Custom Compose Canvas S-curve with green/coral projections. |
| **E.2** | Phase E | Space 2 UI: `JournalTab` (non-judgmental composer, voice input, instant ack banner, encrypted timeline) | pending | 0 | Never show judgmental chat bubbles in Journal tab. |
| **E.3** | Phase E | Space 1 UI: `CounselTab` Thread Switcher bar + `+ Thread` dialog + Post-Note banner + `MainActivity` Guided dock & DeepSeek settings | pending | 0 | Guided dock: `Projects · Overview · Talk · Journal · Settings`. |
| **E.4** | Phase E | Update `ManualSeedDemoTest`, run full clean test/lint/build verification & write `RELEASE_NOTES_v2.0.md` (`CP-FINAL`) | pending | 0 | Stop at `CP-FINAL` and present summary to user. |