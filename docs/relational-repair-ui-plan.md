# Strategos — UI Plan: Surface the Relational Repair Game

**Prepared for:** the Strategos Android developer
**Branch to build on:** `feature/relational-repair-module` (already on origin)
**Goal:** promote the new `relational-repair` module from generic text output to a first-class, visual experience in the UI — a dedicated result card with the melting-prize curve, the maintenance-inequality bar, the trust threshold, and the renewal-survival number, plus an optional interactive "what-if" panel.

---

## 0. Context — what already exists (do not rebuild)

The analysis engine is **done and tested**. The module `RelationalRepairModule`
(id = `"relational-repair"`) is registered in `StrategosViewModel` and runs
through the normal `FrameworkRegistry` pipeline. When a scenario matches (exactly
two stakeholders + relational/repair language, or a repeated 2×2 stag-hunt
structure) it produces an `AnalysisResult` whose `calculations` map already
contains everything the UI needs:

| key in `result.calculations` | type | meaning |
|---|---|---|
| `left_now` | Double | pull-to-stay this period: `S·q·[H+E(H)−R]` |
| `right_now` | Double | pull-to-leave this period: `A − Fe` |
| `survives_now` | Boolean | does the bond clear the bar now |
| `trust_threshold_p_star` | Double? | minimum belief needed to reach first (null if no 2×2 matrix) |
| `t_star` | Int | period after which repair stops being rational (−1 = never within horizon) |
| `prize_at_t_star` | Double | decayed reconnection value at t\* |
| `history_trajectory` | List<Double> | shared-history value per period if maintained (the melting-prize chart series) |
| `horizon` | Int | number of periods simulated |
| `renewal_survival_qT` | Double | `q^T` survival probability |
| `renewal_halflife_periods` | Double | periods until survival halves |
| `terms` | Map<String,Double> | the resolved inputs: H, E(H), R, A, Fe, q, S, history_growth, prize_decay |

**Nothing in the engine needs to change for the read-only UI (Phase 1–2).**
The only reason to touch the module is Phase 3 (interactive recompute), and even
that is a small, additive change described at the end.

### The pattern to copy
There is already a precedent for a module getting its own rich card instead of
the generic one: **`StakeholderTrustDynamicsModule` → `TrustConditionsCard`**.
Look at:
- `app/src/main/kotlin/com/drkkr/strategos/ui/components/TrustConditionsCard.kt`
  — a dedicated `@Composable` that reads a typed object from
  `result.calculations[...]` and draws a custom visualization.
- `app/src/main/kotlin/com/drkkr/strategos/ui/tabs/AnalysisTab.kt` (~line 120)
  — how that card is pulled out of `state.moduleRunResults` by `moduleId` and
  rendered above the generic "Framework Results" list.

**We will mirror this pattern exactly.** If you follow `TrustConditionsCard`,
you cannot go far wrong.

### The gap to be aware of
`FrameworkResultCard` (`AnalysisTab.kt` ~line 232) renders `findings`,
`recommendedActions`, and `explanation` as **text only** — it never looks at
`result.visualizations` (the `VizSpec` list). So today the repair module's
numbers appear only *inside finding text*, and its two `VizSpec`s render as
nothing. That is expected. Phase 1 fixes it with a dedicated card; a generic
`VizSpec` renderer is explicitly out of scope (see "Non-goals").

---

## Non-goals (keep scope tight)

- **No generic `VizSpec` rendering engine.** Tempting, but it balloons scope and
  every module's viz has different needs. Build one dedicated card, like
  `TrustConditionsCard`.
- **No new charting library.** `app/build.gradle.kts` has no chart dependency and
  we are not adding one. All drawing uses Compose `Canvas` + `drawLine` /
  `drawRect` / `drawPath`, consistent with the app's hand-rolled style. (Two
  small charts; a library is overkill and bloats the APK.)
- **No change to navigation / tab structure.** The card lives inside the existing
  `AnalysisTab`, in the existing `LazyColumn`.
- **No new persistence.** Phase 1–2 are pure render of existing result data.

---

## Phase 1 — Dedicated result card (read-only)   ·  ~½–1 day

**Outcome:** when the repair module runs, a rich card appears in `AnalysisTab`
(above the generic Framework Results list), showing the four key visuals. The
generic `FrameworkResultCard` still renders the full findings/actions underneath,
so nothing is lost.

### 1a. New file: `ui/components/RelationalRepairCard.kt`

A single `@Composable fun RelationalRepairCard(result: AnalysisResult, modifier: Modifier = Modifier)`.
Guard clause at the top, same as TrustConditionsCard:

```kotlin
val calc = result.calculations
val left = calc["left_now"] as? Double ?: return
val right = calc["right_now"] as? Double ?: return
```

Read the rest of the keys from the table above (all nullable-safe casts).

Lay it out as a `Surface` card (copy the shell from `TrustConditionsCard`:
`RoundedCornerShape(16.dp)`, `BorderStroke` tinted by outcome) containing, top to
bottom:

**(i) Header** — title "Relational Repair", subtitle citing the mechanism
("Stag hunt · melting prize · renewal burden"), and an outcome pill on the right:
green "Bond holds" if `survives_now`, red "Bond failing" otherwise. (Match the
colour helper in TrustConditionsCard: green `0xFF16A34A`, amber `0xFFD97706`,
red `0xFFDC2626`.)

**(ii) Maintenance-inequality bar** — one horizontal bar comparing
`left_now` (pull-to-stay) vs `right_now` (pull-to-leave). Two stacked mini-bars
or a single diverging bar; label each with its value and the formula
`S·q·[H+E(H)−R] vs A−Fe`. Colour the winning side. This is the "does it survive
now" at a glance.

**(iii) Melting-prize chart** — a small `Canvas` line chart (height ~120.dp,
full width) plotting `history_trajectory` across `0..horizon` on the x-axis.
Draw a vertical marker line at `t_star` (if in `1..horizon`) labelled "t\* —
repair stops being rational". This is the signature visual. See the Canvas
sketch in Appendix A.

**(iv) Two stat tiles** in a row:
- **Trust threshold** — `trust_threshold_p_star` as a percentage ("Reach-first
  threshold: 75%"), or "n/a — add a 2×2 payoff matrix" if null.
- **Renewal survival** — `renewal_survival_qT` as a percentage over `horizon`
  periods, with the half-life underneath ("50% gone in ~7 periods").

**(v) Expandable "Terms" row** (collapsed by default, `remember { mutableStateOf(false) }`
like TrustConditionsCard's `expanded`) — dumps the `terms` map as labelled
chips (H, E(H), R, A, Fe, q, S, growth, decay) so a power user can see the
inputs that drove the result.

Do **not** re-render findings/recommended actions here — the generic card below
already does that, and duplicating is noise.

### 1b. Wire it into `AnalysisTab.kt`

Mirror the Trust Dynamics block (the `trustResult?.let { ... }` around line 120).
Immediately after it, add:

```kotlin
val repairResult = state.moduleRunResults
    .firstOrNull { it.moduleId == "relational-repair" && it.status == ModuleRunStatus.SUCCESS }
    ?.result
    ?: state.analysisResults.firstOrNull { it.moduleId == "relational-repair" }
repairResult?.let { rr ->
    item {
        com.drkkr.strategos.ui.components.RelationalRepairCard(
            result = rr,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}
```

That is the entire integration. The module still also appears in the generic
"Framework Results" list below (fine — one is the visual summary, the other the
full detail). If you'd rather suppress the duplicate, filter `relational-repair`
out of the `items(state.moduleRunResults)` loop the same way skipped modules are
filtered — but recommend leaving it; consistency with Trust Dynamics, which also
double-renders.

### 1c. Acceptance criteria (Phase 1)
- Build a scenario with 2 stakeholders named e.g. "Asha"/"Ben", description
  mentioning drifting/reconnecting, `repeated = true`. Run analysis.
- The RelationalRepairCard appears with: an outcome pill, the inequality bar, a
  melting-prize line with a visible t\* marker, and both stat tiles populated.
- A scenario with a full 2×2 Invest/Withdraw payoff matrix shows a numeric trust
  threshold (75% for the test payoffs a=4,b=0,c=3,d=3); one without shows "n/a".
- Dark mode: all colours legible (use `MaterialTheme.colorScheme` for
  surfaces/text; the three status hexes already work in both per TrustConditionsCard).

---

## Phase 2 — Discoverability & explanation   ·  ~½ day

So a user understands *why* they're seeing this and what to do.

- **Game-type hint.** `GameTypeClassificationCard` already shows at the top of
  AnalysisTab. No change needed, but verify the repair scenario is sensibly
  classified (it will usually read as Stag Hunt / Trust). Optional: add a one-line
  note in RelationalRepairCard — "This bond is being analysed as a repeated
  repair game."
- **Plain-language verdict line** at the top of the card, derived from the data,
  e.g.:
  - survives + t\* far off → "Healthy: reconnection stays worthwhile for the whole horizon."
  - !survives + low S → "At risk: present-tense preference is overriding the history. Protect the commitment."
  - t\* soon → "Act now: the window for easy repair closes around period {t\*}."
  (Pick the single most severe condition; keep it to one sentence.)
- **Link the interventions.** The module already emits `recommendedActions` with
  stable `actionId`s (`intervention-rhythm`, `intervention-focal-point`,
  `intervention-repair-early`, `intervention-reinstrumentalize`,
  `intervention-authorship`, `intervention-compound`). In Phase 2 these still
  render in the generic card. In Phase 3 they become interactive.

---

## Phase 3 — Interactive "what-if" panel (optional, higher value)   ·  ~1–2 days

**Outcome:** sliders for the key terms (S, q, R, A, prize_decay) that re-run the
model live and redraw the chart + inequality bar — so the user *sees* the
equilibrium flip. This is the feature that makes the theory visceral ("drag
self-authority down and watch the bond die"). This is the one part that touches
the engine.

### 3a. Engine change (small, additive, non-breaking)
Right now the module reads its terms from `Scenario.beliefs` / proxies inside
`analyze()`. For live recompute without mutating the scenario, expose the pure
math as a **stateless, public function** so the UI can call it with overridden
terms. In `RelationalRepairModule.kt`:

- Make the private `Terms` data class **public** (or lift it to a small
  top-level `RepairInputs` data class in the same file).
- Extract the four computations already in `analyze()` into a public pure
  function, e.g.:

```kotlin
data class RepairOutputs(
    val left: Double, val right: Double, val survives: Boolean,
    val tStar: Int, val historyPath: List<Double>,
    val renewalSurvival: Double, val pStar: Double?
)

fun computeRepair(inputs: RepairInputs, horizon: Int, pStar: Double?): RepairOutputs
```

Move the inequality, `simulateMeltingPrize`, and `q^T` logic into it. Then
`analyze()` calls `computeRepair(...)` to fill `calculations` (so the existing
tests still pass unchanged — verify `FrameworkTests` is green after refactor).
`pStar` stays computed from the matrix in `analyze()` and is passed in; the
slider panel keeps it fixed (it comes from the payoff matrix, not the sliders).

No behavioural change — pure extraction. The existing 7 tests must still pass;
add one test that `computeRepair` reproduces the same `t_star` the analyze path
reports for the standard scenario.

### 3b. UI
Add an expandable "What-if" section to RelationalRepairCard (or a sibling
`RelationalRepairSimulatorCard`):
- Seed slider state from `result.calculations["terms"]`.
- 5 `Slider`s: Self-authority S, Renewal prob q, Renewal cost R, Alternative
  pull A, Prize decay. (0..1 ranges; decay maybe 0..0.4.)
- On any change, call `RelationalRepairModule.computeRepair(...)` in a
  `remember(sliders)` / `derivedStateOf` and redraw the inequality bar + chart +
  stat tiles from the fresh `RepairOutputs`.
- A "reset to scenario" text button restores the original term values.

This is self-contained — no ViewModel/state changes, because recompute is a pure
function call, not an analysis re-run.

### 3c. Acceptance criteria (Phase 3)
- Dragging S from its value toward 0 flips the outcome pill red and the inequality
  bar's winning side, without re-running the whole analysis.
- Increasing prize_decay visibly pulls the t\* marker left on the chart.
- "Reset" returns every slider and visual to the analysed values.
- `./gradlew :app:testDebugUnitTest` still green (the extraction didn't break the
  7 existing tests).

---

## Suggested commit / PR sequence
1. `feat(ui): RelationalRepairCard with inequality bar, melting-prize chart, stat tiles` (Phase 1)
2. `feat(ui): plain-language verdict + discoverability for repair card` (Phase 2)
3. `refactor(repair): extract pure computeRepair() for live recompute` (Phase 3a)
4. `feat(ui): interactive what-if sliders for repair card` (Phase 3b)

Keep each as its own commit on `feature/relational-repair-module` (or a UI
sub-branch off it) for clean review.

---

## Appendix A — Compose Canvas sketch for the melting-prize chart

Hand-drawn, no library. Illustrative — adapt to the app's spacing tokens.

```kotlin
@Composable
private fun MeltingPrizeChart(
    history: List<Double>,
    tStar: Int,
    modifier: Modifier = Modifier
) {
    val line = MaterialTheme.colorScheme.primary
    val marker = MaterialTheme.colorScheme.error
    val axis = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    if (history.isEmpty()) return
    val maxY = (history.maxOrNull() ?: 1.0).coerceAtLeast(1e-6)

    Canvas(modifier.fillMaxWidth().height(120.dp)) {
        val w = size.width; val h = size.height
        val n = history.size
        fun x(i: Int) = w * i / (n - 1).coerceAtLeast(1)
        fun y(v: Double) = h - (h * (v / maxY)).toFloat()

        // baseline
        drawLine(axis, androidx.compose.ui.geometry.Offset(0f, h),
            androidx.compose.ui.geometry.Offset(w, h), strokeWidth = 1f)

        // history path
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(x(0), y(history[0]))
            for (i in 1 until n) lineTo(x(i), y(history[i]))
        }
        drawPath(path, line, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))

        // t* marker
        if (tStar in 1 until n) {
            val mx = x(tStar)
            drawLine(marker,
                androidx.compose.ui.geometry.Offset(mx, 0f),
                androidx.compose.ui.geometry.Offset(mx, h),
                strokeWidth = 2f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        }
    }
    // Label the marker in a Text below the Canvas, not inside it (SVG/Canvas text is fiddly).
    if (tStar in 1 until history.size) {
        Text("t* = $tStar · repair stops being rational after here",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error)
    }
}
```

Note: `history_trajectory` is the value of shared history *if the bond is
maintained* — it rises. The melting-prize intuition (that the *reconnection
prize* falls) is conveyed by the **t\* marker** on that same timeline: the point
where, given the decay, reaching is no longer worth it. If you want to also plot
the decaying prize as a second (falling) line, the module would need to expose
the prize series too — a trivial addition to `simulateMeltingPrize` (return the
`decayed` values alongside `historyPath`). Flag it if you want both lines; it's a
5-line engine change.

---

## File-touch summary

| Phase | File | Change |
|---|---|---|
| 1 | `ui/components/RelationalRepairCard.kt` | **new** — the card |
| 1 | `ui/tabs/AnalysisTab.kt` | +~10 lines — pull result by moduleId, render card |
| 2 | `ui/components/RelationalRepairCard.kt` | verdict line + discoverability note |
| 3a | `framework/behavioral/RelationalRepairModule.kt` | extract public `computeRepair()`; make inputs public (non-breaking) |
| 3a | `app/src/test/.../FrameworkTests.kt` | +1 test for `computeRepair` parity |
| 3b | `ui/components/RelationalRepairCard.kt` | what-if sliders + live recompute |

Phases 1–2 are pure UI and safe to ship alone. Phase 3 is the differentiator but
optional. Reference implementation for the whole approach:
`TrustConditionsCard.kt` + its wiring in `AnalysisTab.kt`.
