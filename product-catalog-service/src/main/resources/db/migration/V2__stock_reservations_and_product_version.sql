-- Optimistic locking for products: every change (admin edit or stock reservation) bumps it.
ALTER TABLE products ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- One row per reservation id (= order id). RELEASED rows are kept: releasing an id that was never
-- reserved leaves such a "tombstone", so a reserve request that arrives late is refused.
CREATE TABLE stock_reservations (
    id          UUID         PRIMARY KEY,
    status      VARCHAR(20)  NOT NULL CHECK (status IN ('RESERVED', 'RELEASED')),
    created_by  VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- What was reserved, with the price/sku/name read in the same transaction (the authoritative price).
-- No foreign key to products: a product may be deleted later without losing reservation history.
CREATE TABLE stock_reservation_items (
    reservation_id UUID           NOT NULL REFERENCES stock_reservations (id),
    product_id     UUID           NOT NULL,
    sku            VARCHAR(64)    NOT NULL,
    name           VARCHAR(200)   NOT NULL,
    unit_price     NUMERIC(12, 2) NOT NULL,
    quantity       INTEGER        NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (reservation_id, product_id)
);
