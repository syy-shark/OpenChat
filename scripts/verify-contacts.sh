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
  --data '{"id":"alice","name":"Alice"}'
expect_status 200
ALICE_TOKEN="$(json_value "$BODY" token)"
echo "VERIFY: register alice -> Registered"

request -X POST "$BASE_URL/v1/register" -H 'Content-Type: application/json' \
  --data '{"id":"bob","name":"Bob"}'
expect_status 200
BOB_TOKEN="$(json_value "$BODY" token)"
echo "VERIFY: register bob -> Registered"

request -X POST "$BASE_URL/v1/register" -H 'Content-Type: application/json' \
  --data '{"id":"alice","name":"Alice Again"}'
expect_status 409
[ "$(json_value "$BODY" error)" = "id_taken" ] || fail "duplicate alice was not id_taken"
echo "VERIFY: register alice again -> id_taken"

request -X POST "$BASE_URL/v1/contacts" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ALICE_TOKEN" --data '{"id":"bob"}'
expect_status 200
[ "$(json_value "$BODY" state)" = "requested" ] || fail "first add was not requested, got $BODY"
[ "$(json_value "$BODY" already)" = "False" ] || fail "first add already was not false"
echo "VERIFY: alice adds bob -> requested"

request "$BASE_URL/v1/contacts" -H "Authorization: Bearer $ALICE_TOKEN"
expect_status 200
python3 -c '
import json, sys
contacts = json.loads(sys.argv[1])["contacts"]
assert not any(contact["id"] == "bob" for contact in contacts)
' "$BODY" || fail "alice contacts listed bob after request"
echo "VERIFY: alice contacts do not list bob"

request "$BASE_URL/v1/friend-requests" -H "Authorization: Bearer $BOB_TOKEN"
expect_status 200
python3 -c '
import json, sys
requests = json.loads(sys.argv[1])["requests"]
assert any(item["id"] == "alice" and item["name"] == "Alice" for item in requests)
' "$BODY" || fail "bob friend-requests did not list alice"
echo "VERIFY: bob friend-requests list alice"

request -X POST "$BASE_URL/v1/contacts" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ALICE_TOKEN" --data '{"id":"bob"}'
expect_status 200
[ "$(json_value "$BODY" state)" = "pending" ] || fail "second add was not pending, got $BODY"
echo "VERIFY: alice adds bob again -> pending"

request -X POST "$BASE_URL/v1/contacts" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $BOB_TOKEN" --data '{"id":"alice"}'
expect_status 200
[ "$(json_value "$BODY" state)" = "added" ] || fail "bob accept was not added, got $BODY"
echo "VERIFY: bob adds alice -> added"

request "$BASE_URL/v1/contacts" -H "Authorization: Bearer $ALICE_TOKEN"
expect_status 200
python3 -c '
import json, sys
contacts = json.loads(sys.argv[1])["contacts"]
assert any(contact["id"] == "bob" and contact["name"] == "Bob" for contact in contacts)
' "$BODY" || fail "alice contacts did not list bob after accept"
echo "VERIFY: alice contacts list bob"

request "$BASE_URL/v1/contacts" -H "Authorization: Bearer $BOB_TOKEN"
expect_status 200
python3 -c '
import json, sys
contacts = json.loads(sys.argv[1])["contacts"]
assert any(contact["id"] == "alice" and contact["name"] == "Alice" for contact in contacts)
' "$BODY" || fail "bob contacts did not list alice after accept"
echo "VERIFY: bob contacts list alice"

request -X POST "$BASE_URL/v1/contacts" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ALICE_TOKEN" --data '{"id":"nobody"}'
expect_status 404
echo "VERIFY: alice adds nobody -> NoSuchUser"

request -X POST "$BASE_URL/v1/contacts" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ALICE_TOKEN" --data '{"id":"alice"}'
expect_status 400
echo "VERIFY: alice adds alice -> rejected"

echo "VERIFY: contacts server passed"
