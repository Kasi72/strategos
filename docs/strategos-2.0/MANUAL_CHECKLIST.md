# Manual checklist for Phase 7 (CP4)

Run on a phone or emulator, in light and dark themes. This is TEST_CASES §10 adapted to the SIMPLE PROFILE (one message in, one plain answer out; no interview cards, no change-set review in the main flow). Items I already checked on an emulator (strategos_api34, no real key) are marked ✔ with what I saw.

## Needs your Groq key (I did not type it anywhere)
- [ ] **K1. Fresh install → Settings → paste key → Save and connect.** Expect "Connected · key ending ####" and a privacy card with **I understand**. After that, Home's button should read "Start counsel →" without restarting the app.
- [ ] **K2. Home → describe a situation → Start counsel.** You land on Talk. A plain answer arrives with "What I understood", "Here's what I see", "Options to consider", "What to watch for". Try the loan story from `docs/strategos-2.0/API_NOTES.md`, or your own.
- [ ] **K3. Follow-up.** Write what happened next ("I decided to wait and he said he'll ask our parents"). The answer should update and may change the recommendation. Nobody should appear twice in Summary.
- [ ] **K4. Stop** mid-turn (square button) → "Stopped."; sending again works.
- [ ] **K5. Airplane mode** → send a message → offline error with Try again; the saved model is untouched; works again online.
- [ ] **K6. Undo** (⋮ menu → Undo last change) after a turn: the people and options from that message disappear.
- [ ] **K7. Show details (Settings) →** Talk shows a strip ("2 players · …") and an "Engine result" card; tap "% AI" → **Review AI inputs**: Keep / Confirm / Remove / Accept all work and the % drops.
- [ ] **K8. Model dropdown** (Show details on): it lists only text models, `openai/gpt-oss-20b` first with "Recommended". Pick `openai/gpt-oss-120b`; the next answer uses it.
- [ ] **K9. A wrong key later** (Change → paste junk is rejected; or revoke the key in the Groq console and send a message) → Talk shows "Your Groq key stopped working" with Update key.
- [ ] **K10. Crisis phrase** (for example "I want to die") → the support card shows at once, even in airplane mode. **Your clinician must review the phrase list and the helpline text before patients use this.**

## Checked on the emulator without a key
- ✔ **U1. Fresh install, Guided (default):** Home shows "What's on your mind?", the AI-off hint and the 4-item dock (Home · Talk · Summary · Settings).
- ✔ **U2. Wrong key:** "Groq didn't accept this key. Check it and try again." in red; the pasted text stays in the field; nothing saved.
- ✔ **U3. Show details switch** swaps to the Expert dock (Home · Talk · Model · Report · More) and keeps you in Settings (More ▸ Settings).
- ✔ **U4. Expert + AI off:** Home asks "What decision are you facing?"; Build model → the Frame tab with your text (1.x behaviour, no network).
- ✔ **U5. Seeded conversation (made with the app's own code):** Talk shows the answer with bold headings, your message, the error card with Try again, the composer with mic and send; Expert adds the strip, activity line and engine card.
- ✔ **U6. Summary** reads as plain text and has Share with my clinician (opens the Android share sheet only when tapped).
- ✔ **U7. Review AI inputs** lists the AI guesses; **Report** has Decision and Frameworks sub-tabs; Model rows show the "AI" chip.
- ✔ **U8. Dark theme** renders correctly on Talk.
- [ ] **U9. Upgrade from 1.x** with two saved scenarios (done once in Phase 2.7; re-check on the release build).
- [ ] **U10. TalkBack** reads every button and chip (icons have labels; not run).

## Known gaps (not built)
- **Tamil:** UI strings are English only and the app-written answer is English. (The model reads Tamil input; the keyword screen has Tamil phrases.)
- **Clinician PIN** for Show details and the key field (TC_KEY_08).
- **Voice input:** mic buttons added, not tested on a device with speech services.
- **Encrypted backup export/import (G4)**, the monthly usage line (G1, dropped by your decision).
- **ProvenanceChip** is on stakeholder and action rows; constraint and payoff rows in the editors have none yet.
