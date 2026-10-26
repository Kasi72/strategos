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

- [x] **`XRayPriors.withPriors(scenario, overrides)` (new, pure)** — the re-rank primitive the design
  calls `model.withPriors(it)`, mapping the sliders to the counterpart's Bayesian fields
  (`probRespectsBoundary` / `probSoftensOnRepair` / `probEscalatesOnReactive` / `trustBaseline`),
  clamped. The live per-frame re-rank reuses the existing `AnalysisPipeline` on the varied scenario
  (`WhatIfRunner` already does this) — no new ranking engine. `XRayPriors.of(s)` lists the sliders. _Done UX.8._
- [x] **User-prior persistence (`XRayRepository`, new)** — `keepAsMine` writes the dragged priors and
  vouches for the person (`USER_ESTIMATE`, so Memory badges them yours); `reset` clears back to the
  engine's defaults (open question #2). Saved through `ScenarioRepository` (user action, not a
  ChangeSet). Wired in `AppContainer`. _Done UX.8._
- [x] **`NotEnough(question, options)` (`XRayNotEnough`, new)** — surfaces the engine's exploratory /
  no-clear-pick skip as a plain question built from the first missing fact; decoupled from
  `DecisionReport` (takes the two signals the VM already reads) for testability. _Done UX.8._
- [x] **Accept:** `XRayPriorsTest` (labels, clamp, keep-as-mine read-back, reset, NotEnough rules &
  phrasing) + `XRayRepositoryTest` (persist-as-yours, reset).
  _Note: the UI drives the conflated re-rank via `AnalysisPipeline().run(withPriors(...))`; no engine
  internals were changed, keeping the 77 `FrameworkTests` untouched._

## Phase UX-9 — Settings: Reveal the math
Feeds: pill visibility + one-line peek. Board: Cutlist Q#1, Settings.

- [x] **`Settings.revealMath` = `ALWAYS | ON_TAP | NEVER` (new `RevealMath` enum + `ui.reveal_math`
  key)** in `SettingsRepository`, with `revealMath()`/`setRevealMath()`/`observeRevealMath()`. _Done UX.9._
- [x] **Accept:** `RevealMathSettingTest` (default On-tap, persist/read-back, observable, bad value → On-tap).

## Phase UX-10 — Journal link suggestions + cross-project carry-over
Feeds: LinkSuggestionChip, "Bring over what I know about X?". Board: Hub, Cutlist Q#3.

- [x] **Link-suggestion for existing entries (new)** — `JournalRepository.linkSuggestions(id)` surfaces
  unlinked entries the screener tied to a project; `link(entry, scenario)` attaches (user-confirmed,
  `JournalDao.setLinkedScenario`), `dismissLink` drops the suggestion leaving the entry global. _Done UX.10._
- [x] **Cross-project person carry-over (`CrossProjectPeople`, new)** — detects a familiar but blank
  person in a new project and offers a one-time copy; `bringOver` copies wants/fears/role as
  `AI_INFERRED` (flagged for review, brains stay separate — open question #3). Exposed via
  `ProjectBrainRepository.carryOverSuggestions`/`bringOverPerson`. _Done UX.10._
- [x] **Accept:** `JournalLinkSuggestionTest` (suggest/link/dismiss, entry stays global on dismiss) +
  `CrossProjectPeopleTest` (suggest familiar-blank, skip known, flagged copy, separate ids).

---

## Status
All ten backend phases complete; `./gradlew :app:testDebugUnitTest` green and `:app:compileDebugKotlin`
clean. Backend read models + repositories are in place for the redesigned Overview (dashboard),
Projects list, X-ray and journal/settings surfaces.

## UI slices (DESIGN.md §4)
`MainActivity` now launches `ui/calm/CalmApp` (Projects -> Overview -> Talk; dock = Projects / Journal /
Settings). The old tab UI is still compiled but unreachable.

Done and verified on the emulator against a real project:
- [x] **Theme & tokens** — `ui/calm/theme` (light/dark, ground brush, serif wordmark, lifted `calmCard`).
- [x] **Projects (board 04)** — composer, cards with momentum spark + soft word, first-run examples,
  long-press Rename (dialog) / Archive / Delete (confirm dialog).
- [x] **Project Overview (board Hub)** — momentum chart + projections + event pins, quick-check, standing
  Do's/Don'ts, pattern card (+Wikipedia link), conversations, memory with yours/inferred badges,
  influence map, journal teaser, Continue/Start chat FAB.
- [x] **Review AI inputs** — sheet with Keep / Remove / Accept all (banner count updates live).
- [x] **Add something the app should know** — free user fact.
- [x] **X-ray sheet (boards 07/08)** — graph-paper sheet, 3x2 score grid derived from the scenario's own
  payoffs, headline + Robust/Fragile pinned to the engine's directive (never contradicts the chat), odds
  slider recomputes the pure grid live, Keep as my estimate / Reset, Why this works.
- [x] **Settings** — Reveal the math (Always / On tap / Never) replaces "Show details"; model picker shown
  whenever a key is connected.

Interim (still the proven legacy screens, hosted inside the calm shell): **Talk**, **Journal**, **Settings
(key/model/privacy)**. They work but are not yet restyled.

Remaining:
- [ ] Talk redesign: serif coach card, engine pill that honours Reveal the math (the setting is stored but
  nothing reads it yet), topic chips, composer with dictation; Overview thread rows should open that thread.
- [ ] Journal redesign: week strip + month calendar, per-project Journal tab with link suggestions
  (`JournalRepository.linkSuggestions/link/dismissLink` are ready).
- [ ] Settings visual redesign (board 11) incl. "Delete everything" typed confirm; Archived list;
  onboarding (board 03) and the States board (13): empty / error / offline / long content.
- [ ] Real fonts (Figtree, Source Serif 4, IBM Plex Mono) — platform families stand in today.
- [ ] X-ray second slider (third-party "backs your limit") — needs a BLUEPRINT decision; only the
  "takes it well" odds are wired.

_Note: `ProvenanceDefaultsTest.TC_PROV_02` is a pre-existing time-flake (two `Scenario()` defaults a
millisecond apart); it passes on rerun._
