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

## Extra: same spike run against Groq (2026-10-23, not xAI)

The key in `.env` is a Groq key (`gsk_` prefix, console.groq.com), not an xAI key. It is valid for `https://api.groq.com/openai/v1`.
Run with `XAI_BASE_URL=https://api.groq.com/openai/v1 XAI_MODEL=openai/gpt-oss-20b`. This does **not** satisfy CP1 (the blueprint locks xAI); it shows `XaiResponsesClient` works against a real Responses API server.

| Check | Groq result |
|---|---|
| S1 plain call, `store:false` | PASS. Response echoes `store:false`. |
| S2 tool round trip | PASS with `VERBATIM` (`STRIP_IDS` and `CALLS_ONLY` also pass). Output item types: `reasoning`, `function_call`. |
| S3 `role:"system"` mid-input | PASS |
| S4 `tool_choice:"none"` | FAIL: HTTP 400 `tool_use_failed` (the model tried to call the tool anyway). The blueprint fallback applies: use `"auto"` and drop any calls returned. |
| S5 usage fields | PASS: `input_tokens`, `output_tokens`, `input_tokens_details.cached_tokens`, `output_tokens_details.reasoning_tokens` |
| S6 `grok-4.7`, `grok-4.3` | FAIL (404 `model_not_found`), expected: not Groq models |
| S7 model list | `GET /models` works (`data[]`, fields include `id`, `pricing`, `context_window`); `/language-models` is 404 |

Groq models visible to this key: `openai/gpt-oss-120b`, `openai/gpt-oss-20b`, `openai/gpt-oss-safeguard-20b`, `qwen/qwen3.8-27b`, `allam-2-7b`, plus whisper/orpheus/prompt-guard.
