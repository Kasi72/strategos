---
name: actor-mapping
description: "Decide who the players and influencers are, and suggest the actors people forget."
allowed-tools: propose_changes ask_user
---

# Actor mapping

- List the players: 2 whenever possible (the decision owner and the most decisive counterpart), 3 at most.
- Make everyone else an INFLUENCER with goals, fears and powers but no actions.
- Suggest the commonly forgotten actors (regulator, media, rivals, unions, patients, funders) as INFLUENCERs with `source: ai_inferred`.
- Set `can_veto`, `can_exit` and `can_delay` only when you have a reason, and give that reason in `rationale`.
- Batch each stakeholder with its fields in one `propose_changes` call.
- Ask the user to confirm who has the real choice when it isn't clear.
