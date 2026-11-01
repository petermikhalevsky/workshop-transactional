# Transactional War Room — поиск проблемы в проде

Docker-стенд, на котором студент сам воспроизводит боевой инцидент с оформлением
заказов: **под нагрузкой новые заказы не проходят, клиенты висят до таймаута или
получают 503, CPU низкий, PostgreSQL отвечает быстро.** Стенд: Spring Boot 3.3
(Java 17), PostgreSQL 16, WireMock, Prometheus, Grafana и генератор нагрузки.

Стенд поднимается одной командой и не требует локального JDK: всё внутри Docker.

---

## Быстрый старт

```bash
docker compose up -d --build      # первый запуск ~3–5 минут (Maven внутри Docker)
docker compose ps                  # дождитесь healthy у order-service
./scripts/health-check.sh          # должен вернуть exit 0
```

| Что | Адрес | Логин |
|---|---|---|
| order-service (основной порт) | http://localhost:8080 | — |
| Actuator (management-порт) | http://localhost:8081/actuator | — |
| Prometheus | http://localhost:9090 | — |
| Grafana, дашборд «Transactional Pool War Room» | http://localhost:3000 | `admin` / `admin` |
| WireMock (платёжный шлюз) | http://localhost:8089 | — |
| PostgreSQL | `localhost:5432`, база `workshop` | `workshop` / `workshop` |

Grafana открывается без логина (anonymous viewer), поэтому для скриншотов и
самостоятельной работы пароль не нужен.

### Воспроизвести инцидент

```bash
./scripts/start-load.sh 600 20     # 600 секунд, 20 заказов в секунду
./scripts/health-check.sh          # через 30–60 секунд вернёт exit 2
```

Grafana → **Transactional Pool War Room**. Симптом обязан быть виден за минуту.

### Остановить и вернуть стенд в исходное состояние

```bash
docker compose --profile load stop load-generator
./scripts/reset-workshop.sh
```

Полный сброс вместе с базой:

```bash
docker compose down -v
docker compose up -d --build
```

---

## Что внутри

```
load-generator (профиль load)
        |
        v  POST /api/orders
order-service :8080  --->  PostgreSQL :5432
        |  \
        |   +--> WireMock :8089  (платёжный шлюз, задержка 800 мс)
        |
        +--> management :8081  <--- Prometheus :9090 <--- Grafana :3000
```

| Сервис | Роль |
|---|---|
| `order-service` | приложение; основной порт 8080, management 8081 |
| `postgres` | заказы, попытки оплаты, аудит |
| `wiremock` | внешний платёжный провайдер, отвечает через 800 мс |
| `prometheus` | скрейпит `/actuator/prometheus` каждые 2 секунды |
| `grafana` | дашборд с симптомами и доказательствами |
| `load-generator` | нагрузка; не стартует вместе со стендом (профиль `load`) |

### Поток оформления заказа

1. `POST /api/orders` с `customerId`, `amount`, `idempotencyKey`.
2. Сервис проверяет идемпотентность, создаёт заказ в статусе `PENDING`.
3. Вызывает платёжный шлюз.
4. Записывает попытку оплаты, переводит заказ в `PAID`, пишет аудит.
5. Возвращает `orderId`, статус, полное время обработки.

### Эндпоинты

| Метод | Путь | Назначение |
|---|---|---|
| `POST` | `/api/orders` | оформить заказ |
| `GET` | `/api/orders/{orderId}` | получить заказ |
| `GET` | `/api/health` | дешёвый liveness без обращения к БД |
| `POST` | `/api/orders/reset` | очистить таблицы (только для стенда) |
| `GET` | `/actuator/health/liveness` | liveness для Docker healthcheck |
| `GET` | `/actuator/workshop` | снимок пула и транзакций |
| `GET` | `/actuator/prometheus` | метрики для Prometheus |

Management-порт 8081 отдельный осознанно: метрики, thread dump и состояние пула
остаются доступны, даже когда основной порт занят ожиданием.

---

## Ключевые метрики

| Метрика | Что означает |
|---|---|
| `workshop_pool_saturated` | 1, когда все соединения заняты и есть очередь |
| `hikaricp_connections_active` | занятые соединения пула `order-pool` |
| `hikaricp_connections_pending` | потоки, ожидающие соединение |
| `hikaricp_connections_idle` | свободные соединения |
| `workshop_transactions_active` | транзакции, реально удерживающие соединение |
| `workshop_orders_in_flight` | заказы в обработке |
| `workshop_orders_total{result}` | результат заказов: success / failed / duplicate |
| `workshop_payment_call_seconds{scope}` | время вызова шлюза: inside/outside transaction |
| `workshop_order_total_seconds{result}` | полное время обработки заказа |
| `workshop_payment_last_latency` | задержка последнего вызова шлюза |
| `http_server_requests_seconds` | HTTP-латентность и коды ответов |
| `jvm_threads_states_threads` | состояния потоков JVM |

Счётчик `workshop_payment_call_seconds{scope="inside_transaction"}` — метрика-мост:
она фиксирует факт «вызов шлюза произошёл при открытой транзакции», но не называет
причину и не подсказывает фикс.

---

## Скрипты

| Скрипт | Роль | Код возврата |
|---|---|---|
| `health-check.sh` | состояние стенда и вердикт | 0 норма, 2 инцидент |
| `start-load.sh` | запуск нагрузки | — |
| `db-sessions.sh` | сессии, транзакции и блокировки PostgreSQL | — |
| `thread-dump.sh` | SIGQUIT-дамп без JDK | — |
| `diagnose-thread-dump.sh` | разбор дампа: кто ждёт Hikari, кто держит | — |
| `collect-evidence.sh` | комплект улик в `artifacts/incident-*` | — |
| `reset-workshop.sh` | стенд в исходное состояние | — |
| `verify-fix.sh` | приёмка фикса (только ветка `solution`) | 0 починил, 2 нет |

Все скрипты — из корня репозитория; они сами переходят в нужный каталог.

---

## Осознанные решения

- **JRE-only образ.** В контейнере нет `jstack`/`jcmd`: дамп снимается через
  `kill -3` и `docker logs`, как в реальном проде без JDK.
- **Management-порт 8081.** Основной порт может быть занят, а диагностика должна
  оставаться доступной.
- **Пул Hikari — 10 соединений, `connection-timeout` — 10 секунд.** Достаточно
  мало, чтобы очередь появилась за секунды, и достаточно велико, чтобы ошибки
  выглядели как таймауты, а не мгновенные отказы.
- **Tomcat — 40 потоков.** Потоки приложения шире пула БД: часть запросов ждёт
  соединение, но сервис продолжает принимать подключения.
- **Задержка WireMock — 800 мс.** Внешний вызов заметен в метриках, но не
  превращает мок в узкое место (пул потоков WireMock поднят).
- **Нет runtime-переключателя режимов.** Сломанное состояние — это коммит в
  `master`, фикс — отдельная ветка `solution`. Стенд не «чинится» нажатием кнопки.
- **Нагрузка не стартует автоматически.** Иначе стенд создаёт инцидент сразу
  после `up`, и facilitator не успевает показать здоровое состояние.

---

## Ветки

| Ветка | Что внутри |
|---|---|
| `master` | сломанная версия, которую получает студент |
| `solution` | `master` + фикс, `docs/SOLUTION.md` и `verify-fix.sh` |

```bash
git checkout solution
docker compose up -d --build order-service
./scripts/verify-fix.sh
git checkout master
docker compose up -d --build order-service
```

После переключения ветки **всегда** пересобирайте образ: `docker compose up` без
`--build` поднимает образ предыдущей ветки.

---

## Если что-то не работает

- **Порты заняты.** Остановите соседний стенд или задайте свои порты в `.env`
  (см. `.env.example`).
- **Пустая Grafana.** Проверьте `http://localhost:9090/targets`: job `order-service`
  должен быть `UP`. Метрики появляются через 5–10 секунд после старта.
- **Схема не создалась.** `db/init/01-schema.sql` выполняется только при пустом
  томе. Полный сброс: `docker compose down -v && docker compose up -d --build`.
- **load-generator не запускается.** Он под профилем: `docker compose --profile load
  up -d --build load-generator`.
- **Windows/Git Bash.** Скрипты уже отключают конвертацию путей (`MSYS_NO_PATHCONV`),
  но запускайте их из Git Bash, а не из PowerShell.

---

## Документы

- [`docs/CHEATSHEET.md`](docs/CHEATSHEET.md) — одна страница для расследования.
- [`docs/RUNBOOK.md`](docs/RUNBOOK.md) — порядок действий дежурного.
- [`docs/FACILITATOR.md`](docs/FACILITATOR.md) — тайминг занятия на 90 минут.
- [`docs/POSTMORTEM_TEMPLATE.md`](docs/POSTMORTEM_TEMPLATE.md) — форма разбора.
- [`SKILLS.md`](SKILLS.md) — грабли стенда для следующей сессии.
