CREATE TABLE orders (
    id              UUID           PRIMARY KEY,
    user_id         UUID           NOT NULL,
    -- RESERVING: attempt in progress (internal). FAILED: never reserved stock (kept for audit, hidden).
    status          VARCHAR(20)    NOT NULL CHECK (status IN ('RESERVING', 'PENDING', 'PAID', 'CANCELLED', 'FAILED')),
    cancel_reason   VARCHAR(30)    CHECK (cancel_reason IN ('CUSTOMER_REQUEST', 'PAYMENT_FAILED', 'PAYMENT_TIMEOUT')),
    failure_reason  VARCHAR(30)    CHECK (failure_reason IN ('OUT_OF_STOCK', 'CATALOG_UNAVAILABLE', 'SAVE_FAILED', 'STUCK')),
    -- Cleared when an attempt FAILS, so the client may retry with the same key.
    idempotency_key VARCHAR(100),
    total_amount    NUMERIC(12, 2),
    payment_id      VARCHAR(100),
    created_at      TIMESTAMPTZ    NOT NULL,
    updated_at      TIMESTAMPTZ    NOT NULL,
    paid_at         TIMESTAMPTZ,
    cancelled_at    TIMESTAMPTZ
);
CREATE UNIQUE INDEX orders_user_idempotency_uq ON orders (user_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX orders_user_created_idx ON orders (user_id, created_at DESC);
CREATE INDEX orders_status_created_idx ON orders (status, created_at);

CREATE TABLE order_items (
    order_id      UUID           NOT NULL REFERENCES orders (id),
    product_id    UUID           NOT NULL,
    sku           VARCHAR(64)    NOT NULL,
    name          VARCHAR(200)   NOT NULL,
    unit_price    NUMERIC(12, 2) NOT NULL,
    quantity      INTEGER        NOT NULL CHECK (quantity > 0),
    line_total    NUMERIC(12, 2) NOT NULL,
    -- true when the catalog price differs from the price the cart showed
    price_changed BOOLEAN        NOT NULL,
    PRIMARY KEY (order_id, product_id)
);

-- Transactional outbox: written in the same transaction as the order change, published by the relay.
CREATE TABLE outbox_events (
    id              UUID         PRIMARY KEY,          -- = eventId in the message
    aggregate_id    UUID         NOT NULL,
    event_type      VARCHAR(50)  NOT NULL,
    routing_key     VARCHAR(100) NOT NULL,
    payload         JSONB        NOT NULL,             -- the complete envelope as published
    created_at      TIMESTAMPTZ  NOT NULL,
    published_at    TIMESTAMPTZ,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    last_error      TEXT
);
CREATE INDEX outbox_unpublished_idx ON outbox_events (next_attempt_at, created_at) WHERE published_at IS NULL;
CREATE INDEX outbox_published_idx ON outbox_events (published_at) WHERE published_at IS NOT NULL;

-- Ids of consumed events, inserted in the same transaction as their effect (deduplication).
CREATE TABLE processed_events (
    event_id     UUID        PRIMARY KEY,
    event_type   VARCHAR(50) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX processed_events_processed_idx ON processed_events (processed_at);

-- Stock that must be given back to the catalog, retried until the catalog confirms.
CREATE TABLE stock_releases (
    order_id        UUID        PRIMARY KEY,
    created_at      TIMESTAMPTZ NOT NULL,
    completed_at    TIMESTAMPTZ,
    attempts        INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error      TEXT
);
CREATE INDEX stock_releases_pending_idx ON stock_releases (next_attempt_at) WHERE completed_at IS NULL;
