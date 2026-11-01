#!/usr/bin/env bash
# Возвращает стенд в известное состояние перед новым прогоном.
# Обязательный шаг: потоки, застрявшие в ожидании соединения, сами не разойдутся —
# нужен перезапуск приложения, а данные нужно очистить.
#
#   scripts/reset-workshop.sh
#   KEEP_ARTIFACTS=1 scripts/reset-workshop.sh
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

MGMT_URL="${MGMT_URL:-http://localhost:8081}"
APP_URL="${APP_URL:-http://localhost:8080}"
ARTIFACTS="${ARTIFACTS:-artifacts}"
KEEP_ARTIFACTS="${KEEP_ARTIFACTS:-0}"
DB_USER="${POSTGRES_USER:-workshop}"
DB_NAME="${POSTGRES_DB:-workshop}"

echo ">> 1/5 Stopping the load generator"
docker compose --profile load stop load-generator >/dev/null 2>&1 || true
echo "   load stopped"

echo
echo ">> 2/5 Restarting order-service"
docker compose restart order-service >/dev/null
code=""
for _ in $(seq 1 40); do
    code=$(curl -s -o /dev/null -m 3 -w '%{http_code}' "$MGMT_URL/actuator/health/liveness" 2>/dev/null) || code=""
    [[ "$code" == "200" ]] && break
    sleep 2
done
if [[ "$code" == "200" ]]; then
    echo "   liveness: 200, the service responds"
else
    echo "   WARNING: the service did not start within 80s (code: ${code:-no response})" >&2
    echo "   Check: docker compose logs --tail 100 order-service" >&2
fi

echo
echo ">> 3/5 Truncating orders and related tables"
reset_code=""
for _ in $(seq 1 10); do
    reset_code=$(curl -s -o /dev/null -m 15 -w '%{http_code}' -X POST "$APP_URL/api/orders/reset" 2>/dev/null) || reset_code=""
    [[ "$reset_code" == "200" ]] && break
    sleep 2
done
if [[ "$reset_code" == "200" ]]; then
    echo "   orders, payment_attempts and order_audit are empty"
else
    echo "   WARNING: POST /api/orders/reset returned ${reset_code:-no response}, truncating directly" >&2
    docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -X -c \
        "TRUNCATE TABLE order_audit, payment_attempts, orders RESTART IDENTITY;" 2>&1 | sed 's/^/   /'
fi

echo
echo ">> 4/5 Checking the pool state"
status=$(curl -s -m 5 "$MGMT_URL/actuator/workshop")
echo "$status" | sed 's/^/   /'
echo
if echo "$status" | grep -q '"poolSaturated":false'; then
    echo "   OK: the pool is not saturated"
else
    echo "   WARNING: the pool is still saturated, wait a few seconds and check again" >&2
fi
if echo "$status" | grep -q '"activeTransactions":0'; then
    echo "   OK: no open transactions"
else
    echo "   WARNING: transactions are still open, see ./scripts/db-sessions.sh" >&2
fi

echo
if [[ "$KEEP_ARTIFACTS" != "1" ]]; then
    echo ">> 5/5 Removing old artifacts (KEEP_ARTIFACTS=1 keeps them)"
    rm -rf "$ARTIFACTS"/thread-dump-*.txt "$ARTIFACTS"/load-* "$ARTIFACTS"/incident-* 2>/dev/null || true
else
    echo ">> 5/5 Artifacts preserved"
fi

echo
echo ">> Done. The stand is clean, start a new run:"
echo "   ./scripts/start-load.sh 600 20"
echo "   Grafana: http://localhost:3000"
