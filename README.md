# Strategos

> **Strategic decision intelligence for high-stakes environments.**  
> A native Android application that transforms complex, multi-stakeholder decisions into structured, evidence-grounded analyses — powered by game theory, behavioural economics, and a world-class Neo-Tactile UI.

---

## Table of Contents

1. [What Is Strategos?](#1-what-is-strategos)
2. [Core Philosophy](#2-core-philosophy)
3. [Feature Overview](#3-feature-overview)
4. [Architecture](#4-architecture)
5. [Framework Library — All 34 Modules](#5-framework-library--all-34-modules)
6. [Decision Lifecycle](#6-decision-lifecycle)
7. [Stakeholder Trust Dynamics](#7-stakeholder-trust-dynamics)
8. [Recommendation Engine](#8-recommendation-engine)
9. [UI Design System — Neo-Tactile](#9-ui-design-system--neo-tactile)
10. [Navigation & Tabs](#10-navigation--tabs)
11. [Data Model](#11-data-model)
12. [Tech Stack](#12-tech-stack)
13. [Build & Run](#13-build--run)
14. [Project Structure](#14-project-structure)
15. [Roadmap](#15-roadmap)

---

## 1. What Is Strategos?

Strategos is a professional-grade strategic decision-support tool built natively for Android. It is designed for decision-makers — executives, clinicians, policy analysts, strategists — who face decisions with multiple players, competing interests, uncertainty, and real consequences.

Unlike general productivity apps, Strategos applies a rigorous, research-backed analytical engine to every scenario. It answers not just *what to do* but *why*, *under what conditions*, *at what risk*, and *with which stakeholders aligned or opposed*.

**Key outcomes the app delivers:**

- A structured model of every decision: players, actions, payoffs, constraints, beliefs, and evidence
- Automated multi-framework game-theoretic and behavioural analysis
- A recommended pathway with prose rationale (no raw score dumps)
- Stakeholder trust profiles: archetypes, cooperation likelihood, engagement prescriptions
- A governed lifecycle from initial framing through implementation and monitoring

---

## 2. Core Philosophy

| Principle | Implementation |
|---|---|
| **Evidence over intuition** | Every claim is tagged with source, quality, and fact status (Confirmed / User Estimate / AI Inferred / Unknown) |
| **Structure before analysis** | The app enforces framing, modelling, and constraint-checking before running any analytical modules |
| **Multiple lenses** | 34 framework modules run in parallel; consensus across frameworks increases confidence |
| **Governance-first** | Decisions follow a 12-stage lifecycle with explicit approval gates and implementation tracking |
| **Constraint safety** | Hard constraints (Legal, Ethical, Safety) block recommendations entirely — never softened |
| **Transparency** | Every score has a breakdown; every recommendation has named supporting frameworks |

---

## 3. Feature Overview

### 3.1 Scenario Management
- Create, save, and restore multiple independent decision scenarios
- Each scenario stores title, description, institution, location, time horizon, and decision owner
- Version history and change records on every mutation
- Completeness score (0–100%) drives the workflow progression bar

### 3.2 Framing (Frame Tab)
- Define the decision context: stakes, decision rule, time horizon, information asymmetry, zero-sum flag
- Capture stakeholders with full profiles: authority, influence, public/private goals, incentives, fears, veto rights, exit options, coalition links, credibility scores
- Define actions per stakeholder with reversibility, legal status, implementation difficulty, likely reactions, and time/cost estimates
- Add evidence sources with kind (RCT / Systematic Review / Observational / Expert Opinion / Policy / Internal Data) and quality rating

### 3.3 Modelling (Model Tab — two sub-tabs)
- **Frame sub-tab**: Live scenario framing editor for all stakeholder, action, constraint, belief, and evidence data
- **Model sub-tab**: Payoff matrix entry with multi-dimension payoffs (up to 6 dimensions), belief probability assignments, game-tree construction (sequential nodes with chance nodes), and constraint assessments

### 3.4 Analysis (Analysis Tab)
- One-tap analysis runs all applicable framework modules in parallel
- Framework Registry scores each module's applicability (0.0–1.0) before running
- Results grouped by category: CORE → SUPPORTING → EXPLORATORY
- Stakeholder Trust Dynamics card renders before all framework results
- Each result card shows: module name, status, headline finding, detailed breakdown, supporting evidence, confidence tier

### 3.5 Report (Report Tab)
- Ranked decision candidates: Recommended / Safe Fallback / High-Upside / Excluded
- Per-candidate score breakdown: framework relevance, input completeness, evidence quality, robustness, feasibility, stakeholder viability, ethical compliance
- Strength bar withheld if evidence sources are zero (shows "Score withheld — add evidence sources")
- Governance panel: approval stages, constraint status, policy option selection
- Snapshot capability: freeze an analysis state for audit

### 3.6 Implementation & Monitoring (More Tab)
- Stakeholder relationship map (visual graph)
- Implementation dashboard: plan, milestones, monitoring checkpoints, approval workflows
- PAUSED state with explicit recovery pathway

### 3.7 Templates
Four built-in scenario templates to accelerate setup:
- **Crisis Management** — rapid-response multi-agency coordination
- **Institutional Healthcare** — clinical governance and resource allocation
- **Procurement** — vendor selection under constraints
- **Resource Allocation** — competing priorities and budget governance

---

## 4. Architecture

```
Strategos/
├── domain/
│   ├── model/          — core data classes (Scenario, Stakeholder, Action, …)
│   ├── framework/      — FrameworkModule interface, registry, eligibility gate
│   └── recommendation/ — RecommendationEngine, scoring, candidate ranking
├── framework/          — 34 analysis modules (organised by sub-package)
│   ├── nash/
│   ├── behavioral/
│   ├── strategic/
│   ├── eu/
│   ├── minimax/
│   ├── pareto/
│   ├── dominant/
│   ├── sensitivity/
│   └── tree/
├── data/
│   ├── repo/           — ScenarioRepository (in-memory + serialization)
│   └── storage/        — ScenarioStorage (EncryptedSharedPreferences via Jetpack Security)
├── templates/          — Pre-built scenario templates
├── nlp/                — ScenarioParser (natural language scenario extraction)
├── ui/
│   ├── theme/          — NeoDesign.kt, Theme.kt (design system)
│   ├── components/     — Reusable composables (StratCard, TrustConditionsCard, …)
│   ├── tabs/           — One file per app tab
│   └── StrategosViewModel.kt — Single ViewModel, StateFlow-based
└── MainActivity.kt     — NavDock, tab routing
```

**Key architectural decisions:**

| Decision | Rationale |
|---|---|
| Single ViewModel | All app state flows through one `StrategosState`; tabs observe slices |
| `FrameworkModule` interface | Every analytical module is plug-and-play; add a module in one file |
| `AnalysisEligibilityGate` | Computes applicability score before running a module — prevents noise |
| Encrypted storage | All scenario data is encrypted at rest via `security-crypto` |
| Pure Kotlin serialization | No Gson/Moshi; `kotlinx.serialization` — compile-safe and reflection-free |

---

## 5. Framework Library — All 34 Modules

Modules are categorised as CORE (always runs if applicable), SUPPORTING (runs when model data permits), or EXPLORATORY (contextual/specialist).

### 5.1 Nash Equilibrium
| Module | What it finds |
|---|---|
| `PureNashModule` | Pure-strategy Nash equilibria in normal-form games |
| `MixedNash2x2Module` | Mixed-strategy equilibria for 2×2 payoff matrices |

### 5.2 Dominant Strategy
| Module | What it finds |
|---|---|
| `DominantStrategyModule` | Strictly and weakly dominant strategies per stakeholder |
| `IteratedDominanceModule` | Iterated elimination of dominated strategies (IESDS) |

### 5.3 Expected Utility
| Module | What it finds |
|---|---|
| `ExpectedUtilityModule` | EU-maximising action per stakeholder under probability beliefs |
| `BayesianUpdatingModule` | Posterior belief updates given new evidence and likelihoods |
| `ValueOfInformationModule` | Expected value of resolving specific belief uncertainties |

### 5.4 Game Tree
| Module | What it finds |
|---|---|
| `BackwardInductionModule` | Subgame-perfect equilibrium via backward induction on sequential trees |

### 5.5 Minimax & Robustness
| Module | What it finds |
|---|---|
| `MinimaxModule` | Minimax regret and maximin safety strategies |
| `SensitivityModule` | Sensitivity of the recommended action to payoff parameter variation |
| `RobustDecisionFrontierModule` | Robust decision frontier across worst-case assumptions |
| `ScenarioEnsembleModule` | Ensemble ranking across multiple belief scenarios |

### 5.6 Pareto
| Module | What it finds |
|---|---|
| `ParetoModule` | Pareto-efficient action profiles; identifies dominated outcomes |

### 5.7 Behavioral & Social
| Module | What it finds |
|---|---|
| `PrisonersDilemmaModule` | Cooperation conditions in PD-structured situations |
| `StagHuntModule` | Coordination equilibria and trust requirements |
| `ChickenGameModule` | Brinkmanship dynamics and commitment credibility |
| `TrustGameModule` | Trust and reciprocity baseline under uncertainty |
| `UltimatumModule` | Fairness dynamics, rejection risk, and offer calibration |
| `PublicGoodsModule` | Free-rider analysis and contribution incentive design |
| `CommonPoolResourceModule` | Collective action dynamics for shared resource governance |
| `CoalitionAnalysisModule` | Coalition formation stability, value distribution (Shapley), blocking conditions |
| `LevelKModule` | Level-k reasoning — predicts behaviour of non-fully-rational actors |
| `VirtualBargainingModule` | Nash bargaining solution with threat points and disagreement payoffs |
| `BehavioralResponseModule` | Prospect theory framing effects and loss-aversion adjustments |
| `PrincipalAgentModule` | Agency problems: moral hazard, adverse selection, incentive alignment |
| **`StakeholderTrustDynamicsModule`** | Axelrod 4-properties scoring, 8 archetypes, shadow of the future, Mitchell salience, cooperation inertia, GRIT prescriptions — *see §7* |

### 5.8 Strategic
| Module | What it finds |
|---|---|
| `StackelbergModule` | Leader–follower equilibrium in sequential commitment games |
| `SignallingModule` | Credible signal design; separating vs pooling equilibria |
| `AuctionModule` | Optimal bidding strategy across auction formats |
| `RedTeamModule` | Adversarial assumption stress-test; identifies exploitable weaknesses |
| `DynamicSimulationModule` | Multi-round simulation of repeated interactions over time horizon |

---

## 6. Decision Lifecycle

Strategos enforces a 12-stage lifecycle. Every transition is gated — a scenario cannot advance until its current stage requirements are met.

```
DRAFT → MODELLED → CHALLENGED → ANALYSED → HOLD*
                                          ↓
                              READY_FOR_APPROVAL → APPROVED
                                                        ↓
                                          IN_IMPLEMENTATION → MONITORING
                                                                   ↓
                                                PAUSED* ←→ CLOSED → ARCHIVED
```

\* HOLD: analysis complete but decision deferred  
\* PAUSED: implementation suspended — explicit recovery required

### 6.1 Six-Stage Workflow (UI)

| UI Stage | Lifecycle Stages Covered | Tab |
|---|---|---|
| **Frame** | DRAFT | Model → Frame |
| **Model** | MODELLED | Model → Model |
| **Challenge** | CHALLENGED | Model (constraints, red-team, evidence) |
| **Simulate** | HOLD | Analysis |
| **Decide** | ANALYSED, READY_FOR_APPROVAL, APPROVED | Report |
| **Learn** | IN_IMPLEMENTATION, MONITORING, PAUSED, CLOSED, ARCHIVED | More |

### 6.2 Approval Gates
- Configurable multi-stage approval chains
- Each stage has named approvers, decision roles (Final Sign-off, Recommender, etc.)
- Status: PENDING → APPROVED / REJECTED / ABSTAINED
- Approval completion tracked on the Decision Health grid

---

## 7. Stakeholder Trust Dynamics

The `StakeholderTrustDynamicsModule` is Strategos's most sophisticated behavioural module, grounded in three independent research traditions.

### 7.1 Axelrod's Four Properties
Each stakeholder is scored 0.0–1.0 on four dimensions derived from their model data:

| Property | Derivation | Meaning |
|---|---|---|
| **Niceness** | goals alignment × credibility × (no veto bias) | Willingness to cooperate first |
| **Provocability** | canVeto, authority, fear count | Speed to retaliate against defection |
| **Forgiveness** | behavioural assumptions, trust relationships | Ability to return to cooperation after conflict |
| **Clarity** | credibility, goals consistency, public/private goal alignment | Predictability of behaviour |

### 7.2 Eight Trust Archetypes
Inferred from the four-property scores:

| Archetype | Detection Rule | Engagement |
|---|---|---|
| **COPYCAT** (Tit-for-Tat) | Niceness >0.50, Provocability >0.40, Forgiveness >0.35, Clarity >0.50 | Standard reciprocal |
| **COPYKITTEN** | Niceness >0.65, Forgiveness >0.55 | Reciprocal with patience |
| **GRUDGER** | Niceness >0.55, Forgiveness <0.20 | GRIT unilateral de-escalation |
| **DETECTIVE** | Clarity <0.40, Niceness 0.35–0.65 | Challenge probe |
| **ALWAYS_COOPERATE** | Niceness >0.80, Forgiveness >0.75, Provocability <0.30 | Formalise commitment |
| **ALWAYS_DEFECT** | Niceness <0.25, Forgiveness <0.25 | Protect from exploitation |
| **EXTORTIONER** | canVeto + authority ≥8 + Forgiveness <0.30 | Restructure game |
| **PAVLOV** (Win-Stay/Lose-Switch) | Clarity >0.65, Forgiveness 0.35–0.65, Provocability 0.35–0.65 | Standard reciprocal |

### 7.3 Shadow of the Future
Discount rate proxy — how much this stakeholder values future cooperation:

```
base 0.6
  + canExit         → −0.30  (exit option reduces future stake)
  + timeHorizon ≤3  → −0.15  (short horizon = less future weight)
  + timeHorizon ≥12 → +0.15  (long horizon = more future weight)
  + credibility ≥7  → +0.10  (high credibility = trustworthy commitment)
```

### 7.4 Mitchell Salience
Priority score combining power, legitimacy, urgency:

```
salience = (authority + influence) / 20 × credibility / 10 × urgency
urgency = canDelay ? 0.3 : fears.size ≥ 2 ? 0.7 : 0.5
```

### 7.5 Cooperation Inertia
Overall tendency to maintain cooperation once established:

| Level | Condition |
|---|---|
| PERMANENT | ALWAYS_COOPERATE archetype |
| HIGH | Forgiveness >0.65 AND Shadow >0.65 |
| MEDIUM | Otherwise |
| LOW | ALWAYS_DEFECT or EXTORTIONER archetype |

### 7.6 Engagement Prescriptions
Each stakeholder receives a prescribed engagement strategy:

| Prescription | For archetype |
|---|---|
| STANDARD_RECIPROCAL | COPYCAT, COPYKITTEN, PAVLOV |
| GRIT_UNILATERAL | GRUDGER — graduated de-escalation |
| CHALLENGE_PROBE | DETECTIVE — test signals |
| RESTRUCTURE_GAME | EXTORTIONER — change incentive structure |
| FORMALIZE_COMMITMENT | ALWAYS_COOPERATE — lock in via contract |
| PROTECT_FROM_EXPLOITATION | ALWAYS_DEFECT |

---

## 8. Recommendation Engine

The `RecommendationEngine` synthesises all framework results into a ranked set of decision candidates.

### 8.1 Candidate Types

| Type | Description |
|---|---|
| `EXECUTABLE_ACTION` | A concrete action — may be Recommended, Safe Fallback, High-Upside, or Excluded |
| `IMPLEMENTATION_GUARDRAIL` | A mandatory implementation requirement — not a competing option |
| `INFORMATION_TASK` | A data-gathering task needed before deciding |
| `ANALYTICAL_NOTE` | An insight from a framework — not directly actionable |

### 8.2 Score Dimensions
Each candidate is scored across seven dimensions:

| Dimension | What is measured |
|---|---|
| Framework Relevance | How many applicable frameworks support this action |
| Input Completeness | How complete the model data is for this action |
| Evidence Quality | Weighted quality of linked evidence sources |
| Robustness | Stability of the recommendation under sensitivity analysis |
| Feasibility | Constraint clearance (SATISFIED / UNKNOWN / VIOLATED) |
| Stakeholder Viability | Net stakeholder support minus opposition |
| Ethical Compliance | Absence of ethical/safety constraint violations |

### 8.3 Constraint Safety
- **Hard constraints** (SAFETY, LEGAL, ETHICAL, USER_HARD): any violation → `BLOCKED_HARD_CONSTRAINT` → action excluded regardless of score
- **Soft constraints**: reflected in feasibility score only

### 8.4 Rationale Generation
Recommendations include a prose rationale that names:
- The action and its owner
- Supporting framework names (not scores)
- Key stakeholder alignment/risk signals
- The primary constraint status

---

## 9. UI Design System — Neo-Tactile

Strategos uses a custom design system (`NeoDesign.kt`) that hybridises **glassmorphism** and **neumorphism** into a cohesive dark-mode aesthetic.

### 9.1 Color Palette (Dark Mode)

| Token | Hex | Role |
|---|---|---|
| `void` | `#080A10` | Deepest background |
| `dark0` | `#0D0F18` | Canvas |
| `dark2` | `#1C1F2E` | Raised surface |
| `dark3` | `#252839` | Elevated card |
| `blueCore` | `#3D8BFF` | Primary accent |
| `green` | `#1DB954` | Positive / success |
| `amber` | `#FFB340` | Warning / attention |
| `red` | `#FF3B30` | Error / critical |
| `purple` | `#BF5AF2` | Exploratory / archetype |
| `teal` | `#32D2C8` | Secondary accent |

### 9.2 Component Library

| Component | Description |
|---|---|
| `NeoGlassCard` | Semi-transparent card: `dark3→dark2` vertical gradient + diagonal glass border; optional accent glow behind |
| `NeoElevatedCard` | Neumorphic raised card: 16dp shadow + `dark3` fill + white highlight border; optional color glow |
| `GradientPillButton` | Pill-shaped button with horizontal gradient fill, scale-on-press animation, and glow behind |
| `NeoIconSquircle` | Squircle icon button: gradient fill + shadow + glow, animates to pressed (depth flatten) |
| `NeoChip` | Pill chip with colored border + optional radial glow behind |
| `NeoSectionHeader` | Uppercase label with 3dp colored vertical accent bar |
| `NeoDivider` | Gradient horizontal rule (transparent → white 10% → transparent) |
| `GlowDot` | Status indicator dot with `BlurMaskFilter` radial glow |

### 9.3 Glow System
Glow effects use Android's `BlurMaskFilter` rendered via `drawIntoCanvas` — GPU-accelerated soft halos with no third-party dependency:

```kotlin
fun Modifier.glowBehind(color: Color, blurRadius: Dp = 18.dp, offsetY: Dp = 0.dp): Modifier
fun Modifier.ledStripBelow(color: Color, enabled: Boolean, blurRadius: Dp = 8.dp): Modifier
```

### 9.4 Light Mode
All Neo components fall back gracefully to clean Material3 light surfaces. No hard dark-mode-only lock.

---

## 10. Navigation & Tabs

Strategos uses a **floating glassmorphic navigation dock** fixed at the screen bottom. Each of the five tabs has a unique LED accent color.

| Tab | LED Color | Content |
|---|---|---|
| **Scenarios** | Electric Blue `#3D8BFF` | Scenario list, active decision card, health grid |
| **Model** | Teal `#32D2C8` | Frame sub-tab + Model Editor sub-tab |
| **Analyse** | Purple `#BF5AF2` | Framework results, Trust Dynamics card |
| **Report** | Amber `#FFB340` | Ranked recommendations, governance panel |
| **More** | Green `#1DB954` | Stakeholder map + Implementation dashboard |

The active tab displays:
- Per-tab LED glow strip below icon (via `ledStripBelow()`)
- Icon background highlight in the tab's own LED color
- Icon and label tinted with the LED color

Sub-tabs (Model, More) use **gradient pill selectors** replacing standard `TabRow` in dark mode.

---

## 11. Data Model

### 11.1 Core Entities

```
Scenario
 ├── List<Stakeholder>      — players with full behavioural profiles
 ├── List<Action>           — per-stakeholder possible moves
 ├── List<PayoffEntry>      — payoff matrix (multi-dimension)
 ├── List<Constraint>       — legal / ethical / safety / policy / resource
 ├── List<Belief>           — probability-weighted world states
 ├── List<EvidenceSource>   — sourced support for claims
 ├── SequenceNode?          — game tree root (recursive)
 ├── List<ConstraintAssessment>
 ├── List<ApprovalStage>
 ├── ImplementationPlan?
 └── List<ChangeRecord>
```

### 11.2 Payoff Dimensions
Up to 6 simultaneous payoff dimensions per action profile:

`FINANCIAL` · `CLINICAL_OUTCOME` · `POLITICAL_CAPITAL` · `REPUTATIONAL` · `OPERATIONAL` · `STRATEGIC`

### 11.3 Persistence
- Serialised to JSON via `kotlinx.serialization`
- Stored in `EncryptedSharedPreferences` (AES256 via Jetpack Security Crypto)
- No network calls; fully offline

### 11.4 Fact Status
Every data point carries a `FactStatus` tag:

| Status | Meaning |
|---|---|
| `CONFIRMED` | Verified against a named evidence source |
| `USER_ESTIMATE` | Analyst's own judgment |
| `AI_INFERRED` | Derived by the NLP parser |
| `UNKNOWN` | Not yet assessed |

---

## 12. Tech Stack

| Layer | Technology |
|---|---|
| **Language** | Kotlin 1.9 |
| **UI** | Jetpack Compose + Material3 |
| **Architecture** | MVVM — single ViewModel, `StateFlow` |
| **Serialisation** | `kotlinx.serialization` 1.6.3 |
| **Storage** | Jetpack Security Crypto (`EncryptedSharedPreferences`) |
| **Concurrency** | Kotlin Coroutines 1.8.1 |
| **Min SDK** | API 26 (Android 8.0) |
| **Target SDK** | API 34 (Android 14) |
| **Compile SDK** | 34 |
| **Build** | Gradle 8 with Kotlin DSL |
| **Tests** | JUnit 4 + `kotlinx-coroutines-test` |

---

## 13. Build & Run

### 13.1 Prerequisites
- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK 34

### 13.2 Clone

```bash
git clone https://github.com/Kasi72/strategos.git
cd strategos
```

### 13.3 Open in Android Studio
1. Open Android Studio
2. **File → Open** → select the `strategos/` directory
3. Wait for Gradle sync to complete

### 13.4 Run on Device or Emulator
```bash
./gradlew assembleDebug
# or use the green Run button in Android Studio
```

### 13.5 Run Tests
```bash
./gradlew test
```

### 13.6 Build Release APK
```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

> **Note:** Release signing is configured in `app/build.gradle.kts`. Replace `strategos-release.jks` with your own keystore before distributing.

---

## 14. Project Structure

```
strategos/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           └── kotlin/com/drkkr/strategos/
│               ├── MainActivity.kt              # App entry, nav dock, tab routing
│               ├── data/
│               │   ├── repo/ScenarioRepository.kt
│               │   └── storage/ScenarioStorage.kt
│               ├── domain/
│               │   ├── framework/
│               │   │   ├── FrameworkModule.kt   # Interface all modules implement
│               │   │   ├── FrameworkRegistry.kt
│               │   │   └── AnalysisEligibilityGate.kt
│               │   ├── model/
│               │   │   ├── Scenario.kt          # Core data model
│               │   │   ├── GovernanceModel.kt   # Lifecycle, workflow stages
│               │   │   ├── WorkflowOrchestrator.kt
│               │   │   └── PayoffDimension.kt
│               │   └── recommendation/
│               │       └── RecommendationEngine.kt
│               ├── framework/                   # 34 analysis modules
│               │   ├── behavioral/              # Trust, PD, Stag Hunt, etc.
│               │   ├── dominant/
│               │   ├── eu/
│               │   ├── minimax/
│               │   ├── nash/
│               │   ├── pareto/
│               │   ├── sensitivity/
│               │   ├── strategic/               # Stackelberg, Signalling, etc.
│               │   └── tree/
│               ├── nlp/
│               │   └── ScenarioParser.kt
│               ├── templates/
│               │   ├── CrisisManagementTemplate.kt
│               │   ├── InstitutionalHealthcareTemplate.kt
│               │   ├── ProcurementTemplate.kt
│               │   └── ResourceAllocationTemplate.kt
│               └── ui/
│                   ├── StrategosViewModel.kt
│                   ├── components/
│                   │   ├── StratCard.kt         # Glassmorphic card components
│                   │   ├── BlockerActionCard.kt
│                   │   ├── TrustConditionsCard.kt
│                   │   └── WorkflowStageCard.kt
│                   ├── tabs/
│                   │   ├── HomeScreen.kt
│                   │   ├── FrameTab.kt
│                   │   ├── ModelEditorTab.kt
│                   │   ├── AnalysisTab.kt
│                   │   ├── ReportTab.kt
│                   │   ├── StakeholderMapTab.kt
│                   │   └── ImplementationDashboardTab.kt
│                   └── theme/
│                       ├── NeoDesign.kt         # Full Neo-Tactile design system
│                       └── Theme.kt             # Material3 color schemes
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
└── .gitignore
```

---

## 15. Roadmap

| Priority | Feature |
|---|---|
| **High** | AI-assisted scenario framing via on-device NLP |
| **High** | PDF export of analysis report |
| **High** | Comparative scenario view (side-by-side) |
| **Medium** | Shared scenario collaboration (multi-user, encrypted sync) |
| **Medium** | Push notifications for approval gate reminders |
| **Medium** | Custom payoff dimension definition |
| **Low** | Web companion (React / Compose Multiplatform) |
| **Low** | Academic reference export (APA/Chicago for evidence sources) |
| **Low** | Plugin API for custom framework modules |

---

## Author

**Dr KKR**  
Built with [Claude Code](https://claude.com/claude-code)

---

*Strategos — think clearly, decide well.*
