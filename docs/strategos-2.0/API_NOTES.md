# LLM API notes (Phase 3.4 live spike)

**Provider: Groq, Responses API** (user decision 2026-10-23, replacing the blueprint's xAI).
Endpoint `POST https://api.groq.com/openai/v1/responses`, default model `openai/gpt-oss-20b`, option `openai/gpt-oss-120b`.
The first attempt used an xAI endpoint with a Groq key (`gsk_...`) and was rejected, which is why the code and docs changed.

Run it yourself:
```bash
set -a; source .env; set +a
sh ./gradlew :app:testDebugUnitTest --tests "*XaiLiveSpikeTest*" --rerun-tasks --console=plain
```
Output lines are `SPIKE|<check>|PASS/FAIL|<detail>` in `app/build/test-results/testDebugUnitTest/TEST-com.drkkr.strategos.XaiLiveSpikeTest.xml`.
They contain statuses, field names and token counts only, never the key or model text.

## Results (run 2026-10-23)

| Check | Result | Consequence |
|---|---|---|
| S1 plain call, `store:false` | PASS | The response echoes `store:false`. |
| S2 tool round trip | PASS with `VERBATIM` (`STRIP_IDS` and `CALLS_ONLY` also pass) | `AgentConfig.ECHO_STRATEGY = VERBATIM`. Output item types seen: `reasoning`, `function_call`, `message`. |
| S3 `role:"system"` mid-input | PASS | `AgentConfig.CONTEXT_ROLE = "system"`. |
| S4 `tool_choice:"none"` | FAIL: HTTP 400 `tool_use_failed` | The provider rejects it. `XaiResponsesClient` turns `"none"` into a request with `tools` omitted (`AgentConfig.TOOL_CHOICE_NONE_SUPPORTED = false`). Probe: after a tool call, a final round with `tools` omitted and the echoed items in `input` returns 200 with a `message`. The spike's S4 prompt *demands* a tool call, so it still gets `tool_use_failed`; that is the adversarial case. Phase 6: treat a `tool_use_failed` HttpError on the forced final round as a failed turn (retryable), not a crash. |
| S5 usage fields | PASS | `input_tokens`, `output_tokens`, `input_tokens_details.cached_tokens`, `output_tokens_details.reasoning_tokens`. Reasoning tokens are a sub-count of `output_tokens`. |
| S6 model ids | PASS for `openai/gpt-oss-20b` and `openai/gpt-oss-120b` | `AgentConfig.MODEL_OPTIONS` set to those two. |
| S7 model list | `GET /models` PASS (`data[]`: `id`, `pricing`, `context_window`, `max_completion_tokens`, `input_modalities`...). `/language-models` is 404. | `listModels` uses `/models` and reads `pricing.prompt` / `pricing.completion` (USD per token). The list also holds speech and guard models (`whisper-*`, `canopylabs/orpheus-*`, `meta-llama/llama-prompt-guard-*`, `openai/gpt-oss-safeguard-20b`): Phase 7's dropdown filter must exclude them (add `whisper`, `orpheus`, `prompt-guard`, `safeguard` to the blueprint's exclusion list). |

## Prices (from `GET /models` on 2026-10-23, USD per 1M tokens)

| Model | Input | Cached input | Output |
|---|---|---|---|
| `openai/gpt-oss-20b` | 0.075 | 0.0375 | 0.30 |
| `openai/gpt-oss-120b` | 0.15 | 0.075 | 0.60 |

Both: 131,072 context, 65,536 max output, features `tools`, `json_mode`, `structured_outputs`, `reasoning`.

## Quality caveat
`gpt-oss-20b` is a small reasoning model. Interview quality and tool-call reliability are what the design depends on. Run TEST_CASES section 14 (agent eval) on the relationship and loan-family scenarios before relying on it; `gpt-oss-120b` is one setting away.
