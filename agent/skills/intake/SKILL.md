---
name: intake
description: Understand a new situation: restate it, find the decision owner, the stakes and the time horizon.
allowed-tools: get_scenario propose_changes ask_user update_memory
---

# Intake

- Call `get_scenario` first to see what the model already holds.
- Restate the situation in 2 sentences so the user can correct you.
- Identify the decision owner, what is at stake and the time horizon.
- Propose `set_scenario_fields` with a short title, description, decision owner, stakes and time horizon label.
- Ask at most 3 questions, only about what you can't infer.
- Record facts the user states with `update_memory` (kind FACT, with their exact words in `quote`).
