# Strategos 2.0 (Argon High) — release notes

Branch `strategos-2.0`. Built from `strategos_2_0_argon_high_blueprint.md`; the spec files are the `strategos_2_0_argon_high_*` names, not the `*_v2.0` names the prompt uses.

## What was built
- **DeepSeek transport.** Defaults to `https://api.deepseek.com/v1`, `deepseek-chat` (optional `deepseek-reasoner`). The app now uses `ChatCompletionsClient`; `XaiResponsesClient` stays in the code but is no longer wired. Cache-hit tokens are read from `prompt_cache_hit_tokens`, JSON mode is `jsonObjectMode`, and a leading `<think>` block is stripped.
- **Three spaces.**
  - Space 1: several named threads per project (`ConversationEntity` gained title, topic tag and pin), with a switcher and a "+ Thread" dialog in Talk. Threads share one project model.
  - Space 2: an encrypted private journal. Saving is one local write that returns "Recorded privately 🍃"; a background screener (`JournalScreenerService`) may add a "Based on your journal entry" post-note to a project. Offline or malformed replies leave the entry `PENDING_SCREEN`, retried after the next successful turn.
  - Space 3: an Overview tab with a sigmoid trajectory chart (green and coral projections), post-note cards, a 3-tap quick-check, a playbook card and the thread list.
- **Dual-agent prompts** (`ArgonPrompts`): the interaction coach (clear-win move, exact words, do and don't) and the journal screener, verbatim from the blueprint.
- **Deterministic math** in `domain/analysis`: `TrajectoryCalculator` and `QuickCheckUpdater`. Quick-check makes no model call (I12).
- **Prefix-cached context**: `ContextAssembler` orders system prompt, project and trajectory digest, summary and last turns, newest message, and trims to 2,800 estimated tokens.
- **Room v2** with `MIGRATION_1_2` and exported `2.json`.

## Tests
382 unit tests, 0 failures, 7 skipped (env-gated live spikes). 328 baseline tests plus the Argon cases `TC_DS`, `TC_DB2`, `TC_THREAD`, `TC_TRAJ`, `TC_QC`, `TC_PROMPT`, `TC_JRN`, `TC_CTX2`, `TC_CTRL2` (every id in the catalog has a test). `clean :app:testDebugUnitTest`, `assembleDebug`, `lintDebug` and the androidTest compile all pass.

One existing test was changed with the user's approval: `AiSettingsTest.TC_MODEL_01` now expects `deepseek-chat` as the default model (its old Groq default cannot coexist with `TC_DS_01`).

## Known gaps and open questions
- **Live DeepSeek spike passed** (JSON mode, 640/842 cache-hit tokens, 831 ms; see `API_NOTES.md`). The full app flow and the coach prompt have not been run against DeepSeek yet; that is the phone checklist below.
- **No UI tests.** The Compose screens are compile-checked only.
- **Consent.** `CONSENT_VERSION` stays 1 (blueprint), so people who agreed to Groq are not asked again about DeepSeek or the journal being sent for screening. Bump it before release if that matters.
- `XaiLiveSpikeTest` still targets the Responses API through `AgentConfig.BASE_URL`, which is now DeepSeek. It is skipped without a key, and would fail if run.
- Prompt schema mismatch: the blueprint prompt writes options as `who`/`do` and outcomes as `when`/`for_you`/`for_them`; the parser accepts both forms (see `ModelSketch.kt`).
- `Stakeholder.trustBaseline` was added because the model had no numeric trust field.
- Journal `weight_delta` is stored on the note but not applied to payoff weights (blueprint §6.3 has no such step).
- Guided dock no longer has Summary; it is reached from Overview ("Full summary").
- `deepseek-flash` (from the curl example) is not in `MODEL_OPTIONS`.
- Open `BLUEPRINT-QUESTION` comments: see `grep -rn BLUEPRINT-QUESTION app/src/main`.

## Manual checks still needed
Run `TEST_CASES` section 6 (the DeepSeek checklist in the test case catalog) on a phone with a real key.
