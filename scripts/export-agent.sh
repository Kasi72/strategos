#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/agent-build"
npm ci --silent
node build.mjs
