package io.github.joeyblanks.ecommerce.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.joeyblanks.ecommerce.cart.catalog.CatalogClient;
import io.github.joeyblanks.ecommerce.cart.catalog.CatalogProduct;
import io.github.joeyblanks.ecommerce.cart.catalog.CatalogUnavailableException;
import io.github.joeyblanks.ecommerce.cart.catalog.ProductNotFoundException;
import io.github.joeyblanks.ecommerce.cart.config.CartProperties;
import io.github.joeyblanks.ecommerce.cart.model.CartItem;
import io.github.joeyblanks.ecommerce.cart.repository.CartRepository;
import io.github.joeyblanks.ecommerce.cart.repository.CartRepository.Stored;
import io.github.joeyblanks.ecommerce.cart.repository.CartRepository.WriteResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock CartRepository carts;
    @Mock CatalogClient catalog;
    final Instant now = Instant.parse("2026-02-01T10:00:00Z");
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    final CartProperties props = new CartProperties(Duration.ofDays(7), 50, 99, 3);
    final UUID user = UUID.randomUUID();
    final UUID productId = UUID.randomUUID();
    CartService service;

    @BeforeEach
    void setUp() {
        service = new CartService(carts, catalog, props, clock);
    }

    private CatalogProduct product(String price, int stock) {
        return new CatalogProduct(productId, "CC-1", "Clean Code", new BigDecimal(price), stock);
    }

    private Stored stored(int quantity, String price) {
        CartItem item = new CartItem(productId, "CC-1", "Clean Code", new BigDecimal(price), quantity,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"));
        return new Stored(item, "raw-" + quantity);
    }

    private CartItem written() {
        ArgumentCaptor<CartItem> captor = ArgumentCaptor.forClass(CartItem.class);
        verify(carts, org.mockito.Mockito.atLeastOnce()).writeIfUnchanged(eq(user), any(), captor.capture());
        return captor.getValue();
    }

    // ---- add ----

    @Test
    void addingANewProductStoresASnapshotOfTheCatalogData() {
        when(catalog.getProduct(productId)).thenReturn(product("29.9", 10));
        when(carts.find(user, productId)).thenReturn(Optional.empty());
        when(carts.writeIfUnchanged(eq(user), eq(null), any())).thenReturn(WriteResult.WRITTEN);

        service.addItem(user, productId, 2);

        CartItem item = written();
        assertThat(item.quantity()).isEqualTo(2);
        assertThat(item.sku()).isEqualTo("CC-1");
        assertThat(item.name()).isEqualTo("Clean Code");
        assertThat(item.unitPrice()).isEqualByComparingTo("29.90").hasScaleOf(2);
        assertThat(item.addedAt()).isEqualTo(now);
        assertThat(item.updatedAt()).isEqualTo(now);
    }

    @Test
    void addingAnExistingProductAddsToTheQuantityKeepsAddedAtAndRefreshesThePrice() {
        Stored current = stored(3, "20.00");
        when(catalog.getProduct(productId)).thenReturn(product("25.00", 10));
        when(carts.find(user, productId)).thenReturn(Optional.of(current));
        when(carts.writeIfUnchanged(eq(user), eq(current), any())).thenReturn(WriteResult.WRITTEN);

        service.addItem(user, productId, 4);

        CartItem item = written();
        assertThat(item.quantity()).isEqualTo(7);
        assertThat(item.unitPrice()).isEqualByComparingTo("25.00"); // refreshed from the catalog
        assertThat(item.addedAt()).isEqualTo(current.item().addedAt());
        assertThat(item.updatedAt()).isEqualTo(now);
    }

    @Test
    void stockCheckCountsWhatIsAlreadyInTheCart() {
        when(catalog.getProduct(productId)).thenReturn(product("1.00", 4));
        when(carts.find(user, productId)).thenReturn(Optional.of(stored(3, "1.00")));

        assertThatThrownBy(() -> service.addItem(user, productId, 2))
                .isInstanceOfSatisfying(InsufficientStockException.class, e -> {
                    assertThat(e.availableStock()).isEqualTo(4);
                    assertThat(e.requestedTotal()).isEqualTo(5);
                });
        verify(carts, never()).writeIfUnchanged(any(), any(), any());
    }

    @Test
    void exactlyTheAvailableStockIsAllowed() {
        when(catalog.getProduct(productId)).thenReturn(product("1.00", 5));
        when(carts.find(user, productId)).thenReturn(Optional.of(stored(3, "1.00")));
        when(carts.writeIfUnchanged(eq(user), any(), any())).thenReturn(WriteResult.WRITTEN);

        service.addItem(user, productId, 2);

        assertThat(written().quantity()).isEqualTo(5);
    }

    @Test
    void outOfStockProductCannotBeAdded() {
        when(catalog.getProduct(productId)).thenReturn(product("1.00", 0));
        when(carts.find(user, productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addItem(user, productId, 1)).isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void unknownProductIsRejectedAndNothingIsWritten() {
        when(catalog.getProduct(productId)).thenThrow(new ProductNotFoundException(productId));

        assertThatThrownBy(() -> service.addItem(user, productId, 1)).isInstanceOf(ProductNotFoundException.class);
        verify(carts, never()).writeIfUnchanged(any(), any(), any());
    }

    @Test
    void catalogOutageFailsTheAddAndLeavesTheCartUntouched() {
        when(catalog.getProduct(productId)).thenThrow(new CatalogUnavailableException("down", null));

        assertThatThrownBy(() -> service.addItem(user, productId, 1)).isInstanceOf(CatalogUnavailableException.class);
        verify(carts, never()).writeIfUnchanged(any(), any(), any());
    }

    @Test
    void quantityLimitsAreEnforced() {
        assertThatThrownBy(() -> service.addItem(user, productId, 0)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.addItem(user, productId, 100)).isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(catalog);

        when(catalog.getProduct(productId)).thenReturn(product("1.00", 1000));
        when(carts.find(user, productId)).thenReturn(Optional.of(stored(98, "1.00")));
        assertThatThrownBy(() -> service.addItem(user, productId, 2)).isInstanceOf(CartLimitException.class)
                .hasMessageContaining("99");
    }

    @Test
    void fullCartRejectsANewLine() {
        when(catalog.getProduct(productId)).thenReturn(product("1.00", 10));
        when(carts.find(user, productId)).thenReturn(Optional.empty());
        when(carts.writeIfUnchanged(eq(user), eq(null), any())).thenReturn(WriteResult.CART_FULL);

        assertThatThrownBy(() -> service.addItem(user, productId, 1)).isInstanceOf(CartLimitException.class)
                .hasMessageContaining("50");
    }

    // ---- concurrency ----

    @Test
    void aConcurrentChangeIsRetriedFromAFreshReadAndTheCatalogIsAskedOnlyOnce() {
        Stored first = stored(1, "1.00");
        Stored second = stored(5, "1.00"); // someone else raised the line to 5 meanwhile
        when(catalog.getProduct(productId)).thenReturn(product("1.00", 100));
        when(carts.find(user, productId)).thenReturn(Optional.of(first), Optional.of(second));
        when(carts.writeIfUnchanged(eq(user), eq(first), any())).thenReturn(WriteResult.CHANGED_CONCURRENTLY);
        when(carts.writeIfUnchanged(eq(user), eq(second), any())).thenReturn(WriteResult.WRITTEN);

        service.addItem(user, productId, 2);

        assertThat(written().quantity()).isEqualTo(7); // 5 + 2, not the stale 1 + 2 = 3
        verify(catalog, times(1)).getProduct(productId);
    }

    @Test
    void givesUpWithABusyErrorWhenTheLineKeepsChanging() {
        when(catalog.getProduct(productId)).thenReturn(product("1.00", 100));
        when(carts.find(user, productId)).thenReturn(Optional.empty());
        when(carts.writeIfUnchanged(eq(user), any(), any())).thenReturn(WriteResult.CHANGED_CONCURRENTLY);

        assertThatThrownBy(() -> service.addItem(user, productId, 1)).isInstanceOf(CartBusyException.class);
        verify(carts, times(3)).writeIfUnchanged(any(), any(), any()); // casMaxAttempts = 3
    }

    // ---- set quantity ----

    @Test
    void settingTheQuantityReplacesItAndRefreshesTheSnapshot() {
        Stored current = stored(3, "20.00");
        when(carts.find(user, productId)).thenReturn(Optional.of(current));
        when(catalog.getProduct(productId)).thenReturn(product("22.50", 10));
        when(carts.writeIfUnchanged(eq(user), eq(current), any())).thenReturn(WriteResult.WRITTEN);

        service.setQuantity(user, productId, 8);

        CartItem item = written();
        assertThat(item.quantity()).isEqualTo(8);
        assertThat(item.unitPrice()).isEqualByComparingTo("22.50");
    }

    @Test
    void settingTheQuantityOfAMissingLineIsNotFoundWithoutCallingTheCatalog() {
        when(carts.find(user, productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setQuantity(user, productId, 2)).isInstanceOf(ItemNotInCartException.class);
        verifyNoInteractions(catalog);
    }

    @Test
    void settingMoreThanTheStockIsRejected() {
        when(carts.find(user, productId)).thenReturn(Optional.of(stored(1, "1.00")));
        when(catalog.getProduct(productId)).thenReturn(product("1.00", 3));

        assertThatThrownBy(() -> service.setQuantity(user, productId, 4)).isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void lineRemovedWhileAskingTheCatalogIsNotFound() {
        when(carts.find(user, productId)).thenReturn(Optional.of(stored(1, "1.00")), Optional.empty());
        when(catalog.getProduct(productId)).thenReturn(product("1.00", 10));

        assertThatThrownBy(() -> service.setQuantity(user, productId, 2)).isInstanceOf(ItemNotInCartException.class);
    }

    // ---- remove / clear / view ----

    @Test
    void removeMissingLineIsNotFoundAndNeverTouchesTheCatalog() {
        when(carts.remove(user, productId)).thenReturn(false);

        assertThatThrownBy(() -> service.removeItem(user, productId)).isInstanceOf(ItemNotInCartException.class);
        verifyNoInteractions(catalog);
    }

    @Test
    void removeReturnsTheRemainingCart() {
        when(carts.remove(user, productId)).thenReturn(true);
        when(carts.findAll(user)).thenReturn(List.of());

        assertThat(service.removeItem(user, productId).items()).isEmpty();
    }

    @Test
    void clearDeletesTheCart() {
        service.clear(user);

        verify(carts).clear(user);
    }

    @Test
    void viewComputesTotalsFromTheStoredSnapshotsWithoutCallingTheCatalog() {
        CartItem a = new CartItem(UUID.randomUUID(), "A", "A", new BigDecimal("10.00"), 2, now, now);
        CartItem b = new CartItem(UUID.randomUUID(), "B", "B", new BigDecimal("0.99"), 3, now.plusSeconds(1), now);
        when(carts.findAll(user)).thenReturn(List.of(b, a));

        var cart = service.view(user);

        assertThat(cart.itemCount()).isEqualTo(2);
        assertThat(cart.totalQuantity()).isEqualTo(5);
        assertThat(cart.total()).isEqualByComparingTo("22.97");
        assertThat(cart.items()).extracting("sku").containsExactly("A", "B"); // oldest line first
        verifyNoInteractions(catalog);
    }

    @Test
    void emptyCartHasZeroTotals() {
        when(carts.findAll(user)).thenReturn(List.of());

        var cart = service.view(user);

        assertThat(cart.items()).isEmpty();
        assertThat(cart.total()).isEqualByComparingTo("0.00");
    }
}
