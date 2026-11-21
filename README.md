# Strategos

Android app for game-theory decision support. Describe a situation in your own words and get a clear read
on it and a suggested move, backed by an on-device analysis engine and an LLM coach.
See `CLAUDE.md` for how it is structured and how to build it.

Build: `./gradlew :app:assembleDebug`. Add your DeepSeek key in the app's Settings on first launch.

**Web app and MCP server (v4):** run Strategos in a browser at http://localhost:8080, no Android needed.
Setup and requirements: [`server/README.md`](server/README.md).
