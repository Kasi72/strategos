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

- [x] **`MomentumStatus` (new, pure function)** — `from(trustSlope, tensionSlope) → improving | steady | tense`.
  _Done UX.1: `domain/analysis/MomentumStatus.kt`, with `slope()`/`of()` helpers._
- [x] **`TrajectoryRepository.recent(id, n)` (new)** — last `n` points as a series; `momentum(id)` word.
  _Done UX.1._
- [ ] **Projects combined read** — expose scenario + recent trajectory together (currently
  `ScenarioRepository.scenarios` has no trajectory) so the card shows spark + word without an N+1.
  _Deferred to the Projects UI slice: `ProjectsViewModel` combines `scenarios` with per-id `momentum`;
  the repo methods above are the building blocks._
- [x] **Accept:** unit tests for `MomentumStatus.from` boundaries; `slope` tested (`MomentumStatusTest`).

## Phase UX-2 — Conversation landing + rows
Feeds: Chat FAB label/target, Conversations section. Board: Compose, Hub.

- [x] **`ConversationRepository.landingThread(id)` (new)** — newest thread if it has a visible
  message, else the original Main; null when no thread yet. _Done UX.2._
- [x] **`messageCount(scenarioId)` (new)** — visible (USER/ASSISTANT) total across threads; drives
  "Start chat" vs "Continue chat". _Done UX.2 (`MessageDao.countVisibleForScenario`)._
- [x] **Thread rows (`ThreadSummary`, new read)** — last visible line (raw), `lastAt`, `isOpenNow`,
  shared with the chat button. _Done UX.2._
- [x] **Accept:** `ConversationLandingTest` (empty → null/Start; empty newest → Main; active newest →
  Continue; summaries carry last line + open flag).

## Phase UX-3 — ProjectBrainRepository (Memory)
Feeds: "What the app knows", model strip, Review AI inputs. Board: Compose, Hub.

- [x] **`ProjectBrainRepository` (new)** — read model (`ProjectBrain`) over the scenario + free user
  notes. Groups facts by person (Wants / Fears / Role, self first) + a Limits group; every
  `BrainFact` carries `Provenance.USER | INFERRED`; exposes `inferredCount` (still to review),
  `reviewedCount`, `inferredTotal`, and `ModelCounts` (people · options · outcomes). _Done UX.3._
  - New `Provenance` enum + `FactStatus.provenance()` (`domain/model`); pure `ProjectBrain.from(s)`
    (`domain/agent`); reviewed = AI-origin fact (keeps its `provenanceNote`) promoted off AI_INFERRED,
    so the "N of M reviewed" strip needs no new storage. Wired in `AppContainer`.
- [x] **Memory write paths** — `addFact` (USER_ESTIMATE free note), `editNote`/`removeNote`
  (`MemoryDao.setText` added), and `review(item, KEEP|CONFIRM|REMOVE)` reusing `AiReview` + saving
  through `ScenarioRepository` (user action, not a ChangeSet). Agent still can't write `CONFIRMED`.
- [x] **Accept:** `ProjectBrainTest` (grouping, provenance, counts, reviewed derivation) +
  `ProjectBrainRepositoryTest` (add/edit/remove note, review keep/remove).

## Phase UX-4 — Standing guidance ("What to do")
Feeds: What-to-do section. Board: Hub.

- [x] **Guidance aggregation (`GuidanceRepository`, new read)** — latest coach Do's/Don'ts per thread
  merged with applied journal takeaways, de-duplicated by (kind, text) newest-first, each `GuidanceItem`
  carrying its source (THREAD/JOURNAL), source label and a `refId` to jump to the reply/entry. _Done UX.4._
  - Coach guide is now persisted: `OneShotTurn` writes the `CoachGuideSketch` onto the reply's existing
    `payload_json` (no schema change) via `GuidanceCodec`, and sets the message `refId` to itself.
    Pure merge/dedup in `StandingGuidance.of`. Applied journal notes via `JournalDao.appliedPostNotes`.
    Wired in `AppContainer`.
- [x] **Accept:** `GuidanceTest` (separation, dedup newest-wins, kind-keyed, ordering, codec) +
  `GuidanceRepositoryTest` (per-thread latest, cross-thread merge, applied journal takeaways,
  pending note excluded).

## Phase UX-5 — PatternCatalog
Feeds: Overview pattern card + X-ray "Why this works" (one shared source). Board: Compose, Hub.

- [x] **`PatternCatalog` (new)** — archetype id → { plain name, 3-sentence explainer, variable
  templates filled from the scenario (limit / their need / third party / the doubt), Wikipedia URL },
  for the 5 behavioral modules + `ultimatum` + the generic `pure-nash`. Accepts the trajectory's
  UPPER_SNAKE form. _Done UX.5._
  - `EngineDirective` now carries `dynamicArchetype` (the top module id) so the Overview/X-ray can look
    it up; `PatternCatalog.forDirective(d, s)` is the convenience. Shared by both surfaces.
- [x] **Accept:** `PatternCatalogTest` (every archetype → name/3-sentence summary/wiki; unknown → null;
  UPPER_SNAKE; Trust-trap→Prisoner's-dilemma page; variable-fill from `Fx.loanFamily`).

## Phase UX-6 — Momentum events + Quick-check
Feeds: Momentum chart pins/event list, Quick-check card. Board: Hub.

- [x] **Event timeline (`MomentumEvents`, new read)** — deterministic pins (invariant I12, no LLM):
  the start, each quick-check (labelled from its coded answers, e.g. "You did as suggested; it eased")
  and each journal shift; everyday turns are not pinned. `TrajectoryRepository.events(id)`. _Done UX.6._
- [x] **`TrajectoryRepository.pendingCheck(id)` + `PendingCheck` (new)** — pending once a turn has
  happened with no later quick-check; question names the counterpart ("How did the last talk with
  <Name> go?"), with a fallback. `submitQuickCheck` (existing) remains the write. _Done UX.6._
- [x] **Accept:** `MomentumEventsTest` (pins, check labels incl. DE_ESCALATED ordering, pending rules,
  name fallback) + `TrajectoryRepositoryTest.TC_EVENTS_07` (repo events + pending across a turn/check).

## Phase UX-7 — Influence map
Feeds: "Who pulls on whom". Board: Hub.

- [x] **Influence graph (`InfluenceGraph`, new read)** — nodes (self + inferred flags) and edges with
  `weight` = sway (max authority/influence, floored so a tie still shows), plain labels ("wants their
  way" / "can block this" / "can sway this" / "on your side" / "allied"), and an inferred flag for
  dashed rendering. Derived from `Stakeholder` authority/influence/coalitions/trustRelationships/
  `GameRole`. Exposed via `ProjectBrainRepository.influence(id)`. _Done UX.7._
- [x] **Accept:** `InfluenceGraphTest` (node/self, pull labels, weight+floor, inferred node+edge,
  one alliance edge per pair) from `Fx.loanFamily`.

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
