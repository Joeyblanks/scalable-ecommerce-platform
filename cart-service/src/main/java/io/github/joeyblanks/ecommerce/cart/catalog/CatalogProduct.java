package io.github.joeyblanks.ecommerce.cart.catalog;

import java.math.BigDecimal;
import java.util.UUID;

/** The parts of the catalog's product response this service needs (other fields are ignored). */
public record CatalogProduct(UUID id, String sku, String name, BigDecimal price, int stockQuantity) {
}
