# Strategos — screen-by-screen UX audit

Captured live on an Android emulator (`strategos_api34`, API 34) against a synthetic demo
scenario seeded by the project's own `ManualSeedDemoTest` ("Loan for my brother" — nobody's
real data; built from the same code path the app runs). No real conversation, from any
user's phone, appears in this folder, in keeping with `CLAUDE.md`'s rule against persisting
user text or scenario content.

The app has **two parallel UIs that share the same data**: a short **Guided** dock (the
default) and a much larger **Expert** dock, switched with one toggle in Settings
("Show details"). This audit's main finding is that the Expert surface has grown far past
what the stated purpose of the app — a personal decision-support companion — needs, and that
several screens duplicate each other under different navigation paths. Per-screen notes below
end with a **Keep / Cut / Merge** call for the redesign exercise.

---

## Guided mode (5-item dock: Projects · Overview · Talk · Journal · Settings)

### `01_guided_home.png` — Projects (Home)
The landing screen. One composer ("What's on your mind?") to start a new decision, plus a
list of past ones ("Earlier"). This is the whole of Guided mode's entry point — it intentionally
hides everything about players, payoffs or frameworks.
**Why it's here:** the single on-ramp for a non-technical user; the entire pitch of "Guided"
mode is that this is the only screen you need to understand to get started.
**Keep.** This is the product's actual front door and reads cleanly.

### `02_guided_talk.png` / `03_thread_switcher.png` / `04_guided_talk_main.png` — Talk
The chat screen: a thread switcher ("Main Thread ▾ · + Thread"), a journal-triggered post-note
banner, the conversation itself (AI reply, user follow-up, an error card), and the composer.
`03` is the thread-switcher dropdown open; `04` is the Main Thread with the actual seeded
exchange, including a real error state ("The API key was not accepted").
**Why it's here:** this is the product — a conversation that turns into a plain-English
recommendation grounded in an on-device game-theory engine.
**Keep**, but flag two things: (1) opening a project lands you on whichever thread was last
active, not necessarily "Main Thread" — on first open this showed an *empty* secondary thread
instead of the real conversation, which is confusing; (2) the "+ Thread" feature (multiple
named conversations per project) is a real feature but has no onboarding — nothing tells a
guided user what a "thread" is or why they'd want a second one.

### `05_guided_overview.png` — Overview
A trajectory chart (trust/tension over time, projected forward), a "Based on your journal
entry" post-note card with Apply/Dismiss, and a Quick-Check prompt ("How did the last
interaction go?").
**Why it's here:** this is the "Space 3" dashboard from the Argon redesign — the idea that a
relationship is tracked over time, not just turn-by-turn.
**Keep the trajectory chart and Quick-Check** (they're the one genuinely stateful, longitudinal
feature in the app). **Question the post-note card's duplication** — the same card can also
appear inline in Talk (see `04`) and under Expert ▸ More ▸ Overview (`20`). One home for it,
not three.

### `06_guided_journal.png` — Journal
A private, non-judgmental reflection composer ("What's on your heart?") with mood tags and a
project filter. No entry timeline was visible in this capture.
**Why it's here:** deliberately separate from Talk so venting never gets treated as a decision
input — a real design intent worth preserving.
**Keep the separation. Cut or redesign the empty state** — a journal with nothing to show below
the composer, on a tab of its own in a 5-item dock, is a lot of chrome for one text box.

### `07_guided_settings.png` — Settings (Guided)
AI counsel on/off, DeepSeek key status, model name (read-only in Guided), "Show details"
toggle, privacy link, delete-all.
**Keep.** Short, legible, does exactly what Settings should.

---

## Mode switch

### `08_expert_settings_toggled.png` — Settings, right after flipping "Show details" on
Same screen, but the dock behind it has already become the 5-item Expert dock (Projects · Talk
· Model · Report · More), and the Model field gained a dropdown + refresh button.
**This single toggle is where the app's complexity roughly doubles.** Worth designing
deliberately rather than as a binary switch — see the overall recommendation below.

---

## Expert mode

### `09_expert_home.png` — Projects (Expert)
The same composer as Guided, but now followed by a scenario card showing **Draft · 90%**, a
4-line stats row (players/actions/constraints), a "Build decision model" banner, and a
"Decision Health" section (Readiness 90%, Evidence 24 entries — truncated by the dock).
**Why it's here:** a dashboard view of scenario completeness, structural-completeness scoring
and evidence tracking — vocabulary from the original 1.x consulting-tool design.
**Cut or demote.** "Readiness 90%" here directly contradicts what Report ▸ Frameworks says
about the same scenario (`14`, below: "Decision readiness: Not ready"). Two numbers that
disagree about the same thing, on two different screens, is a trust problem, not a feature.

### `10_expert_talk.png` — Talk (Expert)
Identical to Guided Talk, with one addition: a model strip under the title —
`2 players · 2 influencers · 6 options · 18/18 outcomes · 100% AI`.
**Keep the strip, it's genuinely useful** (answers "what does the model actually contain right
now" in one line) — this is the feature a user asked about directly in this project's own
testing and got real value from once it was explained.

### `11_expert_model_frame.png` / `12_expert_model_editor.png` — Model ▸ Frame / Model
`Frame`: scenario title, description, "Add context" (clipboard/upload/photo/mic), decision
owner. `Model`: the same Frame content again, followed by Decision Governance (decision rule)
and a Stakeholders list with AI/Authority/Influence chips.
**Why it's here:** the 1.x manual model-editing UI, predating the chat-driven Argon flow.
**Cut or fold into Talk.** Frame's content is *literally reprinted* inside Model's scroll —
the same title/description/owner fields appear twice, once per sub-tab, with no indication
they're the same fields. For an app whose stated new direction is "describe it in your own
words, I'll build the model," a manual two-level structural editor duplicating chat-derived
data is the single largest source of redundant surface area in the app.

### `13_expert_report_decision.png` / `14_expert_report_frameworks.png` — Report ▸ Decision / Frameworks
`Decision` has **four more sub-tabs** (Executive/Strategic/Technical/Console) and, in this
capture, "No report yet." `Frameworks` shows Structural Completeness (90%), Evidence quality
("None"), Decision readiness ("Not ready"), and a Run Analysis button.
**Why it's here:** the original 1.x multi-audience report system (an executive summary vs. a
technical appendix vs. a raw console), aimed at a professional/consulting use case.
**Cut three of four sub-tabs, or cut the whole thing for personal use.** Nesting — Report ▸
Decision ▸ Executive/Strategic/Technical/Console — is four navigation levels deep for a single
piece of content, in an app whose Talk tab already renders a plain-English version of the same
recommendation for free, every turn.

### `15_expert_more_settings.png` / `20_expert_more_overview.png` — More ▸ Settings / Overview
`More` is a second-level dock holding Overview, Journal, Map, Implement and Settings as
sub-tabs — i.e., Overview and Journal (already full top-level tabs in Guided mode) and
Settings (also reachable directly) all exist a second time here.
**Cut the duplication.** Three of More's five sub-tabs are the same screens Guided mode already
puts one tap away. The only two genuinely Expert-only items here are Map and Implement.

### `16_expert_more_map.png` — More ▸ Map (Stakeholder Map)
A node-and-edge "Influence Diagram" (You / Parents / Brother / Wife) plus a text list with
Role/Authority per person.
**Keep, but relocate.** A real, distinctly useful visualization — it's just buried three taps
deep (More ▸ Map) under a mislabeled parent ("More"). If kept, it deserves to be reachable from
the Model or Overview screen that already describes these people, not a junk-drawer tab.

### `17_expert_more_implement.png` — More ▸ Implement (Implementation Dashboard)
A dense governance checklist: "Hold for Verification," seven blocking reasons (no approved
decision snapshot, no policy option selected, no approval stages, no implementation owner, no
monitoring indicators, no stop/reversal plan…), a ten-row readiness table, and a "Close"
lifecycle action.
**Cut.** This is boardroom/organizational-decision language — "approval stages,"
"implementation owner," "monitoring indicators" — applied to *"My brother wants me to co-sign a
loan."* It's the clearest single example in the app of 1.x enterprise-consulting scope that
was never re-aimed at the personal-decision use case the product has actually become. Nothing
on this screen is something a person deciding whether to co-sign their brother's loan would
ever fill in.

### `18_review_ai_inputs.png` — Review AI inputs
A full-screen sheet reachable from the "100% AI" chip in Talk: "28 values were guessed by the
AI. Keep what looks right, remove what doesn't," then one card per guessed fact (person,
option, …) with Keep/Confirm/Remove.
**Keep.** This is the app's actual answer to "is the AI making things up" — the mechanism this
whole project's testing kept running into needing. It's good; it's just hidden behind a small
orange percentage chip with no other entry point.

### `19_talk_no_active_scenario.png` — Talk with no active scenario
A dead-end: "Start from Home / Tell me what's on your mind and I'll set it up here" with a
single "Go to Home" button. Reached by switching dock tabs in a way that loses the active
project.
**Cut as a dead end** — Talk should either remember the last project automatically or the dock
itself should disable/grey the Talk icon when nothing is active, rather than present a
screen whose only job is to send you back where you came from.

---

## Overall recommendation for the redesign

**The actual product, based on what gets used and works well in this audit, is three screens:**
Home (describe your situation) → Talk (the conversation + plain recommendation + the "% AI"
review) → Overview (the trajectory + journal + quick-check, for people who come back over
time). The Journal composer is a fourth, deliberately-separate screen worth preserving as-is.

**Everything under Expert mode beyond the model strip and Review AI inputs is 1.x
consulting-tool scope** (Frame/Model editors, the four-deep Report hierarchy, the
Implementation governance checklist, the duplicated More tab) that predates the chat-driven
redesign and has not been re-justified against what a person using this app for a personal
decision actually needs. "Show details (for professionals)" as a single on/off switch is
trying to bridge two different products with one toggle; the redesign's first decision should
be whether Strategos is one app or two, because right now flipping that switch roughly
quadruples the screen count without a correspondingly clear new audience being named anywhere
in the UI.
