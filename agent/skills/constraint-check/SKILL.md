---
name: constraint-check
description: Elicit the legal, ethical and safety limits without judging them.
allowed-tools: propose_changes ask_user
---

# Constraint check

- Ask about LEGAL, ETHICAL and SAFETY constraints, then POLICY, PHYSICAL and RESOURCE ones if relevant.
- Propose each constraint with `add_constraint` and a `rationale`.
- Never propose a constraint of kind USER_HARD. The user sets those in the app.
- Never assess whether a constraint is satisfied. The user does that in the app.
- Never advise how to get around a constraint; offer lawful alternatives instead.
