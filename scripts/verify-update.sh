#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${OPENCHAT_BASE_URL:-http://127.0.0.1:8080}"
BODY_FILE="$(mktemp)"
trap 'rm -f "$BODY_FILE"' EXIT

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

request() {
  STATUS="$(curl -sS -o "$BODY_FILE" -w '%{http_code}' "$@")"
  BODY="$(<"$BODY_FILE")"
}

expect_status() {
  [ "$STATUS" = "$1" ] || fail "expected HTTP $1, got $STATUS with $BODY"
}

json_value() {
  python3 -c 'import json,sys; print(json.loads(sys.argv[1])[sys.argv[2]])' "$1" "$2"
}

request "$BASE_URL/v1/update"
expect_status 200
CODE="$(json_value "$BODY" versionCode)"
NAME="$(json_value "$BODY" versionName)"
[ "$CODE" -gt 0 ] || fail "versionCode was $CODE"
[ -n "$NAME" ] || fail "versionName was empty"
echo "VERIFY: /v1/update $CODE $NAME"

request "$BASE_URL/v1/update/apk"
expect_status 200
SIZE="$(wc -c < "$BODY_FILE")"
[ "$SIZE" -gt 1000 ] || fail "apk was only $SIZE bytes"
echo "VERIFY: /v1/update/apk $SIZE bytes"
