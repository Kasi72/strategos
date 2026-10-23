# Prompt for the implementing model (paste into Claude Code running Sonnet, in the repo root)

You are implementing Strategos 2.0 in this repository. Work autonomously and iteratively until the whole blueprint is built and tested. Stop only at the checkpoints below.

## Read first (every session)
1. `CLAUDE.md` (rules).
2. `docs/strategos-2.0/BLUEPRINT.md` §0–§3, then §13 (phase plan), §17 (Guided mode) and §18 (gaps).
3. `docs/strategos-2.0/TEST_CASES.md`.
4. `docs/strategos-2.0/PROGRESS.md`, if it exists. It says where the previous session stopped. Continue from there.

## Setup (first session only)
- Create and switch to branch `strategos-2.0` from `main`. Never commit to `main`.
- Run `./gradlew :app:testDebugUnitTest` and record the baseline result in `PROGRESS.md`. If the baseline fails, stop and report; don't fix unrelated failures.

## Loop: repeat for each numbered step in BLUEPRINT §13, in order
1. **Plan:** re-read the blueprint sections the step cites. List the files you'll create or change.
2. **Test first:** write the step's tests from TEST_CASES.md (named by case id). Run them and confirm they fail for the right reason.
3. **Build:** implement the minimum code the blueprint specifies. Don't add features, libraries or refactors it doesn't ask for.
4. **Verify:** run `./gradlew :app:testDebugUnitTest` (everything, not just the new tests) and `./gradlew :app:assembleDebug`. Fix until both are green. Never delete, skip or weaken a test to make it pass; if a test seems wrong, leave it failing and stop at a checkpoint.
5. **Commit:** one commit per step, message `phase-<n>.<m>: <what>`.
6. **Record:** update `PROGRESS.md` with a step row (`| step | status | tests added | notes / BLUEPRINT-QUESTIONs |`) and the next step to do.
7. **Move on.** When every step of a phase is done, run that phase's "✅ Accept" checks, write `Phase <n> accepted` in `PROGRESS.md`, and start the next phase.

If a session is getting long, finish the current step, update `PROGRESS.md`, and tell me to start a fresh session with this same prompt.

## Checkpoints: stop and wait for me
- **CP1, Phase 3.4 (live API spike):** check whether `XAI_API_KEY` is set (`set -a; source .env; set +a` first). If it isn't, tell me to fill in `.env` and stop. If it is, run `XaiLiveSpikeTest`, write the S1–S7 results into `API_NOTES.md`, set `ECHO_STRATEGY` and `CONTEXT_ROLE` from them, and continue.
- **CP2, end of Phase 4:** if `npm` / `node` is missing or `opengap validate` fails in a way the blueprint doesn't cover, stop and show me the error.
- **CP3, end of Phase 6:** show me a summary and ask me to try a real conversation on a phone or emulator before you start the UI.
- **CP4, end of Phase 7:** give me the manual checklist (TEST_CASES §10) to run on a phone.
- Any time a blueprint instruction conflicts with the existing code in a way you can't resolve with rule R9.

## Final testing (after Phase 8)
1. Run every unit test from a clean state: `./gradlew clean :app:testDebugUnitTest`.
2. Run `./gradlew :app:assembleDebug` and `./gradlew :app:lintDebug`. Fix new errors; list warnings.
3. Go through BLUEPRINT §15 (Definition of done) and mark each box with evidence (test names or files).
4. Map every case id in TEST_CASES.md to its test, or mark it MANUAL. Report any case that's missing.
5. Run `grep -rn "BLUEPRINT-QUESTION" app/` and list each one with your recommendation.
6. Write `docs/strategos-2.0/RELEASE_NOTES.md`: what was built, the test count, known gaps, and the manual checks I still need to do (checklist §10 and agent eval §14).
7. Push the branch, then stop. Don't merge, and don't open a pull request unless I ask.

## Never
- Never put an API key in source code, tests, logs, commits or `PROGRESS.md`. Only the live spike test reads `XAI_API_KEY`, and only from the environment.
- Never commit `.env`, `keystore.properties` or `*.jks`.
- Never edit `framework/**` beyond what BLUEPRINT §6.3 allows.
