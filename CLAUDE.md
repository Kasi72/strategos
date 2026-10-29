# Strategos

Native Android app (Kotlin 1.9.24, Jetpack Compose, AGP 8.5, minSdk 26) for game-theory decision support.
You describe a situation in plain words; an on-device engine models it as a game and picks a move, and an LLM
(DeepSeek) turns that into a short, plain-language answer.

## How it works
- **Extraction** (LLM call 1) reads what the person wrote into a structured situation.
- **Engine** (`framework/`, `domain/`, on-device Kotlin) analyses it and produces a directive.
- **Coach** (LLM call 2, `agent/`) words the directive. Long threads use a rolling summary, not the full history.
- **UI** (`ui/calm/`): Projects, Talk (chat), Journal, Settings, onboarding. Data is in an encrypted local DB.

## Build
```bash
./gradlew :app:testDebugUnitTest   # unit tests
./gradlew :app:assembleDebug       # APK in app/build/outputs/apk/debug
```
The AI key is entered in the app's Settings and stored in the Android Keystore. Copy `.env.example` to `.env`
only for live/e2e tests (`scripts/run-e2e.sh`, emulator only).

## Rules
- Nothing under `domain/` imports `android.*` or `androidx.*`.
- Never log or persist user text or the API key. Never commit `.env`, `keystore.properties` or `*.jks`.
- The LLM never edits the scenario directly; changes go through `ChangeSet`. Agent output is never `CONFIRMED`.
- Don't delete, skip or weaken tests. After changing `agent/*.md` or `agent/tools/*.yaml`, run `scripts/export-agent.sh`.
