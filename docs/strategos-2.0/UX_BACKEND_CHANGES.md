# UX redesign — backend change plan

_Created 2026-10-26. Source of truth for the design is `Design/DESIGN.md` and the boards in
`Design/strategos-redesign/boards/*.dc.html` (note: `Design/CLAUDE.md` mis-points to
`docs/design/...`; the boards are under `Design/`). The richest board for data sources is
`Compose.dc.html`; the dashboard itself is `Hub.dc.html`; what must NOT return is `Cutlist.dc.html`._

This plan lists the **backend** work to support the redesign. The UI (per-screen ViewModels,
`UiState`, composables) is built on top of these and tracked separately in the design build order
(`DESIGN.md` §4). The engine, DB and existing repositories are **reused, not rewritten**; we only
add the new classes the Compose board names (`MomentumStatus`, `PatternCatalog`,
`ProjectBrainRepository`) plus repository methods and read models.

Work **one phase at a time** (per `CLAUDE.md`). A phase is done only when
`./gradlew :app:testDebugUnitTest` is green and the 77 `FrameworkTests` still pass. One commit per
numbered step.

## Legend
- **Exists** — already in the codebase, no change.
- **New** — a new class/method/read model.
- Each item cites the Overview section it feeds and the design board.

---

## Phase UX-1 — Momentum status + Projects list data
Feeds: Projects list (card sparkline + status word), Overview header word. Board: Compose, Projects.

- [ ] **`MomentumStatus` (new, pure function)** — `from(trustSlope, tensionSlope) → improving | steady | tense`.
- [ ] **`TrajectoryRepository.recent(id, n)` (new)** — last `n` points as a series; a `slope` helper.
- [ ] **Projects combined read** — expose scenario + recent trajectory together (currently
  `ScenarioRepository.scenarios` has no trajectory) so the card shows spark + word without an N+1.
- **Accept:** unit tests for `MomentumStatus.from` boundaries; `recent`/slope tested.

## Phase UX-2 — Conversation landing + rows
Feeds: Chat FAB label/target, Conversations section. Board: Compose, Hub.

- [ ] **`ConversationRepository.landingThread(id)` (new)** — last active thread if it has messages,
  else Main.
- [ ] **`messageCount(scenarioId)` (new)** — total across threads; drives "Start chat" vs "Continue chat".
- [ ] **Thread rows (new read)** — last message text (truncated), relative time, `isOpenNow`.
- **Accept:** tests for landing rule (empty project → Main + Start; active thread → Continue).

## Phase UX-3 — ProjectBrainRepository (Memory)
Feeds: "What the app knows", model strip, Review AI inputs. Board: Compose, Hub.

- [ ] **`ProjectBrainRepository` (new)** — read model over scenario + learned priors + AI-input
  ledger. Groups facts by person (Wants / Fears / Role) + a Limits group; every line carries
  `Provenance.USER | INFERRED`; exposes `inferredCount` and model counts (people/options/outcomes,
  reviewed count).
- [ ] **Memory write paths** — `onEditFact` / `onAddFact` set `Provenance.USER` (never agent →
  `CONFIRMED`, per hard rule); `onReview` advances the reviewed ledger.
- **Accept:** grouping + provenance tests; reviewed counter increments.

## Phase UX-4 — Standing guidance ("What to do")
Feeds: What-to-do section. Board: Hub.

- [ ] **Guidance aggregation (new read)** — latest `do_this`/`dont_do_this` merged across all
  threads + applied journal takeaways, de-duplicated by text, each with a source label and a
  `refId` to jump to the originating reply.
- **Accept:** dedup + provenance/refId tests.

## Phase UX-5 — PatternCatalog
Feeds: Overview pattern card + X-ray "Why this works" (one shared source). Board: Compose, Hub.

- [ ] **`PatternCatalog` (new)** — archetype label → { plain name, 3-sentence explainer, variable
  templates filled from the scenario (limit / their need / third party / the doubt), Wikipedia URL }.
  Extends the five `EngineDirectiveBuilder.PLAIN_LABEL` labels.
- **Accept:** every archetype resolves to name + explainer + valid wiki URL; variable-fill from a
  fixture scenario.

## Phase UX-6 — Momentum events + Quick-check
Feeds: Momentum chart pins/event list, Quick-check card. Board: Hub.

- [ ] **Event timeline (new read)** — short user-facing labels + dates derived from turn summaries
  and trajectory points (the numbered pins).
- [ ] **`TrajectoryRepository.pendingCheck(id)` + `CheckIn` (new)** — whether a check is pending and
  the dynamically generated question ("How did the last talk with him go?"); `onSave` writes a `CheckIn`.
- **Accept:** pending-state + question-generation tests; event labels tested.

## Phase UX-7 — Influence map
Feeds: "Who pulls on whom". Board: Hub.

- [ ] **Influence graph (new read)** — nodes + typed edges with weight = sway, dashed = inferred,
  plain-language edge labels ("shares your limit", "pushing for a yes", "can tip it"), derived from
  `Stakeholder` authority/influence/coalitions/trustRelationships/`GameRole`.
- **Accept:** weight + inferred-flag + label tests from a fixture.

## Phase UX-8 — X-ray engine wiring
Feeds: X-ray sheet. Board: Compose, Xray/Prototype.

- [ ] **`RecommendationEngine.rank(model)` + `model.withPriors(Map<PriorId, Float>)` (new)** —
  lightweight 3×2 re-rank for the what-if sliders (conflated per frame).
- [ ] **User-prior persistence (new)** — "Keep as my estimate" writes a `yours`-badged prior per
  project (shown in Memory with Reset); coach told it's user-supplied, never silently overwritten
  (open question #2).
- [ ] **`NotEnough(question, options)` (new shape)** — surface the engine's "not enough input" skip
  as a clarifying question (reuse phase-G elicitation).
- **Accept:** re-rank changes order under prior override; prior persists as USER; NotEnough path tested.

## Phase UX-9 — Settings: Reveal the math
Feeds: pill visibility + one-line peek. Board: Cutlist Q#1, Settings.

- [ ] **`Settings.revealMath` = `Always | OnTap | Never` (new key + enum)** in `SettingsRepository`.
- **Accept:** default is On-tap; persisted + observable.

## Phase UX-10 — Journal link suggestions + cross-project carry-over
Feeds: LinkSuggestionChip, "Bring over what I know about X?". Board: Hub, Cutlist Q#3.

- [ ] **Link-suggestion for existing entries (new)** — screener proposes linking an already-written
  unlinked entry to a project (separate from a takeaway).
- [ ] **Cross-project person carry-over (new)** — detect a familiar person name in a new project and
  offer a one-time suggest-don't-merge copy (open question #3).
- **Accept:** suggestion emitted + Apply/Dismiss reuse post-note handlers; no silent filing.

---

## Already exists (no backend change)
Engine robustness / payoffs / `stressVerdict` (ROBUST/FRAGILE); `ScenarioRepository.scenarios`
StateFlow; thread CRUD; `TrajectoryCalculator` positive/negative projections; `FactStatus`;
`ProvenanceStats` counts; journal encryption + screener + post-note Apply/Dismiss + multi-project
fan-out + per-project filter + mood tags; archetype detection + the five plain labels.
