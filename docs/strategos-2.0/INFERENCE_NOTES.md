# Inference notes (Groq, gpt-oss-20b, real phone, 2026-10-24)

Written for a deeper review by a stronger model. Observations come from the debug request trace (`DebugLog`, tag `StrategosDebug`)
on a CPH2659 running the 2.0.0 debug build. The raw trace holds the tester's personal story, so it is **not** in the repo.
Nothing below quotes the tester's text.

## 1. What the app does per message (SIMPLE PROFILE)
1. One request: system prompt (~800 tokens) + app context (the saved model as JSON) + earlier messages + the new message.
2. The model returns one JSON "sketch": goal, people, options, dims, outcomes (scores), limits.
3. The app converts it to change ops, validates, saves, runs the 32-module engine on the device, and writes a fixed-template answer.
4. (Added later, untested live at the time of writing) COMPANION REPLY: a second request writes ~150 words from the engine result.

## 2. What the model returned (7 requests, all HTTP 200)
| Request | Input tok | Output tok | Reasoning tok | Latency |
|---|---|---|---|---|
| 1st message | 1403 | 707 | 126 | 1.8 s |
| 2nd message | 1317 | 753 | 39 | 1.4 s |
| 3rd message (attempt 1) | 1430 | 914 | 58 | 1.7 s |
| 3rd message (attempt 2) | 1430 | 1056 | 204 | 1.9 s |
| 4th message | 1430 | 1108 | 256 | 1.9 s |
| conversation summary | 853 | 312 | 20 | 0.9 s |
| 5th message | 1552 | 670 | 53 | 1.2 s |

- Quality of the extraction was sensible: for a relationship situation it made the user and the spouse the two players, put family, an aunt,
  a brother and friends in as influencers, produced 3 options per player, and filled all 9 outcome combinations x 2 players with 3 scores each.
- Weak spots seen: it marked several relatives as able to veto (`veto:true`) without the user saying so; it invented influencers
  ("Your Brother", "Your Friends") from a passing remark; and on a later message it **renamed an option** ("Tell husband's mom and sister…"
  then "Inform Mom and Sister…") between attempts, so option wording is not stable across calls.
- The model's reasoning text is exposed in the response (`reasoning_text`); effort "low" used 20-260 reasoning tokens.

## 3. Failures found, with cause and fix
| Symptom on the phone | Cause (from the trace) | Fix |
|---|---|---|
| "I couldn't turn that into options" on every follow-up | The model copied the saved-model JSON's list form (`"fears":["…"]`, key `goals`) back; the sketch parser only accepted strings, so the whole reply was rejected as unparseable (HTTP 200, 3,100 chars, not truncated). | `TextOrListSerializer`; `goals` accepted as `wants`. Test `wantsAndFearsMayComeBackAsLists`. |
| Same error when a follow-up added nothing new | No new ops -> treated as failure. | Re-run the engine and answer; test `aFollowUpWithNothingNewStillAnswersInsteadOfFailing`. |
| Reply to a question looked identical to the last one | By design the answer was a fixed template; the model never saw the question or the engine result. | COMPANION REPLY (second request, `CoachFacts` + `COACH_PROMPT`). |

## 4. Limits and costs to expect
- Groq's tier allows 8,000 tokens/minute and reserves `max_output_tokens` per request. One message now makes up to 3 requests
  (sketch ~1.4k in + 2k reserved, optional repair, coach ~1k in + 700 reserved, and sometimes a summary ~0.9k in). Two messages within
  a minute can exceed the cap and show "Not enough credits.".
- DeepSeek was checked as an alternative: Chat Completions only (no Responses endpoint), no tokens-per-minute cap (concurrency limits only),
  no free tier found, `thinking` on by default (must be disabled). `llm/ChatCompletionsClient.kt` is a first, **unwired and untested** client for it.

## 5. Questions for the deeper review
1. Should the extraction and the companion reply be one call (the model sees the engine result only after the app runs it, so probably not)?
2. Option wording drifts between calls; should the app keep option ids and only let the model rename via an explicit "rename" op?
3. Scores are the model's guesses (all `AI_INFERRED`); is the executive status honest enough about that for a person in distress?
4. The companion prompt (`OneShotTurn.COACH_PROMPT`) positions the app as a coach, not a therapist. Review tone and safety with a clinician;
   the crisis-phrase list and helpline text (`SafetyKeywordScreen`, `SettingsRepository.DEFAULT_SUPPORT_TEXT`) are still unreviewed.
5. Memory: only the last 3 user messages (200 chars each) plus the saved model are sent. A running journal summary is not implemented.
6. Privacy: `DebugLog` writes requests and replies to logcat in debug builds only (`BuildConfig.DEBUG`). Release builds never set the sink.
