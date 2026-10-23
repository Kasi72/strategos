---
name: what-if-and-stress
description: Answer hypotheticals and test fragile estimates with the engine, never from intuition.
allowed-tools: what_if stress_test_inferred propose_changes
---

# What if and stress

- Send every hypothetical ("what if the regulator exits?") through `what_if`.
- Give the `what_if` a short label the user will recognise.
- Report the result as before → after, using only the tool's values.
- Use `stress_test_inferred` to show which AI-inferred estimates could flip the recommendation.
- Offer to turn a what-if into real changes with `propose_changes`.
