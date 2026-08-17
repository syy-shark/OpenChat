#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="${JAVA_HOME:-$ROOT/.tooling/jdk-17}"
export PATH="$JAVA_HOME/bin:$PATH"
VER="8.11.1"
DIST="$ROOT/.tooling/gradle-$VER"
if [ ! -x "$DIST/bin/gradle" ]; then
  mkdir -p "$ROOT/.tooling"
  zip="$ROOT/.tooling/gradle-$VER-bin.zip"
  if [ ! -f "$zip" ]; then
    echo "downloading Gradle $VER"
    curl -fsSL -o "$zip" "https://services.gradle.org/distributions/gradle-${VER}-bin.zip"
  fi
  unzip -q "$zip" -d "$ROOT/.tooling"
  mv "$ROOT/.tooling/gradle-$VER" "$DIST" 2>/dev/null || true
  # archive extracts as gradle-8.11.1/
  if [ ! -x "$DIST/bin/gradle" ] && [ -x "$ROOT/.tooling/gradle-$VER/bin/gradle" ]; then
    :
  fi
fi
GRADLE_BIN="$DIST/bin/gradle"
if [ ! -x "$GRADLE_BIN" ]; then
  GRADLE_BIN="$ROOT/.tooling/gradle-$VER/bin/gradle"
fi
exec "$GRADLE_BIN" "$@"
