#!/usr/bin/env bash
# Разбор thread dump для сценария «исчерпан пул соединений».
# Отвечает на два вопроса:
#   1) сколько потоков ждут соединение в Hikari;
#   2) что делают потоки, которые соединение уже получили.
#
#   scripts/diagnose-thread-dump.sh artifacts/thread-dump-20261101-120000.txt
#   docker logs transactional-order-service | scripts/diagnose-thread-dump.sh -
set -uo pipefail

file="${1:-}"
if [[ -z "$file" ]]; then
    echo "Usage: $0 <thread dump file | ->" >&2
    exit 2
fi

if [[ "$file" == "-" ]]; then
    tmp=$(mktemp)
    cat > "$tmp"
    file="$tmp"
fi

if [[ ! -s "$file" ]]; then
    echo "File is empty or not found: $file" >&2
    exit 1
fi

echo "=== Source: $file ==="

# docker compose logs добавляет префикс "order-service  | " к каждой строке.
clean="$file.normalized"
sed -e 's/^[^ "][^|]*| //' "$file" > "$clean"
trap 'rm -f "$clean"; [[ -n "${tmp:-}" ]] && rm -f "$tmp"' EXIT

total=$(grep -c '^"' "$clean" || true)
blocked=$(grep -c 'java.lang.Thread.State: BLOCKED' "$clean" || true)
waiting=$(grep -c 'java.lang.Thread.State: WAITING' "$clean" || true)
timed=$(grep -c 'java.lang.Thread.State: TIMED_WAITING' "$clean" || true)
runnable=$(grep -c 'java.lang.Thread.State: RUNNABLE' "$clean" || true)
hikari_wait=$(grep -c 'com.zaxxer.hikari.pool.HikariPool.getConnection' "$clean" || true)
payment_call=$(grep -c 'com.workshop.orders.external.PaymentGateway.authorize' "$clean" || true)
order_frames=$(grep -c 'com.workshop.orders.application.OrderService.placeOrder' "$clean" || true)
socket_read=$(grep -c 'socketRead0' "$clean" || true)

echo
echo "=== 1. Summary ==="
printf '  total threads:                   %s\n' "$total"
printf '  RUNNABLE:                        %s\n' "$runnable"
printf '  WAITING:                         %s\n' "$waiting"
printf '  TIMED_WAITING:                   %s\n' "$timed"
printf '  BLOCKED:                         %s\n' "$blocked"
printf '  waiting for a Hikari connection: %s\n' "$hikari_wait"
printf '  inside PaymentGateway:           %s\n' "$payment_call"
printf '  with OrderService.placeOrder:    %s\n' "$order_frames"

echo
echo "=== 2. Threads that hold a connection and sit in the external call ==="
awk -v pat='com.workshop.orders.external.PaymentGateway.authorize' '
BEGIN { RS=""; ORS="\n\n" }
$0 ~ pat && shown < 2 { print; shown++ }
' "$clean" | sed -n '1,40p'

echo
echo "=== 3. Threads that cannot get a connection and wait in Hikari ==="
awk -v pat='com.zaxxer.hikari.pool.HikariPool.getConnection' '
BEGIN { RS=""; ORS="\n\n" }
$0 ~ pat && shown < 2 { print; shown++ }
' "$clean" | sed -n '1,32p'

echo
echo "=== 4. Diagnosis ==="
if grep -q 'Found one Java-level deadlock' "$clean"; then
    echo "  The JVM found a wait-for cycle. That is a different scenario."
elif [[ "$hikari_wait" -gt 0 && "$payment_call" -gt 0 ]]; then
    echo "  Signs of pool exhaustion:"
    echo "    - ${payment_call} thread(s) hold a connection and wait for the payment gateway;"
    echo "    - ${hikari_wait} thread(s) cannot get a connection and wait inside Hikari;"
    echo "    - ${socket_read} thread(s) sit in socketRead0: network I/O, not CPU."
    echo "  Conclusion: connections are held by waiting for an external service, not by database work."
elif [[ "$hikari_wait" -gt 0 ]]; then
    echo "  ${hikari_wait} thread(s) wait for a connection. Check who holds connections:"
    echo "    ./scripts/db-sessions.sh"
    echo "    curl -s localhost:8081/actuator/workshop"
else
    echo "  No connection queue in this dump. Make sure it was taken during the incident."
fi

echo
echo "=== 5. What to do next ==="
echo "  1. Compare the number of waiting threads with hikaricp_connections_pending."
echo "  2. Check PostgreSQL: many 'idle in transaction' or real locks?"
echo "       ./scripts/db-sessions.sh"
echo "  3. Compare the external call time with the transaction holding time."
echo "  4. Write the review: docs/POSTMORTEM_TEMPLATE.md"
