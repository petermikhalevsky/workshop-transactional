#!/usr/bin/env bash
# Снимает thread dump без JDK внутри контейнера: SIGQUIT (kill -3) печатает дамп в stdout.
# В JRE-only образе нет jstack/jcmd, но JVM всегда отвечает на SIGQUIT.
#
# ВАЖНО: снимайте дамп ДО перезапуска. После рестарта состояние потоков исчезает навсегда.
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

export MSYS_NO_PATHCONV=1

SERVICE="${SERVICE:-order-service}"
ARTIFACTS="${ARTIFACTS:-artifacts}"

mkdir -p "$ARTIFACTS"
ts=$(date +%Y%m%d-%H%M%S)
out="$ARTIFACTS/thread-dump-$ts.txt"

echo ">> Sending SIGQUIT to $SERVICE (PID 1)"
docker compose exec -T "$SERVICE" sh -c 'kill -3 1' || {
    echo "!! Failed to send the signal" >&2
    exit 1
}

echo ">> Waiting 4 seconds for the JVM to flush the dump to stdout"
sleep 4

echo ">> Exporting container logs"
# Нужен именно docker logs, а не docker compose logs: compose добавляет префикс
# "order-service  | ", из-за которого парсер не видит заголовки потоков.
# --tail all, а не --tail 0: ноль означает «не показывать строки», а не «показать всё».
CONTAINER=$(docker compose ps --format '{{.Name}}' "$SERVICE" | head -1)
raw="$out.raw"
docker logs --tail all "${CONTAINER:-$SERVICE}" > "$raw"

start=$(grep -n 'Full thread dump' "$raw" | tail -1 | cut -d: -f1)
if [ -n "$start" ]; then
    tail -n +"$start" "$raw" > "$out"
else
    cp "$raw" "$out"
    echo ">> WARNING: marker 'Full thread dump' not found, saved the whole log" >&2
fi
rm -f "$raw"

threads=$(grep -c '^"' "$out" || true)
blocked=$(grep -c 'java.lang.Thread.State: BLOCKED' "$out" || true)
waiting=$(grep -c 'java.lang.Thread.State: WAITING' "$out" || true)
timed=$(grep -c 'java.lang.Thread.State: TIMED_WAITING' "$out" || true)
hikari_wait=$(grep -c 'com.zaxxer.hikari.pool.HikariPool.getConnection' "$out" || true)
payment_call=$(grep -c 'com.workshop.orders.external.PaymentGateway.authorize' "$out" || true)

echo
echo "== Summary =="
echo "  file:            $out"
echo "  threads:         $threads"
echo "  BLOCKED:         $blocked"
echo "  WAITING:         $waiting"
echo "  TIMED_WAITING:   $timed"
echo "  waiting Hikari:  $hikari_wait"
echo "  in payment call: $payment_call"

if grep -q 'Found one Java-level deadlock' "$out"; then
    echo "  JVM DETECTOR:    a wait-for cycle was found"
else
    echo "  JVM DETECTOR:    no wait-for cycle"
fi

echo
echo "Next: ./scripts/diagnose-thread-dump.sh $out"
