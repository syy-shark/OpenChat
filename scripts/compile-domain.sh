#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$ROOT/.tooling/jdk-17}"
export PATH="$JAVA_HOME/bin:$PATH"
cd "$ROOT"
if [ ! -x "$ROOT/gradlew" ]; then
  echo "gradle wrapper missing" >&2
  exit 1
fi
./gradlew :domain:compileKotlin --offline || ./gradlew :domain:compileKotlin
