# Strategos server (v4)

The Strategos engine as a local web app and an MCP server. Spec: `STRATEGOS_SDD_v4.0.md` (repo root).

## Run it on your Mac

```bash
cp .env.example .env            # once; set DEEPSEEK_API_KEY
set -a; source .env; set +a

(cd web && npm install && npm run build)   # the UI, served by the server at /
./gradlew :server:run                       # http://localhost:8080
```

Without Graphiti it uses the built-in memory (keyword + time ledger, saved in `strategos.db`). For Graphiti:

```bash
docker compose up -d            # FalkorDB + graphiti-service on :8000 (needs DEEPSEEK_API_KEY and VOYAGE_API_KEY in .env)
GRAPHITI_URL=http://localhost:8000 ./gradlew :server:run
```

UI development with hot reload: `cd web && npm run dev` (http://localhost:5173, proxies `/api` to :8080).

## Open it on your phone

- Same Wi-Fi: start with `HOST=0.0.0.0 STRATEGOS_AUTH_TOKEN=<something> ./gradlew :server:run`, open
  `http://<your-mac's-ip>:8080`, and enter the token in Settings → Server access.
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
./gradlew :server:test                     # the eight SDD acceptance tests (offline)
DEEPSEEK_API_KEY=... LIVE_MESSAGES_FILE=msgs.txt LIVE_OUT_FILE=out.txt ./gradlew :server:test --tests '*LiveDeepSeekTest*'
```

## On a server

`docker compose --profile server up -d` builds the server image (UI included) and runs it next to the memory stack. Set
`STRATEGOS_AUTH_TOKEN` (required), `JOURNAL_ENCRYPTION_KEY`, and for Postgres `DATABASE_URL_SERVER=jdbc:postgresql://…`. Put it
behind HTTPS (Caddy or nginx).
