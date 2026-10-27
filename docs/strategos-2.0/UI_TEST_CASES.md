# UI end-to-end test cases

These run the real app on the **emulator** (`strategos_api34`), never a USB phone. Code: `app/src/androidTest/kotlin/com/drkkr/strategos/e2e/`.

## Run

```bash
emulator -avd strategos_api34 &          # once
scripts/run-e2e.sh                        # whole suite
scripts/run-e2e.sh -Pandroid.testInstrumentationRunnerArguments.class=com.drkkr.strategos.e2e.SettingsE2e
```

The live DeepSeek key lives in `.env` as `DEEPSEEK_API_KEY=` (gitignored). The script passes it to the tests as an
instrumentation argument and never prints it. Without it, tests marked **live** are skipped. Every test starts from a wiped
database; live tests install the key directly (except SET_03, which pastes it through Settings).

## Cases

| ID | Area | What it proves | Live |
|---|---|---|---|
| ONB_01 | Onboarding | Welcome → "What's on your mind?" → Back returns | |
| ONB_02 | Onboarding | Skipped when a project already exists | |
| ONB_03 | Onboarding | Sending creates the project, opens Talk, first message goes in, coach replies, onboarding never returns | ✔ |
| PRJ_01 | Projects | Typing in the composer shows the text and enables Send | |
| PRJ_02 | Projects | Tapping an example fills the composer | |
| PRJ_03 | Projects | Send with no key creates the project and opens Talk | |
| PRJ_04 | Projects | Tapping a project opens its Overview, not the chat | |
| PRJ_05 | Projects | Rename, archive, restore from Archived, delete | |
| OVR_01 | Overview | Overview + Journal tabs and the main sections show | |
| OVR_02 | Overview | Project Journal tab saves an entry linked to the project | |
| OVR_03 | Overview | Start chat opens Talk for that project; back returns | |
| OVR_04 | Overview | Back returns to Projects | |
| TALK_01 | Talk | No key shows the connect card and the Settings link | |
| TALK_02 | Talk | A message gets a real coach reply (saved to the thread) | ✔ |
| TALK_03 | Talk | "+ Topic" creates a separate topic chip | ✔ |
| TALK_04 | Talk | An empty project invites the first message | ✔ |
| JRN_01 | Journal | First-time empty state | |
| JRN_02 | Journal | Save with a mood: toast, entry, "Today" group | |
| JRN_03 | Journal | Entries survive switching tabs | |
| SET_01 | Settings | No key: key editor shown, AI switch explains itself | |
| SET_02 | Settings | A rejected key is reported and not stored | network |
| SET_03 | Settings | Pasting the live key connects; consent turns AI on | ✔ |
| SET_04 | Settings | Reveal the math choice persists | |
| SET_05 | Settings | Delete everything needs the typed word, then wipes projects | |
| SET_06 | Settings | Keep everything leaves data alone | |
| SET_07 | Settings | Removing the key turns AI off | ✔ |
| NAV_01 | Navigation | Dock switches between the three tabs | |
| NAV_02 | Navigation | No "Archived" row when nothing is archived | |
| NAV_03 | Navigation | Projects tab keeps its depth across tab switches | |

## Bugs this suite has found

- Opening Talk crashed for lack of `ACCESS_NETWORK_STATE`.
- The Overview's Journal tab was squashed to zero width.
- The Save button on the project Journal tab and the key field in Settings were hidden behind the keyboard.
- Every substantive first message failed with "Couldn't reach the coach": the sketch JSON was cut off at 900 output tokens, a limit sized for Groq.

## Not covered (needs a person or a different tool)

Voice dictation, going offline mid-chat, the system share sheet, dark mode appearance, and visual polish.
