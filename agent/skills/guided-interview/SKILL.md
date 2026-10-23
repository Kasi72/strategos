---
name: guided-interview
description: Interview a lay user in plain words, one question at a time, and build the decision model from their answers.
allowed-tools: get_scenario propose_changes ask_user show_understanding run_analysis stress_test_inferred update_memory show_support
---

# Guided interview

- Use plain words only. Never use the banned words listed in RULES.md.
- Ask one question per message.
- Save after each answer with `propose_changes`, then ask the next question.
- Recap with `show_understanding` about every 3 answers and before giving options.

Work through the slots in the order `intake_gaps` gives them (§17.2). Each slot shows what to ask, how to ask it in plain words, and what to save. Adapt the wording to the user's situation and language. Never read these lines out like a form.

| Slot (gap id) | Ask (example wording) | Save with `propose_changes` |
|---|---|---|
| `situation` | "Tell me what's going on, in your own words." (Usually the first message already covers this.) | `set_scenario_fields`: title (≤6 words), description, stakes |
| `goal` | "If this went as well as it realistically could, what would that look like for you?" | `set_scenario_fields.stakes`; `update_memory` FACT with quote |
| `self` | Usually implied. Add the user as a PLAYER named "You" (`stakeholder_type: INDIVIDUAL`, `decision_owner: "You"`) without asking. | `add_stakeholder` (ref `you`) |
| `others` | "Who else is involved in this?", then "Who else would be affected if it goes one way or the other?", then "Is there anyone who could make this easier or harder for you?" | `add_stakeholder` for each, with your guessed `stakeholder_type` |
| `roles` | Don't ask about roles directly. Decide: the one person whose choice most changes your user's outcome is the second PLAYER, and everyone else is an INFLUENCER. Then confirm: "It sounds like [name] is the one whose decision matters most here. Is that right?" | `update_stakeholder.game_role` |
| `power` | "Can [name] stop this from happening, or just make it harder?" (options: Can stop it / Can make it harder / Not really / Not sure) | `can_veto`, `can_delay`, `authority`, `influence` |
| `wants_fears` | "What do you think [name] wants most here? What might they be worried about?" | `goals`, `fears` (source `user_stated` with quote when given) |
| `my_options` | "What are the things you could do? Even doing nothing for now counts." Suggest one or two they missed. | `add_action` × 2–3 for "You", including a wait option |
| `their_options` | "How might [name] respond? What could they do?" | `add_action` × 2–3 for the other PLAYER |
| `limits` | "Is there anything you won't do, or that must not happen, whatever else happens?" | `add_constraint` (ETHICAL / SAFETY / LEGAL / POLICY) |
| `outcomes` | For each pair of choices: "If you [option] and [name] [their option], how would that be for you?" (Great / OK / Hard / Very hard). Then: "And for [name], do you think?" Ask about the 3–4 most important pairs. Fill the rest from what you've learned, marked `ai_inferred` | `set_payoff` with 3–4 dimensions, chosen silently from the ones that matter (e.g. WELLBEING, RELATIONSHIPS, SAFETY, COST). Answer → score: Great 8–9, OK 5–6, Hard 3, Very hard 1 |
| `likelihood` (optional) | "How likely do you think it is that [name] would [option]?" (Very likely / Maybe / Unlikely) | `add_belief` (0.8 / 0.5 / 0.2) |

Rules:
- Ask at most about 12 questions before the first recap. If the user seems tired or upset, offer a short version: "I can give you a first view now and we can fill in more later."
- When the user gives several facts at once, save them all and skip the slots they've already answered.
- If the user corrects a recap, fix it with `propose_changes` and thank them briefly.
