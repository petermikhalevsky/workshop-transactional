# Runbook — «заказы не проходят, сервис отвечает секундами»

## Триггер

Любое из условий:

- клиенты получают таймауты или не получают ответ на `POST /api/orders`;
- в ответах появились `503` с телом `{"code":"DB_POOL_TIMEOUT"}`;
- `workshop_pool_saturated = 1` или `hikaricp_connections_pending > 0` не рассасывается;
- `hikaricp_connections_active{pool="order-pool"} == 10` при низком CPU.

## Шаг 1. Зафиксировать симптом (2 минуты)

```bash
./scripts/health-check.sh
curl -s localhost:8081/actuator/workshop | jq .
```

Запишите время начала, p95, долю таймаутов/no-response, `active`, `pending`,
`activeTransactions`.
Не перезапускайте приложение: состояние потоков и очередь — главные улики.

## Шаг 2. Исключить инфраструктуру (3 минуты)

```bash
docker compose ps
curl -s localhost:9090/-/ready
curl -s localhost:8089/__admin/health
curl -s localhost:8081/actuator/health
```

Проверьте в Grafana:

- `process_cpu_usage` — низкий;
- `jvm_memory_used_bytes{area="heap"}` — без роста;
- PostgreSQL отвечает, `pg_isready` зелёный.

Если CPU/память/БД в норме, а latency высокая — причина не в них.

## Шаг 3. Метрики пула и транзакций (5 минут)

```bash
curl -s localhost:8081/actuator/prometheus \
  | grep -E 'hikaricp_connections_(active|idle|pending|max)|workshop_(transactions_active|orders_in_flight|pool_saturated)'
```

Ожидаемая картина инцидента:

| Метрика | Значение |
|---|---|
| `hikaricp_connections_active` | 10 |
| `hikaricp_connections_idle` | 0 |
| `hikaricp_connections_pending` | > 0, не падает |
| `workshop_transactions_active` | 10 |
| `workshop_orders_in_flight` | десятки |
| `process_cpu_usage` | < 0,1 |
| p95 `workshop_order_total_seconds` | ≈ 0,9 с (успешные), но растёт max |
| no-response у клиента | растёт |

Дальше посмотрите, **чем** заняты соединения:

```bash
curl -s localhost:8081/actuator/prometheus \
  | grep 'workshop_payment_call_seconds_count' | grep scope
```

Если все вызовы идут со `scope="inside_transaction"`, это факт о коде, а не диагноз:
соединение удерживается, пока выполняется внешний вызов.

## Шаг 4. Улики ДО рестарта (10 минут)

```bash
./scripts/thread-dump.sh
./scripts/diagnose-thread-dump.sh artifacts/thread-dump-*.txt
./scripts/db-sessions.sh
./scripts/collect-evidence.sh
```

Что искать:

- в дампе — потоки в `PaymentGateway.authorize` и потоки в
  `HikariPool.getConnection`;
- в PostgreSQL — много `state = 'idle in transaction'`, при этом
  `cardinality(pg_blocking_pids(pid)) = 0`;
- в логах — `=== CONNECTION POOL EXHAUSTED ===` и
  `Connection is not available, request timed out after 10000ms`.

Если `pg_blocking_pids` пуст, а приложение стоит — база не виновата.

## Шаг 5. Восстановление (3 минуты)

```bash
docker compose --profile load stop load-generator
./scripts/reset-workshop.sh
```

`reset-workshop.sh` перезапускает `order-service`, очищает таблицы и проверяет,
что пул и транзакции вернулись в норму. Рестарт допустим **только после** шага 4.

## Шаг 6. Проверить восстановление (3 минуты)

```bash
./scripts/health-check.sh          # exit 0
curl -s localhost:8081/actuator/workshop | jq .
```

Критерии:

- `poolSaturated = false`;
- `activeTransactions = 0`;
- `pending = 0`;
- p95 завершённых заказов снова ниже 1 секунды;
- клиентских таймаутов и новых 5xx нет.

## Шаг 7. Фикс и приёмка (10 минут)

Фикс лежит в ветке `solution` (см. `docs/SOLUTION.md` после переключения):

```bash
git checkout solution
docker compose up -d --build order-service
./scripts/start-load.sh 300 20
./scripts/verify-fix.sh
git checkout master
docker compose up -d --build order-service
```

`verify-fix.sh` должен вернуть exit 0: вызов шлюза вне транзакции, пул не насыщен,
таймаутов и 5xx нет.

## Шаг 8. Разбор

Заполните `docs/POSTMORTEM_TEMPLATE.md` по фактам, а не по памяти. Приложите
каталог `artifacts/incident-*`.

## Чего не делать

- **Не увеличивать пул первым делом.** Это отсрочка, а не фикс: при росте нагрузки
  очередь вернётся, а соединений станет больше.
- **Не увеличивать `connection-timeout`.** Это удлинит ожидание и сделает симптом
  менее заметным, не убрав причину.
- **Не перезапускать до снятия дампа.** После рестарта улик нет.
- **Не искать блокировки в PostgreSQL, если `pg_blocking_pids` пуст.**
- **Не менять код в `master`.** Стенд должен оставаться воспроизводимым; фикс — в
  `solution`.
