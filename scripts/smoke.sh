#!/usr/bin/env bash
# End-to-end smoke test against a running stack (docker compose up). Also the demo script:
# one transfer end to end, one retry, one held transfer, one reconciliation run, one Q&A answer.
set -euo pipefail
API="${API:-http://localhost:8080}"

post() { # post <path> <json> [idempotency-key]
  local key="${3:-$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen)}"
  curl -fsS -X POST "$API$1" -H 'Content-Type: application/json' -H "Idempotency-Key: $key" -d "$2"
}
field() { python3 -c "import sys, json; print(json.load(sys.stdin)$1)"; }

echo "== waiting for API"
for _ in $(seq 1 60); do curl -fs "$API/actuator/health" >/dev/null && break; sleep 2; done

suffix=$RANDOM$RANDOM
asha=$(post /users "{\"name\":\"Asha Verma\",\"email\":\"asha$suffix@example.com\",\"phone\":\"+91900$((suffix % 10000000))1\"}" | field "['user_id']")
rahul=$(post /users "{\"name\":\"Rahul Mehta\",\"email\":\"rahul$suffix@example.com\",\"phone\":\"+91800$((suffix % 10000000))2\"}" | field "['user_id']")
aw=$(post "/users/$asha/wallets" '{"name":"Main"}' | field "['wallet_id']")
rw=$(post "/users/$rahul/wallets" '{"name":"Main"}' | field "['wallet_id']")
echo "users $asha/$rahul wallets $aw/$rw"

echo "== 1. top-up ₹1,000 and send ₹250 end to end"
post /transfers/topup "{\"wallet_id\":$aw,\"amount\":100000}" >/dev/null
t=$(post /transfers "{\"from_wallet_id\":$aw,\"to_wallet_id\":$rw,\"amount\":25000,\"note\":\"dinner\"}")
echo "$t"
tid=$(echo "$t" | field "['transfer_id']")
curl -fsS "$API/transfers/$tid"; echo
curl -fsS "$API/wallets/$aw/balance"; echo

echo "== 2. retry with the same Idempotency-Key: same response, no second charge"
key=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen)
r1=$(post /transfers "{\"from_wallet_id\":$aw,\"to_wallet_id\":$rw,\"amount\":1000}" "$key")
r2=$(post /transfers "{\"from_wallet_id\":$aw,\"to_wallet_id\":$rw,\"amount\":1000}" "$key")
[ "$r1" = "$r2" ] && echo "identical responses: $r1"
curl -fsS "$API/wallets/$aw/balance"; echo

echo "== 3. velocity rule: 6th outgoing transfer in 10 minutes is HELD (202)"
for i in 1 2 3; do post /transfers "{\"from_wallet_id\":$aw,\"to_wallet_id\":$rw,\"amount\":100}" >/dev/null; done
held=$(curl -sS -w '\nHTTP %{http_code}' -X POST "$API/transfers" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen)" \
  -d "{\"from_wallet_id\":$aw,\"to_wallet_id\":$rw,\"amount\":100}")
echo "$held"
echo "$held" | grep -q "HTTP 202"
curl -fsS "$API/admin/risk/queue"; echo
held_id=$(echo "$held" | head -1 | field "['transfer_id']")
echo "-- admin releases held transfer $held_id"
curl -fsS -X POST "$API/admin/risk/$held_id/decision" -H 'Content-Type: application/json' -H 'X-Actor: demo-admin' \
  -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen)" \
  -d '{"decision":"RELEASE","reason":"verified with customer"}'; echo

echo "== 4. reconciliation run"
post "/admin/reconciliation/run?wait=true" '{}'; echo

echo "== 5. statement Q&A"
post "/users/$asha/statement/ask" '{"question":"How much did I send to Rahul this month?"}'; echo
echo "SMOKE OK"
