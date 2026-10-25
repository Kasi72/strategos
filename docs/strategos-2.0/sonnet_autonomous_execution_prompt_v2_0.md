# Prompt for the Implementing Model (Paste into Claude Code running Sonnet, in the repo root)

You are implementing the **Strategos 2.0 Argon High Re-Architecture (`v2.0-argon`)** in this repository. Work autonomously, methodically, and iteratively until every phase in `docs/strategos-2.0/BLUEPRINT_v2.0.md` is built, verified by unit and Robolectric tests, and passes `assembleDebug`. Stop only at the explicit checkpoints below.

## Read First (Every Session)
1. `CLAUDE.md` (repository guardrails).
2. `docs/strategos-2.0/BLUEPRINT_v2.0.md` — Read §0 (Rules & Edge-Case Mandates), §1–§4 (Three-Space Architecture, DeepSeek Transport, & Room v2 Schema), §5 (Prompts Are King — verbatim prompt specifications), §6–§8 (Deterministic Trajectory, Quick-Check, & Silent Journal Screener), §9 (UI/UX Specification), and §10 (Phased Execution Plan).
3. `docs/strategos-2.0/TEST_CASES_v2.0.md` — Every required JUnit/Robolectric test case ID and live evaluation check.
4. `docs/strategos-2.0/PROGRESS_v2.0.md` — Check where the previous session stopped and continue from the next incomplete step.

## Setup (First Session Only)
- Ensure you are on branch `strategos-2.0` (or create branch `strategos-2.0-argon` from the current HEAD if preferred; never commit directly to `main`).
- Export Java 17 in your shell before running Gradle:
  `export JAVA_HOME=/opt/homebrew/opt/openjdk@17`
- Run `sh ./gradlew :app:testDebugUnitTest` and verify the existing baseline (328 tests, 0 failures, 1 skipped env-gated spike) is green. Record the baseline confirmation in `docs/strategos-2.0/PROGRESS_v2.0.md`. If the baseline fails, stop and report before changing code.

## Autonomous Execution Loop (Repeat for Each Numbered Step in `BLUEPRINT_v2.0.md` §10, in Exact Order)
1. **Plan:** Re-read the blueprint sections cited by the current step. List the exact files you will create or modify.
2. **Test First (TDD):** Write the step's JUnit/Robolectric tests from `docs/strategos-2.0/TEST_CASES_v2.0.md` (named after their `TC_*` ID). Run them with `sh ./gradlew :app:testDebugUnitTest --tests "*<TestClassName>*"` and confirm they fail for the expected reason.
3. **Build:** Implement the exact code, database entities, deterministic calculators, and verbatim prompts specified in `BLUEPRINT_v2.0.md`. Do not invent weaker prompts, skip edge-case guards, or add unrequested dependencies.
4. **Verify:** Run the full unit test suite (`sh ./gradlew :app:testDebugUnitTest`) and compile the debug APK (`sh ./gradlew :app:assembleDebug`). Fix any regressions until all existing 328 tests plus all new tests are 100% green. Never delete, skip, or weaken an existing test.
5. **Commit:** Make one clean commit per numbered step with the message format:
   `argon-<phase>.<step>: <concise summary>`
6. **Record:** Update `docs/strategos-2.0/PROGRESS_v2.0.md` with the completed step row (`| step | status | tests added | notes / BLUEPRINT-QUESTIONs |`) and update the "Next Step" pointer.
7. **Advance:** When all steps in a phase are complete, run the phase's `✅ Accept` verification gate, mark `Phase <N> accepted` in `PROGRESS_v2.0.md`, and immediately begin the next phase.

If your context window is filling up, finish the current numbered step, commit, update `PROGRESS_v2.0.md`, and instruct the user to start a fresh session with:
`Follow docs/strategos-2.0/SONNET_PROMPT_v2.0.md exactly. Start now.`

## Checkpoints (Stop and Wait for User Only Here)
- **CP-DS1 (End of Phase A, Step A.4 — Live DeepSeek API Spike):**
  Check whether `DEEPSEEK_API_KEY` (or `XAI_API_KEY` fallback) is present in `.env` (`set -a; source .env; set +a`).
  - If present: run `DeepSeekLiveSpikeTest`, write the verified results (prefix caching `prompt_cache_hit_tokens`, JSON mode sketch, silent journal screener latency) into `docs/strategos-2.0/API_NOTES.md`, and **continue autonomously to Phase B without stopping**.
  - If missing: keep `DeepSeekLiveSpikeTest` gated by `Assume.assumeTrue(...)`, record that the live spike is pending the user's key in `PROGRESS_v2.0.md`, and **continue autonomously through Phases B, C, D, and E** using `FakeLlmClient` and `MockWebServer`! Stop to ask for the live key only at **CP-FINAL** after all code and offline/mocked tests are 100% built and green.
- **CP-FINAL (End of Phase E — All Code, Prompts, DB Migrations, and UI Complete):**
  Present the final summary, total test count, emulator verification results, and the manual DeepSeek checklist from `TEST_CASES_v2.0.md` §8.

## Final Verification Protocol (Run Before CP-FINAL)
1. Run clean unit tests: `sh ./gradlew clean :app:testDebugUnitTest`.
2. Run debug build and lint: `sh ./gradlew :app:assembleDebug :app:lintDebug`.
3. Verify all 12 Argon High Invariants (`BLUEPRINT_v2.0.md` §2) have passing tests.
4. Verify every `TC_*` ID in `TEST_CASES_v2.0.md` is mapped to an automated test function.
5. Run `grep -rn "BLUEPRINT-QUESTION" app/` and list any open items in `PROGRESS_v2.0.md`.
6. Write `docs/strategos-2.0/RELEASE_NOTES_v2.0.md` summarizing the Three-Space architecture, DeepSeek Dual-Agent pipeline, Sigmoid Trajectory engine, and test results.

## Hard Prohibitions
- **Never** hardcode or log an API key, raw journal entry text, or conversation payload in Logcat, exception messages, or `PROGRESS_v2.0.md`.
- **Never** block the main thread on Journal parsing: `JournalScreenerService` must save the journal entry locally first (`status = RECORDED`), emit `"Recorded privately 🍃"` immediately, and run DeepSeek screening asynchronously on `Dispatchers.IO`.
- **Never** edit `app/src/main/kotlin/com/drkkr/strategos/framework/**` except as already completed in baseline v2.0.