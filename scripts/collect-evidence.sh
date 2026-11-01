#!/usr/bin/env bash
# Собирает полный набор доказательств инцидента в один каталог.
# Ничего не перезапускает и не меняет состояние — можно прикладывать к разбору.
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ARTIFACTS="${ARTIFACTS:-artifacts}"
MGMT_URL="${MGMT_URL:-http://localhost:8081}"
APP_URL="${APP_URL:-http://localhost:8080}"
PROM_URL="${PROM_URL:-http://localhost:9090}"

ts=$(date +%Y%m%d-%H%M%S)
out="$ARTIFACTS/incident-$ts"
mkdir -p "$out"

echo ">> Collecting evidence into $out"

docker compose ps > "$out/00-containers.txt" 2>&1
docker compose logs --no-color --tail 3000 order-service > "$out/01-app.log" 2>&1

curl -s -m 5 "$MGMT_URL/actuator/workshop"    > "$out/02-status.json"
curl -s -m 5 "$MGMT_URL/actuator/prometheus"  > "$out/03-metrics.prom"
curl -s -m 8 "$MGMT_URL/actuator/threaddump"  > "$out/04-threaddump-raw.txt"
curl -s -m 5 "$APP_URL/api/health"            > "$out/05-main-port-health.json"

echo ">> Taking a thread dump with kill -3"
./scripts/thread-dump.sh > "$out/06-thread-dump-report.txt" 2>&1 || true
dump=$(ls -1t "$ARTIFACTS"/thread-dump-*.txt 2>/dev/null | head -1 || true)
if [[ -n "$dump" ]]; then
    cp -f "$dump" "$out/07-thread-dump.txt"
fi

echo ">> Querying Prometheus"
{
    for q in \
        'up{job="order-service"}' \
        'max(workshop_pool_saturated)' \
        'max(hikaricp_connections_active{pool="order-pool"})' \
        'max(hikaricp_connections_pending{pool="order-pool"})' \
        'max(workshop_transactions_active)' \
        'max(workshop_orders_in_flight)' \
        'sum(rate(workshop_payment_call_seconds_count[2m])) by (scope)' \
        'histogram_quantile(0.95, sum(rate(workshop_order_total_seconds_bucket[2m])) by (le))' \
        'sum(rate(workshop_orders_total[2m])) by (result)' \
        'sum(rate(http_server_requests_seconds_count{status=~"5.."}[2m]))'
    do
        echo "### $q"
        curl -s -m 5 --get --data-urlencode "query=$q" "$PROM_URL/api/v1/query"
        echo
    done
} > "$out/08-prometheus-queries.json"

echo ">> Collecting PostgreSQL state"
./scripts/db-sessions.sh > "$out/09-postgres.txt" 2>&1 || true

cat > "$out/README.txt" <<'EOF'
Incident bundle contents:
  00-containers.txt          container state
  01-app.log                 application logs (including the SIGQUIT thread dump)
  02-status.json             snapshot of /actuator/workshop
  03-metrics.prom            raw metrics at collection time
  04-threaddump-raw.txt      thread dump in actuator format (JSON)
  05-main-port-health.json   /api/health response on the main port
  06-thread-dump-report.txt  thread dump summary
  07-thread-dump.txt         full thread dump as plain text
  08-prometheus-queries.json Prometheus answers for the key queries
  09-postgres.txt            PostgreSQL sessions, transactions and locks

Next: write the review with docs/POSTMORTEM_TEMPLATE.md
EOF

echo
echo ">> Done: $out"
ls -1 "$out" | sed 's/^/   /'
echo
echo "Next: docs/POSTMORTEM_TEMPLATE.md"
