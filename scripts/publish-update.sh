#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 3 ]; then
  echo "usage: $0 <apk> <versionCode> <versionName>" >&2
  exit 1
fi

APK="$1"
CODE="$2"
NAME="$3"
STAGE="${OPENCHAT_UPDATE_STAGE:-/tmp/openchat-update}"

[ -f "$APK" ] || { echo "missing apk: $APK" >&2; exit 1; }
[ "$CODE" -gt 0 ] || { echo "versionCode must be a positive integer" >&2; exit 1; }
[ -n "$NAME" ] || { echo "versionName is empty" >&2; exit 1; }

mkdir -p "$STAGE"
cp "$APK" "$STAGE/openchat.apk"
python3 -c 'import json,sys; print(json.dumps({"versionCode": int(sys.argv[1]), "versionName": sys.argv[2]}))' "$CODE" "$NAME" > "$STAGE/update.json"

echo "staged $STAGE/openchat.apk ($(wc -c < "$STAGE/openchat.apk") bytes)"
echo "staged $STAGE/update.json ($(cat "$STAGE/update.json"))"
