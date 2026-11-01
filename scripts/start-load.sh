#!/usr/bin/env bash
# Запускает нагрузку на стенд.
#
#   scripts/start-load.sh                 # 600 секунд, 20 заказов в секунду
#   scripts/start-load.sh 300 30          # свои параметры
#
# 20 заказов в секунду — документированное значение: при задержке шлюза 800 мс
# и пуле из 10 соединений очередь появляется за несколько секунд.
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# Git Bash (MSYS) переписывает /load-test/... в C:/Program Files/Git/load-test/...
# Все пути ниже — пути внутри контейнера, конвертацию нужно отключить.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

DURATION="${1:-600}"
RATE="${2:-20}"
MGMT_URL="${MGMT_URL:-http://localhost:8081}"
RESULTS_IN_CONTAINER="/artifacts/load-$(date +%H%M%S).log"

if ! [[ "$DURATION" =~ ^[0-9]+$ ]] || ! [[ "$RATE" =~ ^[0-9]+$ ]] \
        || [[ "$DURATION" -le 0 ]] || [[ "$RATE" -le 0 ]]; then
    echo "Duration and rate must be positive integers" >&2
    exit 2
fi

echo ">> Target:     ${MGMT_URL} (management)"
echo ">> Starting load: ${DURATION}s, ${RATE} orders/s"

docker compose --profile load up -d --build load-generator >/dev/null

docker compose exec -d load-generator \
    sh -c "/load-test/load.sh $DURATION $RATE > $RESULTS_IN_CONTAINER 2>&1"

sleep 3
if docker compose exec -T load-generator sh -c "test -s $RESULTS_IN_CONTAINER" 2>/dev/null; then
    echo ">> Load started, log: $RESULTS_IN_CONTAINER"
else
    echo ">> ERROR: could not confirm the load started (log $RESULTS_IN_CONTAINER is empty)" >&2
    echo ">> Check manually: docker compose exec load-generator /load-test/load.sh 10 4" >&2
    exit 1
fi

echo ">> Grafana:   http://localhost:3000 (Transactional Pool War Room)"
echo ">> State:     ./scripts/health-check.sh"
echo ">> Log:       docker compose exec load-generator cat $RESULTS_IN_CONTAINER"
echo ">> Stop:      docker compose --profile load stop load-generator"
