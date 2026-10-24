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

## Phase 6 end-to-end run (2026-10-23, `XaiLiveSpikeTest.counselEndToEnd`)
Healthcare template, Expert mode, real agent assets, `openai/gpt-oss-20b`.

| Step | Result |
|---|---|
| Small spike calls (S1–S7, ~80–160 input tokens) | Still PASS with the same key. The key and account work. |
| Every Counsel turn (system prompt + tools + live context) | **HTTP 413, `error.code = rate_limit_exceeded`** on the first request, so no tool was ever called. |

Size of one Counsel request before any history: `system_prompt.md` 17.5 KB (~4.4k tokens) + `tools.json` 22 KB (~5.5k tokens, the change-op schema appears in both `propose_changes` and `what_if`) + the scenario digest. That is ~10k tokens or more per request, and a turn makes 2–4 requests. Groq returns 413 `rate_limit_exceeded` when one request exceeds the account's tokens-per-minute limit, so this account tier can't run Counsel at all. **Needs a decision (CP3).**

`LlmErrors` now maps `rate_limit_exceeded` to "Not enough credits." (provider override rule), so users see that instead of "error (413)".

## CP3 live run after the 6.9 fixes (`XaiLiveSpikeTest.counselEndToEnd`, gpt-oss-20b, scripted loan story)
**Works:** no 413s (Guided requests ≈2.0–2.6k input tokens); the model now calls `propose_changes` every turn; changes are saved (5 commits, scenario grew to 12 people / 6 options); one request per Guided turn; `run_analysis` ran and returned a digest.
**Does not work well yet (model quality, 20B):**
- Re-adds the same people every turn (4 real people became 12 entries) instead of using the ids in the context.
- Marks almost everyone as PLAYER (10 players), which blows up the matrix; ignores the "2 players, 3 max" rule.
- Asks the same question repeatedly ("Can your brother stop you…") regardless of the answer; sometimes sends 5 options (max 4).
- Never reached the outcomes step (0/0 payoffs); the later `set_payoff` calls failed with `bad_profile`.
- Still gets `400 tool_use_failed` on some calls, and the scripted run hit `429` (8k tokens/minute) after several fast turns.
**Cheap fixes available (not done, waiting for a decision at CP3):** drop an `add_stakeholder` whose name already exists (turn it into an update); truncate `ask.options` to 4 instead of erroring; reject a 4th PLAYER in the validator; try `openai/gpt-oss-120b` (one setting, 2× price).

## DeepSeek live spike (Argon A.4, run 2026-10-24)

Endpoint `POST https://api.deepseek.com/v1/chat/completions`, model `deepseek-chat`, via `ChatCompletionsClient`. Run: `set -a; source .env; set +a; ./gradlew :app:testDebugUnitTest --tests "*DeepSeekLiveSpikeTest*" --rerun-tasks`.

| Check | Result | Detail |
|---|---|---|
| S1 JSON mode (`response_format: json_object`) | PASS | Reply parses as a JSON object. |
| S2 prefix cache | PASS | Second identical-prefix call: `prompt_cache_hit_tokens=640` of 842 input tokens. |
| S3 journal-screener-sized call | PASS | 831 ms round trip (600-token cap, JSON mode). |

Not covered: `deepseek-reasoner`, 429 behaviour, and the full coach prompt. An earlier run with a Groq key returned 401, as expected.
