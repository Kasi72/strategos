# Strategos server (v4)

The Strategos engine as a local web app and an MCP server. Spec: `STRATEGOS_SDD_v4.0.md` (repo root).
Every command below runs from the **repo root** (the folder with `gradlew`), on macOS or Linux.

## 1. What you need

| | Needed for | Check |
|---|---|---|
| **JDK 17** (e.g. Temurin, or `brew install openjdk@17`) | the server | `java -version` |
| **Node.js 18+** (with npm) | building the web page | `node -v` |
| **A DeepSeek API key** (https://platform.deepseek.com) | every reply | |
| Docker *(optional)* | Graphiti memory | `docker compose version` |
| A Voyage AI key *(optional, with Docker)* (https://dash.voyageai.com) | Graphiti's embeddings | |

No Android SDK is needed: the server builds without it.

## 2. Quick start (no Docker)

```bash
cp .env.example .env                 # then open .env and set DEEPSEEK_API_KEY=sk-...
set -a; source .env; set +a          # load it into this terminal

(cd web && npm install && npm run build)   # the web page, served by the server
./gradlew :server:run                       # first run downloads Gradle and dependencies (a few minutes)
```

Open **http://localhost:8080** and describe a situation. Chats are saved in `strategos.db` in the repo root (gitignored).
Memory is the built-in one (a keyword and time ledger, kept in the same file).

Stop with Ctrl-C. Next time, only `set -a; source .env; set +a` and `./gradlew :server:run` are needed (rebuild the page after
changing anything under `web/`).

## 3. Optional: Graphiti memory (Docker)

Graphiti gives the coach a long-term memory graph (FalkorDB + a small Python service).

```bash
# in .env: DEEPSEEK_API_KEY and VOYAGE_API_KEY set, and GRAPHITI_URL=http://localhost:8000
docker compose up -d                 # FalkorDB on :6379 (graph browser on :3000), graphiti-service on :8000
curl -s localhost:8000/health        # wait until it answers
set -a; source .env; set +a
./gradlew :server:run
```

`http://localhost:8080/api/health` then shows `"memory":"graphiti","memoryHealthy":true`. If Graphiti is down or slow, turns
still work: the server falls back to the built-in memory. Without a Voyage key you can run the embeddings locally instead:
set `EMBEDDING_PROVIDER=ollama`, `EMBEDDING_MODEL=nomic-embed-text` and `EMBEDDING_DIM=768` in `.env`, then
`docker compose --profile local-embeddings up -d` (pulls the model once). Pick one before real use: switching later means
re-embedding the memory.

Stop it with `docker compose down` (the memory is kept in a Docker volume).

## 4. Troubleshooting

| Problem | Fix |
|---|---|
| `./gradlew: permission denied` | `chmod +x gradlew` |
| The page says 404 / "Not Found" | Build the page: `(cd web && npm install && npm run build)` |
| Replies fail with 401 / "couldn't read that" | The key isn't loaded: run `set -a; source .env; set +a` in the same terminal, check `DEEPSEEK_API_KEY` |
| `Unsupported class file major version` / toolchain errors | Gradle is using another Java: install JDK 17 and set `JAVA_HOME` to it |
| `/api/health` shows `"memory":"graphiti","memoryHealthy":false` | Graphiti isn't up yet (`docker compose ps`, `docker compose logs graphiti`), or empty `GRAPHITI_URL` to skip it |
| Port 8080 is taken | `PORT=8081 ./gradlew :server:run` |

## Open it on your phone

- Same Wi-Fi: start with `HOST=0.0.0.0 STRATEGOS_AUTH_TOKEN=<something> ./gradlew :server:run`, open
  `http://<your-computer's-ip>:8080`, and enter the token in Settings → Server access.
- USB (Android): `adb reverse tcp:8080 tcp:8080`, then open `http://localhost:8080` in Chrome on the phone.

## Claude Desktop / Claude Code (MCP)

stdio (Claude launches the server):

```bash
./gradlew :server:installDist
claude mcp add strategos --env MCP_TRANSPORT=stdio --env DATABASE_URL=jdbc:sqlite:$PWD/strategos.db \
  --env DEEPSEEK_API_KEY=$DEEPSEEK_API_KEY -- $PWD/server/build/install/server/bin/server
```

Claude Desktop (`claude_desktop_config.json`):

```json
{ "mcpServers": { "strategos": {
  "command": "/ABSOLUTE/PATH/strategos/server/build/install/server/bin/server",
  "env": { "MCP_TRANSPORT": "stdio", "DATABASE_URL": "jdbc:sqlite:/ABSOLUTE/PATH/strategos/strategos.db", "DEEPSEEK_API_KEY": "sk-..." }
} } }
```

Streamable HTTP (the running server): `claude mcp add --transport http strategos http://localhost:8080/mcp`.

Point stdio and the web server at the same `DATABASE_URL` to share one state. Run only one writer at a time on SQLite.

## Tests

```bash
./gradlew :server:test                       # server: API, pipeline, X-ray and web-contract tests (offline, no key needed)
(cd web && npm test)                         # web unit tests
(cd web && npm run build && npm run e2e)     # real-browser tests: needs Google Chrome and DEEPSEEK_API_KEY in .env (~2 min)
./gradlew :app:testDebugUnitTest             # the Android app and shared engine (needs the Android SDK)
DEEPSEEK_API_KEY=... LIVE_MESSAGES_FILE=msgs.txt LIVE_OUT_FILE=out.txt ./gradlew :server:test --tests '*LiveDeepSeekTest*'
```

The e2e suite starts its own server on a throwaway database, so your chats are never touched.

## On a server

`docker compose --profile server up -d` builds the server image (UI included) and runs it next to the memory stack. Set
`STRATEGOS_AUTH_TOKEN` (required), `JOURNAL_ENCRYPTION_KEY`, and for Postgres `DATABASE_URL_SERVER=jdbc:postgresql://…`. Put it
behind HTTPS (Caddy or nginx).
