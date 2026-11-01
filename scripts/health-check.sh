#!/usr/bin/env bash
# Проверка стенда: контейнеры, порты, метрики и состояние пула.
# Работает и как pre-flight до занятия, и как светофор во время инцидента.
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

APP_URL="${APP_URL:-http://localhost:8080}"
MGMT_URL="${MGMT_URL:-http://localhost:8081}"
PROM_URL="${PROM_URL:-http://localhost:9090}"
GRAFANA_URL="${GRAFANA_URL:-http://localhost:3000}"
WIREMOCK_URL="${WIREMOCK_URL:-http://localhost:8089}"

ok=0
warn=0
fail=0

green() { printf '\033[32m%s\033[0m\n' "$1"; }
yellow() { printf '\033[33m%s\033[0m\n' "$1"; }
red() { printf '\033[31m%s\033[0m\n' "$1"; }

check() {
    local label="$1" code="$2" expected="$3"
    if [[ "$code" == "$expected" ]]; then
        green "  OK    $label ($code)"
        ok=$((ok + 1))
    else
        red   "  FAIL  $label (code $code, expected $expected)"
        fail=$((fail + 1))
    fi
}

check_warning() {
    local label="$1" code="$2"
    if [[ "$code" == "200" ]]; then
        green "  OK    $label"
    else
        yellow "  WARN  $label (code $code)"
        warn=$((warn + 1))
    fi
}

metric() {
    curl -s -m 5 "$MGMT_URL/actuator/prometheus" \
        | awk -v m="$1" '$0 ~ "^"m"\\{" {print $NF; exit}'
}

echo "=== 1. Containers ==="
docker compose ps --format '  {{.Name}}\t{{.Status}}' 2>/dev/null || red "  failed to run docker compose ps"

echo
echo "=== 2. Ports ==="
check "order-service /api/health" \
    "$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$APP_URL/api/health")" 200
check "management /actuator/health/liveness" \
    "$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$MGMT_URL/actuator/health/liveness")" 200
check "management /actuator/prometheus" \
    "$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$MGMT_URL/actuator/prometheus")" 200
check "management /actuator/workshop" \
    "$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$MGMT_URL/actuator/workshop")" 200
check "prometheus ready" \
    "$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$PROM_URL/-/ready")" 200
check_warning "grafana" \
    "$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$GRAFANA_URL/api/health")"
check_warning "wiremock" \
    "$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$WIREMOCK_URL/__admin/health")"

echo
echo "=== 3. Application state (management port) ==="
status=$(curl -s -m 5 "$MGMT_URL/actuator/workshop")
echo "$status" | sed 's/^/  /'

scope=$(echo "$status" | sed -n 's/.*"transactionScope":"\([a-z]*\)".*/\1/p')
saturated=$(echo "$status" | sed -n 's/.*"poolSaturated":\(true\|false\).*/\1/p')
active=$(echo "$status" | sed -n 's/.*"pool":{"active":\([0-9]*\).*/\1/p')
pending=$(echo "$status" | sed -n 's/.*"pending":\([0-9]*\).*/\1/p')
transactions=$(echo "$status" | sed -n 's/.*"activeTransactions":\([0-9]*\).*/\1/p')

echo
echo "=== 4. Key metrics (management port) ==="
for name in workshop_pool_saturated workshop_transactions_active workshop_orders_in_flight \
            hikaricp_connections_active hikaricp_connections_pending hikaricp_connections_idle; do
    value=$(metric "$name")
    printf '  %-40s %s\n' "$name" "${value:-<no data>}"
done

echo
echo "=== 5. Metrics as Prometheus sees them ==="
for query in 'up{job="order-service"}' \
             'max(workshop_pool_saturated)' \
             'max(hikaricp_connections_active{pool="order-pool"})' \
             'max(hikaricp_connections_pending{pool="order-pool"})' \
             'sum(rate(workshop_payment_call_seconds_count[1m])) by (scope)' \
             'histogram_quantile(0.95, sum(rate(workshop_order_total_seconds_bucket[1m])) by (le))'; do
    value=$(curl -s -m 5 --get --data-urlencode "query=$query" "$PROM_URL/api/v1/query" \
        | sed -n 's/.*"value":\[[^,]*,\([^]]*\)\].*/\1/p')
    printf '  %-70s %s\n' "$query" "${value:-<no data>}"
done

echo
echo "=== 6. Check summary ==="
echo "  OK: $ok, WARN: $warn, FAIL: $fail"

echo
echo "=== 7. Verdict ==="
echo "  scope=${scope:-?}, saturated=${saturated:-?}, active=${active:-?}, pending=${pending:-?}, tx=${transactions:-?}"
if [[ "$saturated" == "true" ]]; then
    red "  INCIDENT: the connection pool is exhausted, new orders are waiting for a connection."
    echo "        Take the thread dump BEFORE restarting: ./scripts/thread-dump.sh"
    echo "        See what holds the transactions: ./scripts/db-sessions.sh"
    exit 2
fi
if [[ "${pending:-0}" -gt 0 ]]; then
    yellow "  WARNING: a connection queue has appeared (pending=${pending})."
    echo "        This is the start of the degradation. Watch Grafana and Hikari."
    exit 0
fi
green "  SERVICE ALIVE: the pool is not exhausted, no one is waiting for a connection."
exit 0
