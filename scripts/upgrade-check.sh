#!/usr/bin/env bash
# TC_ID_E2E_02 upgrade check, EMULATOR only (never a USB phone): installs the `main` build, seeds a project, two conversations,
# a journal entry and its note (ManualSeedDemoTest, the same code the app runs), installs this branch's APK over it, and asserts
# the data is still there (PostUpgradeCheckTest#TC_ID_E2E_02_...). BASE picks the older build (default origin/main).
# Wipes the app's data on the emulator first.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
BASE="${BASE:-origin/main}"
ADB="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/platform-tools/adb"
SERIAL=$("$ADB" devices | awk '/^emulator-/ && $2=="device" {print $1; exit}')
[ -n "$SERIAL" ] || { echo "No running emulator. Start one: emulator -avd strategos_api34"; exit 1; }
adb() { "$ADB" -s "$SERIAL" "$@"; }
APP=com.drkkr.strategos
RUNNER=$APP.test/androidx.test.runner.AndroidJUnitRunner
apk() { ls "$1"/app/build/outputs/apk/debug/*.apk | head -1; }   # the file name differs between versions
TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

OLD=$(mktemp -d)/base
trap 'git -C "$ROOT" worktree remove --force "$OLD" >/dev/null 2>&1 || true' EXIT
git worktree add --detach "$OLD" "$BASE" >/dev/null
cp local.properties "$OLD/"
echo "Building $BASE ($(git -C "$OLD" rev-parse --short HEAD)) and this branch ($(git rev-parse --short HEAD))..."
(cd "$OLD" && sh gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest)
sh gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest

adb uninstall $APP >/dev/null 2>&1 || true
adb uninstall $APP.test >/dev/null 2>&1 || true
adb install "$(apk "$OLD")" >/dev/null
adb install "$OLD/$TEST_APK" >/dev/null
SEED=$(adb shell am instrument -w -e seedDemo 1 -e class $APP.ManualSeedDemoTest $RUNNER)
echo "$SEED" | grep -q "OK (1 test)" || { echo "Seeding on $BASE failed:"; echo "$SEED"; exit 1; }
echo "Seeded on $BASE."

adb install -r "$(apk "$ROOT")" >/dev/null
adb install -r "$TEST_APK" >/dev/null
adb logcat -c
CHECK=$(adb shell am instrument -w -e upgradeCheck 1 -e class "$APP.PostUpgradeCheckTest#TC_ID_E2E_02_upgradeKeepsTheProjectConversationAndJournal" $RUNNER)
adb logcat -d -s System.out | grep "UPGRADE-CHECK" || true
if echo "$CHECK" | grep -q "OK (1 test)"; then echo "UPGRADE CHECK: PASS"; else echo "$CHECK"; echo "UPGRADE CHECK: FAIL"; exit 1; fi
