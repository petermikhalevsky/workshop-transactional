-- =============================================================================
-- Схема стенда Transactional War Room.
-- Выполняется автоматически только при создании пустого тома postgres-data.
-- =============================================================================

CREATE TABLE IF NOT EXISTS orders (
    id              BIGSERIAL PRIMARY KEY,
    public_id       VARCHAR(36)  NOT NULL UNIQUE,
    customer_id     VARCHAR(64)  NOT NULL,
    amount          NUMERIC(19, 2) NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    idempotency_key VARCHAR(80)  NOT NULL UNIQUE,
    failure_reason  VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_orders_customer ON orders (customer_id);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders (status);

CREATE TABLE IF NOT EXISTS payment_attempts (
    id                 BIGSERIAL PRIMARY KEY,
    order_public_id    VARCHAR(36) NOT NULL,
    provider_reference VARCHAR(80) NOT NULL,
    status             VARCHAR(24) NOT NULL,
    latency_ms         BIGINT      NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_payment_attempts_order
    ON payment_attempts (order_public_id);

CREATE TABLE IF NOT EXISTS order_audit (
    id              BIGSERIAL PRIMARY KEY,
    order_public_id VARCHAR(36) NOT NULL,
    event_type      VARCHAR(40) NOT NULL,
    details         VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_order_audit_order ON order_audit (order_public_id);

-- Несколько исторических заказов, чтобы дашборд не выглядел пустым до нагрузки.
INSERT INTO orders (public_id, customer_id, amount, status, idempotency_key, created_at, updated_at)
VALUES
    ('seed-order-0001', 'cust-1001', 1490.00, 'PAID', 'seed-key-0001', NOW() - INTERVAL '2 hours', NOW() - INTERVAL '2 hours'),
    ('seed-order-0002', 'cust-1002', 320.50, 'PAID', 'seed-key-0002', NOW() - INTERVAL '1 hour', NOW() - INTERVAL '1 hour'),
    ('seed-order-0003', 'cust-1003', 899.90, 'FAILED', 'seed-key-0003', NOW() - INTERVAL '30 minutes', NOW() - INTERVAL '30 minutes')
ON CONFLICT (public_id) DO NOTHING;

INSERT INTO payment_attempts (order_public_id, provider_reference, status, latency_ms, created_at)
VALUES
    ('seed-order-0001', 'pay-seed-0001', 'APPROVED', 812, NOW() - INTERVAL '2 hours'),
    ('seed-order-0002', 'pay-seed-0002', 'APPROVED', 795, NOW() - INTERVAL '1 hour'),
    ('seed-order-0003', 'pay-seed-0003', 'DECLINED', 803, NOW() - INTERVAL '30 minutes')
ON CONFLICT DO NOTHING;

INSERT INTO order_audit (order_public_id, event_type, details, created_at)
VALUES
    ('seed-order-0001', 'ORDER_CREATED', 'Заказ создан и ожидает оплаты', NOW() - INTERVAL '2 hours'),
    ('seed-order-0001', 'ORDER_PAID', 'Платёж подтверждён: pay-seed-0001', NOW() - INTERVAL '2 hours'),
    ('seed-order-0002', 'ORDER_CREATED', 'Заказ создан и ожидает оплаты', NOW() - INTERVAL '1 hour'),
    ('seed-order-0002', 'ORDER_PAID', 'Платёж подтверждён: pay-seed-0002', NOW() - INTERVAL '1 hour'),
    ('seed-order-0003', 'ORDER_CREATED', 'Заказ создан и ожидает оплаты', NOW() - INTERVAL '30 minutes'),
    ('seed-order-0003', 'ORDER_FAILED', 'Платёж отклонён: DECLINED', NOW() - INTERVAL '30 minutes');
