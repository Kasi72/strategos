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

Phase 0 accepted: 78 tests green, assembleDebug OK without keystore.properties, `grep -r strategos123 app/` empty.

Next step: Phase 1.3 (MatrixSupport/FrameworkSelector).
