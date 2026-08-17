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

request -X POST "$BASE_URL/v1/register" -H 'Content-Type: application/json' \
  --data '{"id":"chatalice","name":"Chat Alice"}'
expect_status 200
ALICE_TOKEN="$(json_value "$BODY" token)"
echo "VERIFY: registered chatalice"

request -X POST "$BASE_URL/v1/register" -H 'Content-Type: application/json' \
  --data '{"id":"chatbob","name":"Chat Bob"}'
expect_status 200
BOB_TOKEN="$(json_value "$BODY" token)"
echo "VERIFY: registered chatbob"

request "$BASE_URL/v1/users/chatbob" -H "Authorization: Bearer $ALICE_TOKEN"
expect_status 200
[ "$(json_value "$BODY" id)" = "chatbob" ] || fail "lookup returned the wrong user"
echo "VERIFY: chatalice found chatbob"

request -X POST "$BASE_URL/v1/contacts" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ALICE_TOKEN" --data '{"id":"chatbob"}'
expect_status 200
[ "$(json_value "$BODY" state)" = "requested" ] || fail "chatalice add was not requested, got $BODY"
echo "VERIFY: chatalice requested chatbob"

request -X POST "$BASE_URL/v1/contacts" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $BOB_TOKEN" --data '{"id":"chatalice"}'
expect_status 200
[ "$(json_value "$BODY" state)" = "added" ] || fail "chatbob accept was not added, got $BODY"
echo "VERIFY: chatbob accepted chatalice"

CONVERSATION="d:chatalice:chatbob"
MESSAGE_ID="0123456789abcdef0123456789abcdef"
MESSAGE_BODY="hello from chatalice"

request -X POST "$BASE_URL/v1/conversations/$CONVERSATION/messages" \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $ALICE_TOKEN" \
  --data "{\"id\":\"$MESSAGE_ID\",\"body\":\"$MESSAGE_BODY\"}"
expect_status 200
FIRST_SEQ="$(json_value "$BODY" seq)"
echo "VERIFY: chatalice sent message $MESSAGE_ID at seq $FIRST_SEQ"

request "$BASE_URL/v1/conversations/$CONVERSATION/messages" \
  -H "Authorization: Bearer $BOB_TOKEN"
expect_status 200
python3 -c '
import json, sys
messages = json.loads(sys.argv[1])["messages"]
assert any(
    message["id"] == sys.argv[2] and message["body"] == sys.argv[3]
    for message in messages
)
' "$BODY" "$MESSAGE_ID" "$MESSAGE_BODY" || fail "chatbob did not receive the message"
echo "VERIFY: chatbob received the same message id and body"

request -X POST "$BASE_URL/v1/conversations/$CONVERSATION/messages" \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $ALICE_TOKEN" \
  --data "{\"id\":\"$MESSAGE_ID\",\"body\":\"$MESSAGE_BODY\"}"
expect_status 200
SECOND_SEQ="$(json_value "$BODY" seq)"
[ "$SECOND_SEQ" = "$FIRST_SEQ" ] || fail "duplicate message bumped seq from $FIRST_SEQ to $SECOND_SEQ"
echo "VERIFY: duplicate post kept seq $SECOND_SEQ"
echo "VERIFY: private chat passed"
