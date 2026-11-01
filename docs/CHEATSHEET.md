# Transactional War Room — шпаргалка дежурного

> Одна страница. Держать открытой во время расследования.
> Правило: сначала улики, потом действия. Рестарт уничтожает состояние.

## 0. Порядок действий (не переставлять)

```bash
./scripts/health-check.sh              # 0 норма / 2 инцидент
./scripts/thread-dump.sh               # ДО рестарта
./scripts/db-sessions.sh               # исключить PostgreSQL
./scripts/collect-evidence.sh          # комплект улик в artifacts/incident-*
```

Только после этого — восстановление. Разбор — по `POSTMORTEM_TEMPLATE.md`.

## 1. Вопрос → метрика → норма → инцидент

| Вопрос | Метрика / команда | Норма | Инцидент |
|---|---|---|---|
| Сервис вообще жив? | `curl -s localhost:8081/actuator/health/liveness` | `UP` | нет ответа — стенд сломан |
| Растёт ли latency? | Grafana: HTTP latency p95/p99 и max | p95 < 1 с | p95 завершённых заказов ≈ 0,9 с, но max/доля таймаутов растут |
| Пул исчерпан? | `workshop_pool_saturated` | 0 | 1 |
| Сколько занято? | `hikaricp_connections_active{pool="order-pool"}` | < 10 | 10 (упёрлось в max) |
| Кто ждёт соединение? | `hikaricp_connections_pending` | 0 | > 0 и растёт |
| Есть свободные? | `hikaricp_connections_idle` | > 0 | 0 |
| Кто держит соединения? | `workshop_transactions_active` | 0–2 | 10 |
| Заказы в работе? | `workshop_orders_in_flight` | ≈ RPS × 1 с | десятки, не падает |
| Внешний вызов? | `workshop_payment_call_seconds{scope}` | p95 ≈ 0,8 с | p95 ≈ 0,8 с, но вызовов больше, чем пул |
| Где именно вызов? | `scope="inside_transaction"` | 0 | все вызовы внутри |
| Ошибки? | `workshop_orders_total{result="failed"}` / HTTP 5xx / клиентские таймауты | 0 | таймауты растут; 503 `DB_POOL_TIMEOUT` — только если запрос провисел в Hikari 10 с |
| CPU виноват? | `process_cpu_usage` | 0,2–0,5 | < 0,1 при высокой latency |
| Heap виноват? | `jvm_memory_used_bytes{area="heap"}` | пила | ровно, без роста |
| БД виновата? | `./scripts/db-sessions.sh` | нет блокировок | много `idle in transaction`, блокировок нет |
| Что в потоках? | `./scripts/thread-dump.sh` + diagnose | нет очереди | 10 в `PaymentGateway`, 30 в `HikariPool.getConnection` |

## 2. Быстрые команды

```bash
# состояние пула и транзакций
curl -s localhost:8081/actuator/workshop | jq .

# сырые метрики
curl -s localhost:8081/actuator/prometheus | grep -E 'hikaricp|workshop_'

# нагрузка
./scripts/start-load.sh 600 20
docker compose --profile load stop load-generator

# thread dump и разбор
./scripts/thread-dump.sh
./scripts/diagnose-thread-dump.sh artifacts/thread-dump-*.txt | tail -1
```

## 3. Как отличить дедлок от очереди за соединением

| Признак | Дедлок | Исчерпанный пул |
|---|---|---|
| Состояние потоков | `BLOCKED` | `WAITING` / `TIMED_WAITING` |
| CPU | низкий | низкий |
| PostgreSQL | блокировки есть | блокировок нет, `idle in transaction` |
| Само пройдёт? | нет | нет, но рестарт помогает |
| Ключевая метрика | цикл в wait-for graph | `hikaricp_connections_pending > 0` |

`Found one Java-level deadlock` в дампе — это **другой** сценарий. Здесь его быть не должно.

## 4. Ловушки диагностики

- **Ответа может не быть вовсе.** Запрос висит в очереди Tomcat или Hikari;
  `503 DB_POOL_TIMEOUT` появляется, только если запрос дошёл до пула и провисел
  `connection-timeout=10000`. Клиент часто отваливается по своему таймауту раньше.
- **`pending > 0` — это уже очередь, но не обязательно авария.** Авария — когда
  `active == max` **и** `pending` не рассасывается.
- **p95 по HTTP может быть ниже времени заказа**, если часть запросов падает
  раньше, чем завершается. Смотрите и `workshop_order_total_seconds`.
- **`hikaricp_connections_pending` — gauge, а не счётчик.** Он показывает текущую
  очередь, а не число таймаутов за всё время.
- **`idle in transaction` — не блокировка.** Это открытая транзакция, которая ничего
  не делает и держит соединение. `pg_blocking_pids` при этом пуст.
- **Не рестартуйте до thread dump.** После рестарта потоки и очередь исчезают,
  остаётся только пересказ.

## 5. Восстановление

```bash
docker compose --profile load stop load-generator
./scripts/thread-dump.sh          # если ещё не сняли
./scripts/collect-evidence.sh
./scripts/reset-workshop.sh       # рестарт приложения + очистка таблиц
./scripts/health-check.sh         # exit 0
```

## 6. Чек-лист для postmortem

- [ ] Симптом назван числом (p95, доля таймаутов, pending), а не «тормозило».
- [ ] Есть thread dump, снятый **до** рестарта.
- [ ] Есть вывод `db-sessions.sh`: блокировок нет, `idle in transaction` есть.
- [ ] Причина объясняет одновременно `active=max`, `pending>0` и `idle in transaction`.
- [ ] Фикс проверен `verify-fix.sh` на ветке `solution`.
- [ ] Записано, какая метрика поймала бы это раньше.
