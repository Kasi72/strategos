#!/usr/bin/env bash
# DEBUG BUILDS ONLY. Copies the unencrypted debug database off a connected phone so conversations can be read with sqlite3.
# Usage: scripts/pull-debug-db.sh [out_dir]   (default ./debug-db; pass a path outside the repo)
set -euo pipefail
ADB="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/platform-tools/adb"
OUT="${1:-./debug-db}"; mkdir -p "$OUT"
for f in strategos_plain_debug.db strategos_plain_debug.db-wal strategos_plain_debug.db-shm; do
  "$ADB" exec-out run-as com.drkkr.strategos cat "databases/$f" > "$OUT/$f" 2>/dev/null || rm -f "$OUT/$f"
done
echo "pulled to $OUT"; ls -la "$OUT"
