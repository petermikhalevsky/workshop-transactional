#!/usr/bin/env bash
# =============================================================================
# Генератор нагрузки для Transactional War Room.
#
#   load.sh [duration_seconds] [rate_per_second]
#     duration  сколько секунд держать нагрузку        (по умолчанию 600)
#     rate      сколько заказов отправлять в секунду   (по умолчанию 20)
#
# Нагрузка rate-based: новый запрос уходит каждые 1/rate секунды, независимо
# от того, сколько предыдущих ещё не завершились. Это важно: при инциденте
# ответы замедляются, а поток запросов не должен замедляться вместе с ними.
#
# Каждый заказ получает уникальный idempotencyKey, поэтому все запросы
# доходят до платёжного шлюза и до БД.
#
# Переменные окружения: TARGET_URL, REQ_TIMEOUT, RESULTS_DIR
# =============================================================================
set -uo pipefail

DURATION=${1:-${DURATION:-600}}
RATE=${2:-${RATE:-20}}
REQ_TIMEOUT=${REQ_TIMEOUT:-15}
TARGET_URL=${TARGET_URL:-http://order-service:8080}
URL="${TARGET_URL}/api/orders"
RESULTS_DIR=${RESULTS_DIR:-"/artifacts/load-$(date +%H%M%S)"}
RESULTS_FILE="$RESULTS_DIR/requests.txt"
mkdir -p "$RESULTS_DIR"
: > "$RESULTS_FILE"

if ! [[ "$DURATION" =~ ^[0-9]+$ ]] || ! [[ "$RATE" =~ ^[0-9]+$ ]] \
        || [[ "$DURATION" -le 0 ]] || [[ "$RATE" -le 0 ]]; then
  echo "duration and rate must be positive integers" >&2
  exit 2
fi

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; CYAN=$'\033[36m'; RESET=$'\033[0m'
if [ ! -t 1 ]; then RED=""; GREEN=""; YELLOW=""; CYAN=""; RESET=""; fi

echo "=============================================================="
echo " Transactional load test"
echo "   target   : $URL"
echo "   duration : ${DURATION}s"
echo "   rate     : ${RATE} orders/s"
echo "   timeout  : ${REQ_TIMEOUT}s per request"
echo "   results  : ${RESULTS_DIR}"
echo "=============================================================="

if ! curl -sf --max-time 5 "${TARGET_URL}/api/health" >/dev/null 2>&1; then
  echo "${YELLOW}The service did not answer /api/health. Continuing: this is part of the scenario.${RESET}"
fi

send_order() {
  local seq=$1
  local customer="cust-$((1000 + RANDOM % 50))"
  local amount="$((100 + RANDOM % 4900)).$(printf '%02d' $((RANDOM % 100)))"
  local key="load-${seq}-$$-$(date +%s%N)"
  local res exitcode

  res=$(curl -s -o /dev/null -w '%{http_code} %{time_total}' \
        -X POST "$URL" \
        -H 'Content-Type: application/json' \
        -d "{\"customerId\":\"${customer}\",\"amount\":${amount},\"idempotencyKey\":\"${key}\"}" \
        --max-time "$REQ_TIMEOUT" 2>/dev/null)
  exitcode=$?
  printf '%s %s\n' "$res" "$exitcode" >> "$RESULTS_FILE"
}

LAST_LINE=0
TOTAL_SENT=0
TOTAL_OK=0
TOTAL_SRV=0
TOTAL_HANG=0

summarize() {
  local label=$1 window="$RESULTS_DIR/window.txt"
  local total_lines
  total_lines=$(wc -l < "$RESULTS_FILE" | tr -d ' ')
  if [[ "$total_lines" -le "$LAST_LINE" ]]; then
    printf '%s sent=%s (no new requests)\n' "$label" "$((total_lines - LAST_LINE))"
    return
  fi
  tail -n +"$((LAST_LINE + 1))" "$RESULTS_FILE" > "$window"

  local ok cli srv hang maxlat
  read -r ok cli srv hang maxlat < <(
    awk '
      {
        code = $1; t = $2 + 0
        total++
        if (code ~ /^2/) ok++
        else if (code ~ /^5/) srv++
        else if (code ~ /^4/) cli++
        else if (code == "000") hang++
        if (t > maxlat) maxlat = t
      }
      END { printf "%d %d %d %d %.3f\n", ok, cli, srv, hang, maxlat }
    ' "$window"
  )
  local sent=$((total_lines - LAST_LINE))
  local color=$GREEN
  [[ "${hang:-0}" -gt 0 ]] && color=$RED
  [[ "${srv:-0}" -gt 0 ]] && color=$YELLOW
  printf '%s%s%s sent=%s  2xx=%s  4xx=%s  5xx=%s  no-response=%s  max_latency=%ss\n' \
    "$color" "$label" "$RESET" "$sent" "$ok" "$cli" "$srv" "$hang" "$maxlat"

  TOTAL_SENT=$((TOTAL_SENT + sent))
  TOTAL_OK=$((TOTAL_OK + ok))
  TOTAL_SRV=$((TOTAL_SRV + srv))
  TOTAL_HANG=$((TOTAL_HANG + hang))
  LAST_LINE=$total_lines
}

trap 'echo; echo "${YELLOW}Load stopped by the operator.${RESET}"; exit 130' INT TERM

interval=$(awk -v r="$RATE" 'BEGIN { printf "%.4f", 1 / r }')
start_time=$(date +%s)
end_time=$((start_time + DURATION))
next_summary=$((start_time + 10))
seq=0

while [ "$(date +%s)" -lt "$end_time" ]; do
  seq=$((seq + 1))
  send_order "$seq" &
  sleep "$interval"

  now=$(date +%s)
  if [ "$now" -ge "$next_summary" ]; then
    summarize "[$(date +%H:%M:%S)]"
    next_summary=$((now + 10))
  fi
done

wait
summarize "[final]"

echo "=============================================================="
echo " Total: requests=${TOTAL_SENT}  2xx=${TOTAL_OK}  5xx=${TOTAL_SRV}  no-response=${TOTAL_HANG}"
echo " Request log: ${RESULTS_FILE}"
if [ "$TOTAL_SRV" -gt 0 ] || [ "$TOTAL_HANG" -gt 0 ]; then
  echo "${RED}${CYAN}Clients are getting errors or never get a response, that is the incident symptom.${RESET}"
  echo "${CYAN}Open Grafana -> Transactional Pool War Room and follow the symptoms.${RESET}"
else
  echo "${GREEN}All orders passed. If the incident did not reproduce, raise the rate.${RESET}"
fi
echo "=============================================================="
