#!/usr/bin/env bash
# Runs the on-device end-to-end suite on the EMULATOR only (never a USB phone).
# The live DeepSeek key comes from .env (DEEPSEEK_API_KEY), is passed as an instrumentation argument, and is never printed.
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/platform-tools/adb"
SERIAL=$("$ADB" devices | awk '/^emulator-/ && $2=="device" {print $1; exit}')
[ -n "$SERIAL" ] || { echo "No running emulator. Start one: emulator -avd strategos_api34"; exit 1; }
export ANDROID_SERIAL="$SERIAL"
ARGS=()
if [ -f .env ]; then
  KEY=$(grep -E '^DEEPSEEK_API_KEY=' .env | head -1 | cut -d= -f2- || true)
  [ -n "${KEY:-}" ] && ARGS+=("-Pandroid.testInstrumentationRunnerArguments.deepseekKey=$KEY")
fi
[ ${#ARGS[@]} -eq 0 ] && echo "DEEPSEEK_API_KEY not set in .env: live-coach tests will be skipped."
sh gradlew :app:connectedDebugAndroidTest "${ARGS[@]}" "$@"
