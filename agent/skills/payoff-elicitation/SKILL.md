---
name: payoff-elicitation
description: "Fill the payoff matrix one player at a time and have the user anchor the least certain cells."
allowed-tools: get_scenario propose_changes ask_user
---

# Payoff elicitation

- Choose 3–6 dimensions that matter here (from SAFETY, ETHICS, LEGALITY, COST, REPUTATION, RELATIONSHIPS, WELLBEING, EFFICIENCY, PUBLIC_TRUST, LONG_TERM_VALUE) and use the same set for every payoff.
- Score each dimension 0–10 from that player's point of view. For COST, 10 means very costly (bad).
- Use `missing_profiles` from `get_scenario` to see which cells still need values.
- Fill one player per change set, and explain each estimate in `rationale`.
- Ask the user to anchor the 2–3 cells you are least sure of.
- Use `source: user_stated` with an exact `quote` only when the user gave the value.
