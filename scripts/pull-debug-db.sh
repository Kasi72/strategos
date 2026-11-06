#!/usr/bin/env bash
# DEBUG BUILDS ONLY. Copies the unencrypted debug EXPORT off a connected phone so conversations can be read with sqlite3.
# The live database is always the encrypted strategos.db; a debug build writes this one-way export each time the app starts
# (DebugDbExport), so the script restarts the app first to get a fresh copy. It also pulls strategos_plain_debug.pre-id05.db
# (and its -wal/-shm) when present: the live database of a power-mode debug build from before ID0.5, kept as it was.
# Usage: scripts/pull-debug-db.sh [out_dir]   (default ./debug-db; pass a path outside the repo)
set -euo pipefail
ADB="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/platform-tools/adb"
APP=com.drkkr.strategos
OUT="${1:-./debug-db}"; mkdir -p "$OUT"
stamp() { "$ADB" shell run-as $APP stat -c %Y databases/strategos_plain_debug.db 2>/dev/null | tr -d '\r' || echo 0; }
before=$(stamp)
"$ADB" shell am force-stop $APP
"$ADB" shell monkey -p $APP -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
for _ in $(seq 1 30); do [ "$(stamp)" != "$before" ] && [ "$(stamp)" != "0" ] && break; sleep 1; done
[ "$(stamp)" != "$before" ] || echo "warning: no fresh export (is this a debug build?); pulling what is there"
for f in strategos_plain_debug.db strategos_plain_debug.pre-id05.db strategos_plain_debug.pre-id05.db-wal strategos_plain_debug.pre-id05.db-shm; do
  "$ADB" exec-out run-as $APP cat "databases/$f" > "$OUT/$f" 2>/dev/null || rm -f "$OUT/$f"
  [ -s "$OUT/$f" ] || rm -f "$OUT/$f"
done
echo "pulled to $OUT"; ls -la "$OUT"
