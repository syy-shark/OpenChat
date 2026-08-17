#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$ROOT/.tooling/jdk-17}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

[ -x "$JAVA_HOME/bin/java" ] || fail "JDK missing at $JAVA_HOME"
"$JAVA_HOME/bin/java" -version >/dev/null
[ -x "$ANDROID_SDK_ROOT/platform-tools/adb" ] || fail "adb missing"
adb version >/dev/null

cd "$ROOT"
./gradlew :domain:compileKotlin -q || fail "domain did not compile"

classes="$ROOT/domain/build/classes/kotlin/main/com/openchat/domain"
[ -f "$classes/ChatList.class" ] || fail "ChatList.class missing after compile"
[ -f "$classes/Standing.class" ] || fail "Standing.class missing after compile"
[ -f "$classes/Message.class" ] || fail "Message.class missing after compile"

apk="$(find "$ROOT/android/build/outputs/apk" -name '*-debug.apk' 2>/dev/null | head -n 1 || true)"
[ -n "$apk" ] || fail "no debug apk; predicates 1-4 are red until the Android shell exists"
echo "VERIFY: apk present at $apk"
