-- One row per event that must produce an email. event_id is UNIQUE: that is the first and main guard
-- against sending the same email twice for one event (ADR 010). The recipient's email address is NEVER
-- stored here: it is looked up from user-service when the email is sent and then forgotten.
CREATE TABLE notifications (
    id              UUID PRIMARY KEY,
    event_id        UUID        NOT NULL UNIQUE,
    kind            VARCHAR(32) NOT NULL CHECK (kind IN ('ORDER_PLACED', 'ORDER_PAID', 'ORDER_CANCELLED')),
    user_id         UUID        NOT NULL,
    order_id        UUID        NOT NULL,
    payload         JSONB       NOT NULL,          -- what the email needs from the event (no address)
    status          VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_until     TIMESTAMPTZ,                   -- a worker owns the row until then
    last_error      VARCHAR(500),
    created_at      TIMESTAMPTZ NOT NULL,
    sent_at         TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_notifications_due ON notifications (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX idx_notifications_sent ON notifications (sent_at) WHERE status = 'SENT';
