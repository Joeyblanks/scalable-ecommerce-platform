package io.github.joeyblanks.ecommerce.cart.service;

public class InsufficientStockException extends RuntimeException {

    private final int availableStock;
    private final int requestedTotal;

    public InsufficientStockException(int availableStock, int requestedTotal) {
        super("Not enough stock: " + requestedTotal + " requested in total, " + availableStock + " available");
        this.availableStock = availableStock;
        this.requestedTotal = requestedTotal;
    }

    public int availableStock() { return availableStock; }
    public int requestedTotal() { return requestedTotal; }
}
