# Dr KKR Game Theory Engine — Strategos

**Modular Decision Intelligence Engine for Android**

---

## Architecture

```
Presentation      MainActivity / StrategosViewModel / 5 Compose tabs
    │
Domain            Scenario (data model) · FrameworkModule (plugin interface)
    │             RecommendationEngine (multi-lens synthesis)
    │
Framework         8 plug-in modules (separate packages, independent tests)
    │
Data              ScenarioRepository · EncryptedSharedPreferences storage
    │
NLP               ScenarioParser stub (wire to LLM for production)
Templates         InstitutionalHealthcareTemplate (Psychiatry sub-template)
```

### Layers

| Layer | Package | Role |
|---|---|---|
| Scenario Intelligence | `nlp/` | Free-text → structured Scenario draft |
| Formal Decision Model | `domain/model/` | Scenario, Stakeholder, Action, PayoffEntry, SequenceNode |
| Reasoning Frameworks | `framework/*/` | Plug-in modules implementing `FrameworkModule` |
| Recommendation & Governance | `domain/recommendation/` | Multi-lens synthesis, defensible pathway |

---

## Reasoning Modules (MVP)

| Module | Package | Key output |
|---|---|---|
| Dominant Strategy | `framework/dominant` | Strictly/weakly dominant strategies |
| Pure Nash | `framework/nash` | Enumeration of pure NE profiles |
| Mixed Nash 2×2 | `framework/nash` | Closed-form indifference solution |
| Expected Utility | `framework/eu` | Best action under uniform prior |
| Minimax / Maximin / Regret | `framework/minimax` | Worst-case-safe + low-regret choices |
| Backward Induction | `framework/tree` | SPNE path from game tree |
| Pareto Multi-objective | `framework/pareto` | Non-dominated profiles across dimensions |
| Sensitivity Analysis | `framework/sensitivity` | Ranking stability under weight perturbation |

### Adding a new module

```kotlin
class MyNewModule : FrameworkModule {
    override val id = "my-module"
    override val name = "My Module"
    override val version = "1.0"
    override val category = "Category"
    override fun canAnalyze(model: Scenario): Applicability { ... }
    override fun analyze(model: Scenario): AnalysisResult { ... }
}
// Register in StrategosViewModel.buildRegistry():
.register(MyNewModule())
```

---

## Payoff Dimensions

`SAFETY · ETHICS · LEGALITY · COST · REPUTATION · RELATIONSHIPS · WELLBEING · EFFICIENCY · PUBLIC_TRUST · LONG_TERM_VALUE`

Every dimension has a weight (slider in Model Editor). Scalar utility = weighted sum (COST negated).

---

## Five UI Tabs

1. **Scenarios** — list, create from free text, load template
2. **Model** — edit stakeholders, actions, constraints, weights, assumption log
3. **Analyse** — run all applicable frameworks, expand per-module results
4. **Report** — defensible pathway, multi-lens recommendations, disagreements, missing info
5. **Map** — stakeholder influence diagram

---

## Templates

`InstitutionalHealthcareTemplate.SubTemplate`:
- `PSYCHIATRY` — seeds a full psychiatric dept. governance scenario
- `GENERAL_MEDICINE`, `HEALTHCARE_ADMIN`, `CUSTOM` — generic healthcare stubs

---

## Safety Constraints

Hard-coded constraints of kind `SAFETY`, `LEGAL`, `ETHICAL`, or `USER_HARD` are
displayed as locked items in the Model editor and referenced in the defensible
pathway. The engine never recommends actions that violate them.

---

## Building

```bash
./gradlew assembleDebug
./gradlew test          # unit tests for all 8 framework modules
```

Requires Android Studio Hedgehog+ / AGP 8.5+ / Kotlin 1.9.24.

---

## Production wiring (not included — requires API key)

Replace `ScenarioParser.parse()` with an Anthropic / OpenAI call using:
- System prompt: extract JSON `Scenario` from natural language
- User message: raw scenario text
- Response: deserialized into `Scenario` with `FactStatus` tags

---

## Security

- `EncryptedSharedPreferences` (AES256-GCM) for scenario storage
- Biometric lock hook in `ScenarioStorage` constructor
- Auto-lock: extend `MainActivity.onPause()` to clear state
- Redacted export: filter `Stakeholder.notes` and `Belief` details before sharing
