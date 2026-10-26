# UI handoff: restyle the screens, keep the backend

Goal: a designer tool (Gemini) rewrites the **look** of the screens; the backend, view models and
navigation stay as they are. The app already runs end to end with the new `ui/calm` shell on a real
phone, so the UI code can be replaced screen by screen and checked immediately.

## Give Gemini these (the design)
- `Design/DESIGN.md` — principles, colour/type/shape tokens, screen list, writing style.
- `Design/CLAUDE.md` — the rules the UI must follow (stateless screens, no jargon, 48dp targets, etc.).
- `Design/strategos-redesign/screenshots/*.png` — what each screen should look like (00-Main … 16-Cutlist).
- `Design/strategos-redesign/boards/*.dc.html` — the exact copy, spacing and states (open as text).
  Most useful: `Projects`, `Hub` (the Overview), `Talk`, `Xray`, `Journal`, `Settings`, `Topic`,
  `Review`, `States`, `Onboarding`, `Components`, `Cutlist` (what must NOT come back).
- `app/src/main/res/font/` — Figtree, Source Serif 4, IBM Plex Mono (already bundled and wired in
  `ui/calm/theme/CalmFonts.kt`).

## Give Gemini these (the code it edits)
The UI lives only in `app/src/main/kotlin/com/drkkr/strategos/ui/calm/`:
- `theme/StrategosTheme.kt`, `theme/CalmFonts.kt` — tokens, `Calm.colors/type/dimens`, `Modifier.calmCard`.
- `CalmApp.kt`, `CalmNav.kt`, `Dock.kt` — shell, routes, bottom dock.
- `projects/ProjectsScreen.kt` (+ `ProjectsUi.kt` holds `ProjectsUiState` / `ProjectsActions`).
- `overview/OverviewScreen.kt`, `overview/ReviewSheet.kt` (+ `OverviewUi.kt`: `OverviewUiState` / `OverviewActions`).
- `xray/XRaySheet.kt` (+ `XRayUiState` in `xray/XRayViewModel.kt`).

Rule for each screen: a **stateless composable** taking one `UiState` and one `Actions` object. Change
how it looks freely; do not rename or remove fields of `UiState` / `Actions` (add new fields only if a
design needs them, and tell me so I can feed them).

## Do NOT hand to Gemini to change (this is the backend I wire)
`domain/`, `data/`, `agent/`, `framework/`, `llm/`, `AppContainer.kt`, every `*ViewModel.kt`, all tests.
The 77 `FrameworkTests` and `DomainPurityTest` must keep passing.

## Screens still to be designed (currently the old screens hosted in the new shell)
Talk, Journal, Settings (key/model/privacy), onboarding / first run, New topic, Archived list, and the
empty / error / offline states. For these, Gemini should create new `…Screen.kt` files with the same
pattern; I then connect them to:
- Talk: `StrategosViewModel.counsel` (controller) + `counselState` (`CounselUiState` / `ChatItem` in
  `agent/CounselUiState.kt`). Replies can carry `ChatItem.AssistantText.pill` (the engine's one-line label,
  tapped to open the X-ray). Reveal-the-math setting: `StrategosViewModel.revealMath`.
- Journal: `JournalRepository` (entries, post notes, `linkSuggestions/link/dismissLink`).
- Settings: `AiSettingsController` (key, consent, model), `StrategosViewModel.revealMath`.

## Ready backend (all unit-tested)
Momentum status/events, `ProjectBrainRepository` (memory, review, influence, cross-project carry-over),
`GuidanceRepository`, `PatternCatalog`, `XRayGridBuilder` + `XRayRepository`, `RevealMath` setting.
See `docs/strategos-2.0/UX_BACKEND_CHANGES.md`.

## How to check a change
`./gradlew :app:testDebugUnitTest` then `./gradlew :app:assembleDebug` and install on the phone.
A debug-only harness (`app/src/debug/.../CalmPreviewActivity.kt`) shows screens with sample data:
`adb shell am start -n com.drkkr.strategos/.ui.calm.CalmPreviewActivity --ez dark false --es screen overview`
(`projects`, `firstrun`, `overview`, `fonts`).
