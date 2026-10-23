# Strategos — instructions for coding agents

Native Android app (Kotlin 1.9.24, Jetpack Compose, AGP 8.5, minSdk 26) for game-theory decision support. Version 2.0 adds an LLM chat agent ("Counsel") on top of an on-device analysis engine.

## The spec
- `docs/strategos-2.0/BLUEPRINT.md` is the source of truth. Read §0–§3 before any task, then only the sections the current phase cites.
- `docs/strategos-2.0/TEST_CASES.md` lists every test to implement. Name each test after its case id (`TC_GATE_02_…`).
- Work **one phase at a time** (§13). Don't start the next phase until that phase's "✅ Accept" checks pass. One commit per numbered step, with a message like `phase-1.3: GameRole in MatrixSupport`.
- If the spec is ambiguous, choose the option that changes less existing code and leave a `// BLUEPRINT-QUESTION:` comment. Don't invent features.

## Commands
```bash
./gradlew :app:testDebugUnitTest          # after every step; must stay green
./gradlew :app:assembleDebug
scripts/export-agent.sh                    # after any change under agent/
set -a; source .env; set +a                # only for the live spike test and release signing
```

## Hard rules
- Don't edit `app/src/main/kotlin/com/drkkr/strategos/framework/**` except `MatrixSupport.kt` and `FrameworkSelector.kt`, as blueprint §6.3 says.
- Never delete, skip or weaken an existing test. The 77 tests in `FrameworkTests.kt` must keep passing.
- Nothing under `domain/` imports `android.*` or `androidx.*`.
- Add only the dependencies and versions in blueprint §4.2.
- Never log or persist user text, scenario content or the API key. Never commit `.env`, `keystore.properties` or `*.jks`.
- The LLM never edits the scenario directly. All changes go through `ChangeSet` → `ChangeSetApplier`. Agent output can never be `FactStatus.CONFIRMED`.
- Edit `agent/*.md` and `agent/tools/*.yaml` only when the blueprint says so, then re-run `scripts/export-agent.sh` and commit the regenerated `app/src/main/assets/agent/*`.
- Follow the existing style: data classes, `object` singletons, kotlinx.serialization, manual wiring in `AppContainer`, Neo-Tactile composables from `ui/theme/NeoDesign.kt`.
