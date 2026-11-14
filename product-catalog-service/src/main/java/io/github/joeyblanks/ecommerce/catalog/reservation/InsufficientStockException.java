package io.github.joeyblanks.ecommerce.catalog.reservation;

import java.util.List;
import java.util.UUID;

/** At least one item cannot be reserved; nothing was reserved. */
public class InsufficientStockException extends RuntimeException {

    public enum Reason { INSUFFICIENT_STOCK, PRODUCT_NOT_FOUND }

    public record Shortage(UUID productId, int requested, int available, Reason reason) {
    }

    private final List<Shortage> shortages;

    public InsufficientStockException(List<Shortage> shortages) {
        super("Not all items could be reserved; nothing was reserved");
        this.shortages = List.copyOf(shortages);
    }

    public List<Shortage> shortages() {
        return shortages;
    }
}
