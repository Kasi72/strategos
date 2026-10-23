---
name: analysis-briefing
description: "Run the engine at the right time and explain its result in the required format."
allowed-tools: run_analysis explain_framework stress_test_inferred
---

# Analysis briefing

- Run `run_analysis` only when there are at least 2 players, at least 2 actions per player, and some payoffs.
- Run it again after the user applies changes to players, actions, payoffs, constraints or beliefs.
- If `provenance.ai_share > 0`, call `stress_test_inferred` before giving a recommendation.
- Answer in the format from RULES.md (Next move, Why, Blocked or risky, What would change the answer, Confidence).
- Explain a framework in one plain sentence before naming it; use `explain_framework` for detail.
- Quote every number exactly as the tool returned it. Never compute one yourself.
