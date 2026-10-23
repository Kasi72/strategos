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

Next step: Phase 0.2 (analyze() on Dispatchers.Default).
