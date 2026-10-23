# Strategos 2.0 progress

Branch: `strategos-2.0` (created from `main` at 03d12a8; CLAUDE.md, docs/, .env.example and the .gitignore change are still uncommitted).

## Baseline
**GREEN.** `./gradlew :app:testDebugUnitTest`: 77 tests, 0 failures, 0 errors, 0 skipped (3m 28s cold).

Toolchain installed 2026-10-23 with Homebrew:
- `openjdk@17` (keg-only). Use `export JAVA_HOME=/opt/homebrew/opt/openjdk@17` in each shell.
- `android-commandlinetools`, SDK root `/opt/homebrew/share/android-commandlinetools`, with platform-tools, platforms;android-34 and build-tools;34.0.0. Licenses accepted.
- `local.properties` (gitignored) holds `sdk.dir`.
- `gradlew` is not executable in this checkout: run it as `sh ./gradlew ...`. Not changed, to keep the diff clean.
- Node v26.12.0, npm 11.20.0.

## Steps
| step | status | tests added | notes / BLUEPRINT-QUESTIONs |
|---|---|---|---|
| 0.1 | done | 0 (TC_BUILD_01/02 verified by command) | Signing read from keystore.properties or STRATEGOS_* env; release signed only if the store file exists. Needed `import java.util.Properties` (inside `android {}` `java` is the Java extension). |
| 0.2 | done | 0 | Classify, runAll and combine run in `withContext(Dispatchers.Default)`; state updates stay on main. Full suite + assembleDebug green. |
| 0.3 | done | 1 (TC_PURE_01) | Verified it fails when `import android.util.Log` is added to domain/, then reverted. |
| 1.1 | done | 2 (TC_PROV_01/02) | factStatus + provenanceNote on Stakeholder, Action, Constraint, PayoffEntry. gameRole assertion added in 1.2. |
| 1.2 | done | 1 (players_excludesInfluencers) + TC_PROV gameRole assertions | GameRole enum, Stakeholder.gameRole, Scenario.players(); Fx.pdWithInfluencer added. |
| 1.3 | done | 3 (TC_MATRIX_01/02/03) | MatrixSupport.buildChecked and FrameworkSelector use scenario.players(). Tests 01/02 failed before the edit as expected; 03 already passed. |
| 1.4 | done | 5 (TC_PIPE_01 x4, TC_PIPE_02) | AnalysisPipeline extracted; ViewModel uses it (analyzeAndAwait + analyze). Expected template values captured from the old logic before the refactor; action labels stand in for random ids. BLUEPRINT-QUESTION (§6.4): PipelineResult has no provenance/gate fields yet; added in 1.5 to avoid forward dependency. Dropped the mid-run analysisPlan state update (plan is set at the end). |
| 1.5 | done | 10 (TC_GATE_01-06 + extras, ProvenanceStats) | ProvenanceStats, ProvenanceGate; PipelineResult gains provenance + gate and applies the gate (step 6 of §6.4). If executiveStatusReason is null, one is created when capping. |
| 1.6 | done | 5 (TC_RECON_01-04 + belief/constraint) | ProvenanceReconciler; called at the top of StrategosViewModel.updateActiveScenario (not on the agent path). Fixed my own TC_RECON_02 expectation (compared against the wrong object) before commit. |
| 1.7 | done | 2 (TC_LANG_01 + parser delegation) | LanguageDetector in domain/agent; ScenarioParser delegates. |
| 2.1 | done | 3 (TC_DB_01 cascade, seq uniqueness, settings) | Room 2.6.1 + KSP + SQLCipher + OkHttp + Robolectric deps (exact §4.2 versions), 10 entities, 10 DAOs, StrategosDatabase v1, schema exported to app/schemas. TC_DB_02 (concurrent append) comes with ConversationRepository in 2.5. |
| 2.2 | done | 0 (Keystore needs a real device; TC_SECRET_01 is instrumented-only, not written yet) | KeystoreCipher, DatabaseKeyManager (+StorageUnavailableException), DbFactory, SecretStore. DaoTest now uses DbFactory.inMemoryForTests. |
| 2.3 | done | 0 (wiring only; assembleDebug + full suite green) | StrategosApplication, AppContainer (lazy DB, null + error on failure), manifest android:name. BLUEPRINT-QUESTION: reused existing data.storage.StorageUnavailableException instead of adding a second class with the same name. |
| 2.4 | done | 7 (TC_DB_03/04/05 + delete, snapshots, orphan snapshot, null db) | ScenarioRepository now Room-backed (db, storageError); ViewModel gets db from AppContainer. save() builds the updated scenario outside the transaction (first run returned a different updatedAt than the stored one). BLUEPRINT-QUESTION: saveSnapshot skips when the scenario row doesn't exist yet (FK); analyze() saves the snapshot before the scenario for never-saved scenarios. |
| 2.5 | done | 7 (TC_DB_02 + conversation, memory, change set, settings) | AgentConfig (§10.1) created now because SettingsRepository needs DEFAULT_MODEL/CONSENT_VERSION; enums MessageRole, UiKind (incl. UNDERSTANDING, SUPPORT from §17.4), ChangeSetStatus, MemoryKind, UiMode in RepoModels.kt. Snapshots now carry engineVersion = AgentConfig.ENGINE_VERSION (§4.3). FOUND A REAL BUG: with `encodeDefaults=false` kotlinx.serialization drops `updatedAt`/`createdAt`/`timestamp` when the value equals "now" at encode time (same ms), and decoding re-evaluates the default. Caused a flaky TC_DB_04 (stored time 1ms off). ScenarioRepository now encodes with `encodeDefaults=true`. The legacy ScenarioStorage and the ViewModel `snapshotJson` still have the same latent issue (1.x behaviour, not touched). |
| 2.6 | done | 4 (TC_MIG_01/02/03 + missing store) | LegacyStorageMigrator + LegacySource; ScenarioMapper is the single Scenario->row mapper. AppContainer.prepareStorage() runs it once (Mutex) before load(); the legacy factory returns null when shared_prefs/strategos_scenarios.xml is absent so a fresh install never creates a 1.x master key. Legacy store is kept. |
| 3.1 | done | 7 (TC_XAI_03/04/05 + usage, functionCallOutput, PriceTable) | LlmClient/DTOs, OutputItems, PriceTable. BLUEPRINT-QUESTION: PriceTable assumes output_tokens already includes reasoning tokens (check in spike S5). |
| 3.2 | done | 14 (TC_XAI_01/02/06-12 + ok parse, missing key, retry-then-ok, bad_response, listModels) | XaiResponsesClient: store:false, parallel_tool_calls:false, retries per §9.4, writes one llm_call row per attempt, never logs or keeps bodies. BLUEPRINT-QUESTION: LlmRequest gained purpose/conversationId/turnId (defaulted) so the client can write llm_call rows. listModels is ids-only until spike S7 shows the real fields. |
| 3.3 | done | 2 (FakeLlmClient behaviour) | FakeLlmClient + helpers (textReply, toolCall, error401, error429, networkDown) in the test source set. |
| 3.4 | done | 0 (XaiLiveSpikeTest, env-gated; ran live) | Provider switched to Groq / openai/gpt-oss-20b on the user's instruction. Spike S1-S3, S5-S7 pass; S4 tool_choice:"none" is rejected so the client omits tools instead. ECHO_STRATEGY=VERBATIM, CONTEXT_ROLE=system. See API_NOTES.md and the PROVIDER OVERRIDE block at the top of BLUEPRINT.md. G1 (spending cap) dropped; LlmErrors maps credit/rate-limit failures to "Not enough credits." |
| 3.5 | done | 0 (permission + R8 rules; assembleDebug green; release/R8 build not run until Phase 8) | INTERNET permission, usesCleartextTraffic=false, R8 keep rules for domain, llm and net.zetetic. |
| 2.7 | done | 7 instrumented (TC_SECRET_01, SQLCipher encryption + reopen + wrong passphrase, legacy migration, fresh install) + 1 manual-only PostUpgradeCheckTest | Emulator `strategos_api34` (Android 14 arm64, Google APIs) installed with Homebrew sdkmanager. Real upgrade verified: built the 1.x APK from 03d12a8, seeded two scenarios with the real 1.x ScenarioStorage, `adb install -r` the 2.0 APK, home screen showed "2 total" with both, no crash, save works. BLUEPRINT-QUESTION: added androidTest deps (androidx.test runner 1.6.2, ext:junit 1.2.1, core 1.6.1), not in the 4.2 list. Note for 1.x seeding: ScenarioStorage writes with apply(), so a seeding process must wait before exiting or the write is lost. Run with `sh ./gradlew :app:connectedDebugAndroidTest` (emulator must be booted). |
| 4.1 | done | 0 (files only) | agent.yaml, SOUL.md, RULES.md, change_op.yaml and the 10 tool YAMLs extracted verbatim from the blueprint by script. 9 skills written with name/description/allowed-tools frontmatter (allowed-tools is a space-delimited string per the OpenGAP skill schema). guided-interview body = §7.5.1 verbatim plus the 4 required bullets. Follow-up (uncommitted): skill descriptions quoted, since `: ` inside broke YAML. |
| 4.2 | done | 0 (pipeline; run by script) | build.mjs, package.json, package-lock.json, export-agent.sh; node_modules gitignored. CP2: `opengap validate` rejected snake_case names in agent.yaml `tools:` (pattern `^[a-z][a-z0-9-]*$`). User chose option 1: the `tools:` list is removed from agent.yaml (BLUEPRINT-QUESTION comment there); build.mjs reads tools/*.yaml directly and the snake_case names stay everywhere. Validate now passes with 12 warnings (tool YAML `name` pattern ×10, `ops.items: "@change_op"` not an object ×2). Output is reproducible (two runs, identical sha256). Skill descriptions quoted (`: ` broke the YAML frontmatter). |
| 4.3 | done | 0 (wiring; full suite + assembleDebug green) | AgentAssets reads assets/agent/*; AppContainer.agentAssets (lazy). |
| 4.4 | done | 5 (TC_ASSET_01-05) | Verified TC_ASSET_05 fails when tools.json is edited without re-exporting. BLUEPRINT-QUESTION: TC_ASSET_02's `== ToolDispatcher.names` assertion is deferred to 5.8, when ToolDispatcher exists. |
| 5.1 | done | 3 (ChangeOpTest: snake_case parse, USER_HARD unparseable, describer) | ChangeOp mirrors change_op.yaml. `constraint_kind` uses OpConstraintKind (no USER_HARD), so I4 holds at parse time. `type` is non-null; `rationale` is nullable so a missing one gives `rationale_required` instead of failing the batch. ChangeOpDescriber.describe(op, scenario, batch) resolves refs by name. |
| 5.2 | done | 20 (TC_VAL_01-13 + 7 extra rules) | Stateful workspace (roles, action owners, refIds, dims); `recheck()` reused by the applier. BLUEPRINT-QUESTIONs: (1) the 3-6 dimension count is enforced only when a batch establishes the set; if payoffs exist the set must match (templates use all 10 dims, so a literal reading would block every payoff on a template); (2) add_belief/add_assumption without text → `label_required`. Fx.loanFamily and Fx.pdAiPayoffs added. |
| 5.3 | done | 6 (TC_QUOTE_01-05 + edge chars/null) | normalize = lowercase, collapse whitespace, trim, strip edge quotes/punctuation. Callers must pass USER texts only (TC_QUOTE_05 documents the contract). |
| 5.4 | done | 12 (TC_APPLY_01-11 + ref resolution) | Re-validates each op with ChangeSetValidator.recheck against the working scenario. User-rejected ops appear in `skipped` as `rejected`. Does not bump `version` (the save path does). add_stakeholder description → `notes`. BLUEPRINT-QUESTION: an op with no decision is treated as rejected. Fixed my own TC_APPLY_11 (a REJECTed update correctly leaves the target CONFIRMED). |

Phase 0 accepted: 78 tests green, assembleDebug OK without keystore.properties, `grep -r strategos123 app/` empty.

Phase 1 accepted: 106 tests green (77 legacy + 29 new). The 4 templates produce identical status, pathways and candidates through AnalysisPipeline (TC_PIPE_01). Not run on a device; no emulator here.

Phase 2 accepted for everything automatable: cascade delete, seq uniqueness and concurrent append, migrator tests all green (127 tests). **Not done, needs a device/emulator:** "install 1.x, create 2 scenarios, install 2.0, both appear and save works", and TC_SECRET_01 (real Keystore, instrumented). Both go on the manual checklist for the user. **UPDATE: both are now done on an emulator, see step 2.7.**

Phase 3 accepted: 156 tests (1 skipped = the env-gated live spike), API_NOTES.md has S1-S7 for the Groq provider. Provider is Groq / openai/gpt-oss-20b (see the PROVIDER OVERRIDE block in BLUEPRINT.md).

Phase 4 accepted: `opengap validate` passes (12 warnings, no errors), 161 tests green (1 skipped = live spike), assembleDebug OK.

Next step: Phase 5.5 (ScenarioDigest, ReportDigest).
