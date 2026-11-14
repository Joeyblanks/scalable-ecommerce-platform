package io.github.joeyblanks.ecommerce.order.client;

import java.util.List;
import java.util.Map;

/** The catalog refused the reservation because something is short or gone; nothing was reserved. */
public class OutOfStockException extends RuntimeException {

    private final List<Map<String, Object>> shortages;

    public OutOfStockException(List<Map<String, Object>> shortages) {
        super("Some items are no longer available in the requested quantity; nothing was ordered");
        this.shortages = shortages == null ? List.of() : List.copyOf(shortages);
    }

    public List<Map<String, Object>> shortages() {
        return shortages;
    }
}
