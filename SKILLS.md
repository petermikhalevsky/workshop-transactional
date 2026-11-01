# SKILLS — Transactional War Room

Памятка для следующей сессии: что уже сломало работу и почему стенд устроен именно так.

## Что это

Стенд про исчерпание пула соединений: внешний платёжный вызов выполняется внутри
транзакции и удерживает соединение Hikari. `master` — сломанная версия, `solution` —
фикс. Сценарий не про дедлок и не про медленную БД.

## Перед любыми изменениями

```bash
docker compose up -d --build
./scripts/health-check.sh          # exit 0
./scripts/start-load.sh 60 20
sleep 35
./scripts/health-check.sh          # exit 2
docker compose --profile load stop load-generator
./scripts/reset-workshop.sh
```

Если инцидент не воспроизводится за минуту — не начинайте занятие.

## Три неочевидные вещи

### 1. Нагрузка должна быть rate-based, а не «раундами»

Первая версия `load.sh` отправляла 20 параллельных запросов, ждала их и спала
секунду. При инциденте раунд растягивался до 10 секунд, фактический RPS падал до
2–3, и очередь успевала рассосаться. Теперь новый запрос уходит каждые `1/rate`
секунды независимо от предыдущих: поток запросов не замедляется вместе с ответами.

### 2. Healthcheck — только по liveness

`/actuator/health` агрегирует состояние DataSource и под нагрузкой сам блокируется
на получении соединения. Docker решит, что сервис мёртв, и перезапустит его вместе
с уликами. Поэтому healthcheck смотрит на `/actuator/health/liveness`, который не
ходит в БД.

### 3. Пул не должен быть слишком большим

При 10 соединениях и задержке шлюза 800 мс потолок — 12,5 заказа/с. Нагрузка 20/с
гарантированно создаёт очередь за секунды. Если поднять пул до 40, симптом
размажется и станет невоспроизводимым на занятии.

## Ещё несколько граблей

- **`docker compose up` без `--build` поднимает образ предыдущей ветки.** После
  `git checkout` всегда пересобирайте `order-service`.
- **`docker compose logs` добавляет префикс `order-service  | `.** Для разбора
  thread dump используйте `docker logs` (в скрипте так и сделано).
- **`docker logs --tail 0` не показывает всё.** Нужен `--tail all`.
- **WireMock с `--global-response-templating` тратит время на шаблон.** Один
  шаблон на запрос допустим; если добавлять тяжёлые трансформации, мок станет
  узким местом. Для проверки сравнивайте `workshop_payment_call_seconds` с
  задержкой 800 мс.
- **`hikaricp_connections_pending` — gauge.** Он показывает текущую очередь, а не
  накопленное число таймаутов.
- **Ответа может не быть вовсе.** Плавная нагрузка даёт клиентские таймауты, а не
  `503`: очередь из 40 потоков рассасывается быстрее `connection-timeout=10000`.
  `Connection is not available` появляется, только если запрос реально провисел в
  Hikari все 10 секунд.
- **`idle in transaction` — не блокировка.** `pg_blocking_pids` пуст; не ищите
  дедлок в PostgreSQL.
- **Не перезапускайте до thread dump.** Рестарт уничтожает очередь и состояние
  потоков; остаётся только пересказ.

## Структура

```
app/                    Spring Boot, Maven, JRE-only runtime
db/init/                схема и seed
infra/wiremock/         платёжный шлюз, задержка 800 мс
prometheus/             скрейп каждые 2 секунды
grafana/                provisioning + transaction-dashboard.json
load-test/              rate-based генератор
scripts/                диагностика и приёмка
docs/                   CHEATSHEET, RUNBOOK, FACILITATOR, POSTMORTEM_TEMPLATE
```

## Проверка перед занятием

```bash
docker compose down -v
docker compose up -d --build
./scripts/health-check.sh                       # 0
./scripts/start-load.sh 60 20 && sleep 35
./scripts/health-check.sh                       # 2
./scripts/thread-dump.sh
./scripts/diagnose-thread-dump.sh artifacts/thread-dump-*.txt | tail -20
./scripts/db-sessions.sh | grep -c 'idle in transaction'
docker compose --profile load stop load-generator
./scripts/reset-workshop.sh
git checkout solution && docker compose up -d --build order-service
./scripts/verify-fix.sh                         # 0
git checkout master && docker compose up -d --build order-service
for f in scripts/*.sh; do bash -n "$f" || echo "FAIL $f"; done
```

Плюс глазами:

- [ ] дашборд открывается без логина и наполняется;
- [ ] `CHEATSHEET.md` помещается на одну страницу;
- [ ] в `RUNBOOK.md` нет шага, не проверенного на стенде;
- [ ] `git diff --stat master solution` — компактный, только фикс и документы.

## Правила, которые стоит сохранять

1. Сначала улики, потом рестарт.
2. Диагноз — из метрик, а не из кода.
3. Стенд не подсказывает фикс в выводе скриптов.
4. `verify-fix.sh` меряет механизм, а не следствие: вызов вне транзакции, пул не
   насыщен, 5xx нет.
5. После любого коммита в `master` — `git checkout solution && git rebase master`,
   иначе ветка фикса отстанет.
