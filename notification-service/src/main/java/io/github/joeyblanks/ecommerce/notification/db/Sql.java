package io.github.joeyblanks.ecommerce.notification.db;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Small helper for TIMESTAMPTZ columns. */
public final class Sql {

    private Sql() {
    }

    public static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
