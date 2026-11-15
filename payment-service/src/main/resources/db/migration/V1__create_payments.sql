-- One row per order: the single place every event handler and worker meets (ADR 009).
CREATE TABLE payments (
    id                   UUID          PRIMARY KEY,                -- also the provider idempotency key base
    -- UNIQUE: a second OrderPlaced for the same order (any event id) cannot create a second payment.
    order_id             UUID          NOT NULL UNIQUE,
    -- NULL user_id/amount = a tombstone: OrderCancelled arrived before OrderPlaced. Never charged.
    user_id              UUID,
    amount               NUMERIC(12, 2),
    currency             VARCHAR(3)    NOT NULL,
    status               VARCHAR(20)   NOT NULL CHECK (status IN
                         ('CHARGING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'REFUND_PENDING', 'REFUNDED', 'NEEDS_REVIEW')),
    -- set when OrderCancelled arrives while a charge may be in flight; honoured when the charge finishes
    cancel_requested     BOOLEAN       NOT NULL DEFAULT FALSE,
    failure_reason       VARCHAR(50),
    provider_ref         VARCHAR(100),
    refund_ref           VARCHAR(100),
    -- charging: claimed by a worker until lease_until; charge_attempts = 0 means no provider call was ever made
    charge_attempts      INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at      TIMESTAMPTZ   NOT NULL,
    lease_until          TIMESTAMPTZ,
    refund_attempts      INTEGER       NOT NULL DEFAULT 0,
    refund_next_attempt_at TIMESTAMPTZ,
    refund_lease_until   TIMESTAMPTZ,
    last_error           TEXT,
    created_at           TIMESTAMPTZ   NOT NULL,
    updated_at           TIMESTAMPTZ   NOT NULL,
    succeeded_at         TIMESTAMPTZ,
    failed_at            TIMESTAMPTZ,
    cancelled_at         TIMESTAMPTZ,
    refunded_at          TIMESTAMPTZ
);
CREATE INDEX payments_charge_due_idx ON payments (next_attempt_at) WHERE status = 'CHARGING';
CREATE INDEX payments_refund_due_idx ON payments (refund_next_attempt_at) WHERE status = 'REFUND_PENDING';
CREATE INDEX payments_user_idx ON payments (user_id);

-- Transactional outbox: written in the same transaction as the payment change, published by the relay.
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

-- The simulated provider's own ledger (what a real provider keeps on its side). Idempotent by key, so
-- "charged exactly once" survives restarts and can be asserted by tests.
CREATE TABLE simulated_charges (
    idempotency_key  VARCHAR(100) PRIMARY KEY,
    amount           NUMERIC(12, 2) NOT NULL,
    outcome          VARCHAR(20)  NOT NULL CHECK (outcome IN ('PENDING', 'SUCCEEDED', 'DECLINED')),
    failed_attempts  INTEGER      NOT NULL DEFAULT 0,
    provider_ref     VARCHAR(100),
    decline_reason   VARCHAR(50),
    refund_ref       VARCHAR(100),
    created_at       TIMESTAMPTZ  NOT NULL
);
CREATE UNIQUE INDEX simulated_charges_ref_uq ON simulated_charges (provider_ref) WHERE provider_ref IS NOT NULL;
