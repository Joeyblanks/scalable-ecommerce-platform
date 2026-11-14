CREATE TABLE categories (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- Category names are unique ignoring case (the service also trims them).
CREATE UNIQUE INDEX categories_name_lower_uq ON categories (lower(name));

CREATE TABLE products (
    id             UUID           PRIMARY KEY,
    sku            VARCHAR(64)    NOT NULL UNIQUE,
    name           VARCHAR(200)   NOT NULL,
    description    TEXT,
    price          NUMERIC(12, 2) NOT NULL CHECK (price >= 0),
    stock_quantity INTEGER        NOT NULL CHECK (stock_quantity >= 0),
    category_id    UUID           NOT NULL REFERENCES categories (id),
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX products_category_id_idx ON products (category_id);
-- Supports the default listing order (name, id).
CREATE INDEX products_name_id_idx ON products (name, id);
