# Solution — вынести внешний вызов из транзакции

Эта ветка содержит фикс, которого нет в `master`. Читайте после того, как группа
сформулировала причину и подкрепила её метриками.

## Что было не так

В `master` весь checkout — одна транзакция:

```java
@Transactional
public OrderResult placeOrder(PlaceCommand command) {
    orders.save(order);                    // соединение взято из пула
    paymentGateway.authorize(orderId, ...); // 800 мс внешнего I/O
    paymentAttempts.save(...);              // всё ещё то же соединение
    auditEvents.save(...);
}
```

Транзакция начинается до первого запроса к БД и заканчивается после ответа шлюза.
Всё это время Hikari-соединение занято, хотя база не работает. При пуле из 10
соединений и задержке 800 мс потолок — примерно 12,5 заказа в секунду. Нагрузка
20/с создаёт очередь; новые запросы висят в `getConnection`, клиенты получают
таймаут, а при достаточном ожидании — `503 DB_POOL_TIMEOUT`.

## Что изменено

### 1. Появился `OrderPersistenceService`

Только он работает с БД, и только в коротких транзакциях:

| Метод | Транзакция | Что делает |
|---|---|---|
| `findByIdempotencyKey` | `readOnly` | проверка дубликата |
| `createPending` | короткая | заказ `PENDING` + аудит |
| `markPaid` | короткая | попытка оплаты, `PAID`, аудит |
| `markFailed` | короткая | `FAILED` + причина, аудит |

### 2. `OrderService` стал оркестратором без транзакции

```java
public OrderResult placeOrder(PlaceOrderCommand command) {
    var existing = persistence.findByIdempotencyKey(command.idempotencyKey());
    ...
    persistence.createPending(orderId, command);   // транзакция #1, миллисекунды
    authorization = paymentGateway.authorize(...); // вне транзакции
    persistence.markPaid(orderId, authorization);  // транзакция #2, миллисекунды
}
```

Соединение берётся на время двух коротких запросов, а не на всё время заказа.
Пул из 10 соединений перестаёт быть потолком в 12,5 заказа/с.

### 3. Ошибка оплаты фиксируется отдельной транзакцией

Если шлюз бросил исключение, `markFailed` переводит заказ в `FAILED` и пишет аудит.
Это осознанный компромисс: заказ остаётся видимым со статусом `FAILED`, а не
исчезает вместе с откатом. Клиент получает ошибку, состояние заказа можно
разобрать.

### 4. Метрика переехала в `outside_transaction`

`workshop_payment_call_seconds{scope="outside_transaction"}` подтверждает, что
внешний вызов больше не выполняется при открытой транзакции. Значение
`inside_transaction` должно остаться нулевым.

### 5. Регрессионный тест

`TransactionBoundaryTest` проверяет три вещи:

- в момент вызова `PaymentGateway.authorize` активной транзакции нет;
- при отказе шлюза вызывается `markFailed`, исключение пробрасывается;
- у `placeOrder` нет аннотации `@Transactional`.

Тест не требует БД: он подменяет persistence и gateway, поэтому падает именно на
нарушении границы, а не на инфраструктуре.

## Проверка

```bash
git checkout solution
docker compose up -d --build order-service
./scripts/verify-fix.sh
```

Ожидаемый вывод:

```text
OK    transactionScope=narrow
OK    the external call runs outside the transaction
OK    the pool is not exhausted under load
FIX VERIFIED
```

Критерии приёмки:

- `workshop_payment_call_seconds_count{scope="inside_transaction"} = 0`;
- `workshop_payment_call_seconds_count{scope="outside_transaction"} > 0`;
- `workshop_pool_saturated = 0`;
- `hikaricp_connections_pending <= 2`;
- нет клиентских таймаутов и 5xx;
- p95 заказа остаётся около 0,9 с.

## Почему не «просто увеличить пул»

Увеличение пула с 10 до 40 поднимает потолок до 50 заказов/с, но не меняет
механизм: соединение всё ещё удерживается на время внешнего вызова. При росте
нагрузки очередь вернётся, а вместе с ней — больше занятых соединений и больше
памяти. Это отсрочка, а не фикс.

Другие варианты и их цена:

| Вариант | Проблема |
|---|---|
| увеличить `connection-timeout` | ошибки позже, latency хуже, причина та же |
| `@Async` / очередь | нужны идемпотентность, retry и обработка partial failure |
| `REQUIRES_NEW` для шлюза | внешний вызов не нужен в транзакции; лишняя сложность |
| кэш/шлюз без I/O | не решает общий случай внешнего вызова |

## Ограничения фикса

- Заказ создаётся до оплаты. Если процесс упадёт между `createPending` и
  `markPaid`, останется `PENDING` — нужен фоновый reconciler или таймаут заказа.
- Повторный запрос с тем же `idempotencyKey` вернёт существующий заказ, но
  конкурентные вставки всё ещё может остановить уникальный индекс. Для продакшена
  нужна обработка `DataIntegrityViolationException`.
- `markFailed` может не выполниться при недоступной БД; ошибка добавляется как
  suppressed, но заказ останется `PENDING`. Это задача reconciler-а, а не
  транзакционной границы.

Эти ограничения стоит проговорить на занятии: фикс убирает причину инцидента, но
не превращает систему в идеально надёжную.
