# xAI API notes (Phase 3.4 live spike)

**Status: NOT RUN against a working key (checkpoint CP1).**

`XaiLiveSpikeTest` is written and ran on 2026-10-23, but every call, including `GET /v1/models`, was rejected:
`HTTP 400`, error code `invalid-argument`, message saying the API key is incorrect.

What I can say about the key in `.env` without printing it: it is 56 characters long, it does **not** start with `xai-`,
and it has no stray whitespace or quotes. xAI keys normally start with `xai-`, so this may be a key for another provider,
a truncated paste, or a placeholder. Create a key at https://console.x.ai, paste it into `.env` as `XAI_API_KEY=...`, then run:

```bash
set -a; source .env; set +a
sh ./gradlew :app:testDebugUnitTest --tests "*XaiLiveSpikeTest*" --console=plain
```

The test prints `SPIKE|<check>|PASS/FAIL|<detail>` lines (statuses, field names and token counts only, never the key or model text).
Read them from `app/build/test-results/testDebugUnitTest/TEST-com.drkkr.strategos.XaiLiveSpikeTest.xml`.

## Results (fill in after a successful run)

| Check | Result | Notes |
|---|---|---|
| S1 plain call, `store:false` | not run | |
| S2 tool round trip (VERBATIM / STRIP_IDS / CALLS_ONLY) | not run | `AgentConfig.ECHO_STRATEGY` stays `VERBATIM` (the blueprint default) until this is known |
| S3 `role:"system"` mid-input | not run | `AgentConfig.CONTEXT_ROLE` stays `"system"` until this is known |
| S4 `tool_choice:"none"` | not run | |
| S5 usage field names | not run | also confirms whether `output_tokens` already includes reasoning tokens (PriceTable assumes it does) |
| S6 model ids `grok-4.7`, `grok-4.3` | not run | |
| S7 model list endpoint and price fields | not run | `XaiResponsesClient.listModels` parses ids only until this is known |
