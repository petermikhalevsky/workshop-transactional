#!/usr/bin/env bash
# Состояние PostgreSQL во время инцидента: кто держит транзакции, кто чего ждёт
# и есть ли блокировки на уровне БД. Помогает исключить «дедлок в базе».
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

DB_USER="${POSTGRES_USER:-workshop}"
DB_NAME="${POSTGRES_DB:-workshop}"
PSQL="docker compose exec -T postgres psql -U $DB_USER -d $DB_NAME -X -P pager=off"
EXCLUDE_SELF="AND pid <> pg_backend_pid() AND query NOT LIKE '%pg_stat_activity%'"

section() {
    echo
    echo "=== $1 ==="
}

section "1. Client sessions and transaction age"
$PSQL -c "
SELECT pid,
       state,
       coalesce(wait_event_type, '-') AS wait_type,
       coalesce(wait_event, '-')       AS wait_event,
       to_char(coalesce(now() - xact_start, interval '0'), 'HH24:MI:SS') AS xact_age,
       left(regexp_replace(query, '\s+', ' ', 'g'), 70) AS query
FROM pg_stat_activity
WHERE datname = '$DB_NAME'
  AND backend_type = 'client backend'
  $EXCLUDE_SELF
ORDER BY xact_start NULLS LAST, pid
LIMIT 30;"

section "2. Sessions by state"
$PSQL -c "
SELECT state, count(*) AS sessions
FROM pg_stat_activity
WHERE datname = '$DB_NAME'
  AND backend_type = 'client backend'
  $EXCLUDE_SELF
GROUP BY state
ORDER BY sessions DESC;"

section "3. 'idle in transaction': the transaction is open but the server does nothing"
$PSQL -c "
SELECT pid,
       application_name,
       to_char(now() - xact_start, 'HH24:MI:SS') AS xact_age,
       to_char(now() - state_change, 'HH24:MI:SS') AS idle_age,
       left(regexp_replace(query, '\s+', ' ', 'g'), 70) AS last_query
FROM pg_stat_activity
WHERE datname = '$DB_NAME'
  AND state = 'idle in transaction'
  $EXCLUDE_SELF
ORDER BY xact_start
LIMIT 30;"

section "4. Who blocks whom (pg_blocking_pids)"
$PSQL -c "
SELECT blocked.pid                AS blocked_pid,
       blocked.wait_event_type,
       blocked.wait_event,
       pg_blocking_pids(blocked.pid) AS blocking_pids,
       left(blocked.query, 70)     AS blocked_query
FROM pg_stat_activity blocked
WHERE cardinality(pg_blocking_pids(blocked.pid)) > 0;"

section "5. Held locks"
$PSQL -c "
SELECT a.pid,
       l.locktype,
       l.mode,
       l.granted,
       l.relation::regclass AS relation,
       count(*) AS locks
FROM pg_locks l
JOIN pg_stat_activity a ON a.pid = l.pid
WHERE a.datname = '$DB_NAME'
GROUP BY a.pid, l.locktype, l.mode, l.granted, l.relation
ORDER BY count(*) DESC
LIMIT 20;"

section "6. Long transactions (> 5 seconds)"
$PSQL -c "
SELECT pid,
       application_name,
       to_char(now() - xact_start, 'HH24:MI:SS') AS xact_age,
       state,
       left(regexp_replace(query, '\s+', ' ', 'g'), 90) AS query
FROM pg_stat_activity
WHERE datname = '$DB_NAME'
  AND xact_start IS NOT NULL
  AND now() - xact_start > interval '5 seconds'
ORDER BY xact_start
LIMIT 20;"

echo
echo "How to read this:"
echo "  - many 'idle in transaction' with an empty section 4: the database is not the bottleneck;"
echo "    transactions are open but waiting for an external call, not for a lock."
echo "  - if section 4 is empty while the app is stuck, look at the JVM and the connection pool."
