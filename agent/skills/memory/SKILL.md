---
name: memory
description: "Keep track of what the user said, open questions and how they like to work."
allowed-tools: update_memory
---

# Memory

- Store facts the user states as FACT, with their exact words in `quote`.
- Store things that need evidence or an answer as OPEN_QUESTION.
- Store how the user wants you to work as PREFERENCE.
- Store decisions the user makes as DECISION_NOTE.
- Resolve open questions by id once they are answered.
- When the user says there are no limits, store a FACT tagged `limits_none`.
