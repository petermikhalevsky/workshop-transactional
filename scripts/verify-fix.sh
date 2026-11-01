#!/usr/bin/env bash
# Приёмка фикса: вызов шлюза вне транзакции, пул не исчерпан.
#
# Запускается только на ветке solution и только из корня репозитория:
#   ./scripts/verify-fix.sh
#
# Код возврата: 0 — фикс подтверждён, 2 — нет.
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

MGMT_URL="${MGMT_URL:-http://localhost:8081}"
LOAD_DURATION="${LOAD_DURATION:-60}"
LOAD_RATE="${LOAD_RATE:-20}"

fail() {
    echo "  FAIL  $*" >&2
    exit 2
}

metric() {
    local name="$1" filter="${2:-}"
    curl -s -m 5 "$MGMT_URL/actuator/prometheus" \
        | awk -v m="$name" -v f="$filter" '
            $1 ~ "^"m"\\{" || $1 == m {
                if (f == "" || $0 ~ f) { print $2; exit }
            }'
}

echo "=== 1. Service ==="
curl -s -m 5 "$MGMT_URL/actuator/health/liveness" | grep -q '"status":"UP"' \
    || fail "liveness is not UP"
echo "  OK    liveness UP"

status=$(curl -s -m 5 "$MGMT_URL/actuator/workshop")
scope=$(printf '%s' "$status" | sed -n 's/.*"transactionScope":"\([a-z]*\)".*/\1/p')
[[ "$scope" == "narrow" ]] || fail "expected transactionScope=narrow, got '${scope:-unknown}'"
echo "  OK    transactionScope=narrow"

echo
echo "=== 2. Load: ${LOAD_DURATION}s at ${LOAD_RATE} orders/s ==="
./scripts/start-load.sh "$LOAD_DURATION" "$LOAD_RATE" >/dev/null || fail "could not start load"
echo "  load started, waiting 45s"
sleep 45

echo
echo "=== 3. Evidence ==="
active=$(metric 'hikaricp_connections_active' 'pool="order-pool"')
pending=$(metric 'hikaricp_connections_pending' 'pool="order-pool"')
saturated=$(metric 'workshop_pool_saturated')
inside=$(metric 'workshop_payment_call_seconds_count' 'scope="inside_transaction"')
outside=$(metric 'workshop_payment_call_seconds_count' 'scope="outside_transaction"')

printf '  %-46s %s\n' 'hikaricp_connections_active' "${active:-0}"
printf '  %-46s %s\n' 'hikaricp_connections_pending' "${pending:-0}"
printf '  %-46s %s\n' 'workshop_pool_saturated' "${saturated:-0}"
printf '  %-46s %s\n' 'payment calls inside transaction' "${inside:-0}"
printf '  %-46s %s\n' 'payment calls outside transaction' "${outside:-0}"

echo
echo "=== 4. Verdict ==="
awk -v v="${inside:-0}" 'BEGIN{exit !(v == 0)}' \
    || fail "payment call still runs inside a transaction"
awk -v v="${outside:-0}" 'BEGIN{exit !(v > 0)}' \
    || fail "no outside_transaction payment calls recorded"
awk -v v="${saturated:-0}" 'BEGIN{exit !(v == 0)}' \
    || fail "connection pool is saturated"
awk -v v="${pending:-0}" 'BEGIN{exit !(v <= 2)}' \
    || fail "requests are still waiting for a connection"
echo "  OK    the external call runs outside the transaction"
echo "  OK    the pool is not exhausted under load"

docker compose --profile load stop load-generator >/dev/null
echo
echo "FIX VERIFIED"
