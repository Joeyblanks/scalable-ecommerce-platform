package io.github.joeyblanks.ecommerce.cart.catalog;

/** The catalog could not give a trustworthy answer (down, too slow, erroring, or garbled). */
public class CatalogUnavailableException extends RuntimeException {
    public CatalogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
