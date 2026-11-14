package io.github.joeyblanks.ecommerce.order.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.joeyblanks.ecommerce.order.client.CartClient;
import io.github.joeyblanks.ecommerce.order.client.CatalogClient;
import io.github.joeyblanks.ecommerce.order.client.DependencyUnavailableException;
import io.github.joeyblanks.ecommerce.order.client.OutOfStockException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;

/** Every branch of the placement orchestration, including the races that are hard to stage for real. */
@ExtendWith(MockitoExtension.class)
class OrderPlacementServiceTest {

    @Mock OrderRepository orders;
    @Mock OrderTransactions tx;
    @Mock CartClient cart;
    @Mock CatalogClient catalog;

    final UUID user = UUID.randomUUID();
    final UUID product = UUID.randomUUID();

    private OrderPlacementService service() {
        return new OrderPlacementService(orders, tx, cart, catalog);
    }

    private CartClient.Cart cartWithOneLine() {
        return new CartClient.Cart(List.of(new CartClient.Item(product, "BOOK", "Book", new BigDecimal("10.00"), 2)));
    }

    private CatalogClient.Reservation reservation() {
        return new CatalogClient.Reservation(List.of(new CatalogClient.Item(product, "BOOK", "Book",
                new BigDecimal("10.00"), 2, new BigDecimal("20.00"))));
    }

    private Order order(OrderStatus status) {
        return new Order(UUID.randomUUID(), user, status, null, null, new BigDecimal("20.00"), null,
                Instant.EPOCH, Instant.EPOCH, null, null, List.of());
    }

    @Test
    void aRepeatedKeyReturnsTheExistingOrderWithoutCallingAnything() {
        Order existing = order(OrderStatus.PENDING);
        when(orders.findByUserAndKey(user, "k")).thenReturn(Optional.of(existing));

        var placement = service().place(user, "tok", "k");

        assertThat(placement.created()).isFalse();
        assertThat(placement.order()).isEqualTo(existing);
        verifyNoInteractions(cart, catalog, tx);
    }

    @Test
    void aRepeatedKeyWhileTheFirstIsStillReservingIsInProgress() {
        when(orders.findByUserAndKey(user, "k")).thenReturn(Optional.of(order(OrderStatus.RESERVING)));

        assertThatThrownBy(() -> service().place(user, "tok", "k")).isInstanceOf(OrderInProgressException.class);
        verifyNoInteractions(cart, catalog);
    }

    @Test
    void anEmptyCartWritesNothing() {
        when(orders.findByUserAndKey(user, "k")).thenReturn(Optional.empty());
        when(cart.getCart("tok")).thenReturn(new CartClient.Cart(List.of()));

        assertThatThrownBy(() -> service().place(user, "tok", "k")).isInstanceOf(EmptyCartException.class);
        verifyNoInteractions(tx, catalog);
    }

    @Test
    void aCartOutageWritesNothing() {
        when(orders.findByUserAndKey(user, "k")).thenReturn(Optional.empty());
        when(cart.getCart("tok")).thenThrow(new DependencyUnavailableException("cart-service", "down", null));

        assertThatThrownBy(() -> service().place(user, "tok", "k")).isInstanceOf(DependencyUnavailableException.class);
        verifyNoInteractions(tx, catalog);
    }

    @Test
    void theSameKeyArrivingTwiceAtOnceReturnsTheWinnersOrder() {
        Order winner = order(OrderStatus.PENDING);
        when(orders.findByUserAndKey(user, "k")).thenReturn(Optional.empty(), Optional.of(winner));
        when(cart.getCart("tok")).thenReturn(cartWithOneLine());
        doThrow(new DuplicateKeyException("unique")).when(tx).createAttempt(any(), eq(user), eq("k"));

        var placement = service().place(user, "tok", "k");

        assertThat(placement.order()).isEqualTo(winner);
        verifyNoInteractions(catalog);
    }

    @Test
    void outOfStockFailsTheAttemptWithoutAReleaseBecauseNothingWasReserved() {
        when(orders.findByUserAndKey(user, "k")).thenReturn(Optional.empty());
        when(cart.getCart("tok")).thenReturn(cartWithOneLine());
        when(catalog.reserve(any(), any())).thenThrow(new OutOfStockException(List.of()));

        assertThatThrownBy(() -> service().place(user, "tok", "k")).isInstanceOf(OutOfStockException.class);
        verify(tx).failAttempt(any(), eq("OUT_OF_STOCK"), eq(false));
    }

    @Test
    void anUnknownReservationOutcomeFailsTheAttemptAndQueuesARelease() {
        when(orders.findByUserAndKey(user, "k")).thenReturn(Optional.empty());
        when(cart.getCart("tok")).thenReturn(cartWithOneLine());
        when(catalog.reserve(any(), any())).thenThrow(new DependencyUnavailableException("product-catalog-service", "timeout", null));

        assertThatThrownBy(() -> service().place(user, "tok", "k")).isInstanceOf(DependencyUnavailableException.class);
        verify(tx).failAttempt(any(), eq("CATALOG_UNAVAILABLE"), eq(true));
        verify(tx, never()).completePlacement(any(), any(), any());
    }

    @Test
    void anUnexpectedCatalogErrorIsTreatedAsUnknownToo() {
        when(orders.findByUserAndKey(user, "k")).thenReturn(Optional.empty());
        when(cart.getCart("tok")).thenReturn(cartWithOneLine());
        when(catalog.reserve(any(), any())).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> service().place(user, "tok", "k")).isInstanceOf(DependencyUnavailableException.class);
        verify(tx).failAttempt(any(), eq("CATALOG_UNAVAILABLE"), eq(true));
    }

    @Test
    void ifTheReconcilerAlreadyGaveUpOnTheAttemptThePlacementReportsNotCompleted() {
        when(orders.findByUserAndKey(user, "k")).thenReturn(Optional.empty());
        when(cart.getCart("tok")).thenReturn(cartWithOneLine());
        when(catalog.reserve(any(), any())).thenReturn(reservation());
        when(tx.completePlacement(any(), any(), any())).thenReturn(Optional.empty()); // conditional update hit 0 rows

        assertThatThrownBy(() -> service().place(user, "tok", "k")).isInstanceOf(OrderNotCompletedException.class);
    }

    @Test
    void aDatabaseFailureAfterReservingFailsTheAttemptAndQueuesARelease() {
        when(orders.findByUserAndKey(user, "k")).thenReturn(Optional.empty());
        when(cart.getCart("tok")).thenReturn(cartWithOneLine());
        when(catalog.reserve(any(), any())).thenReturn(reservation());
        when(tx.completePlacement(any(), any(), any())).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service().place(user, "tok", "k")).isInstanceOf(OrderNotCompletedException.class);
        verify(tx).failAttempt(any(), eq("SAVE_FAILED"), eq(true));
    }

    @Test
    void ifEvenRecordingTheFailureFailsTheErrorStillReachesTheCaller_theReconcilerCleansUp() {
        when(orders.findByUserAndKey(user, "k")).thenReturn(Optional.empty());
        when(cart.getCart("tok")).thenReturn(cartWithOneLine());
        when(catalog.reserve(any(), any())).thenReturn(reservation());
        when(tx.completePlacement(any(), any(), any())).thenThrow(new DataAccessResourceFailureException("db down"));
        doThrow(new DataAccessResourceFailureException("still down")).when(tx).failAttempt(any(), anyString(), anyBoolean());

        assertThatThrownBy(() -> service().place(user, "tok", "k")).isInstanceOf(OrderNotCompletedException.class);
    }

    @Test
    void theReservationUsesTheOrderIdAndTheCartQuantities() {
        when(orders.findByUserAndKey(user, "k")).thenReturn(Optional.empty());
        when(cart.getCart("tok")).thenReturn(cartWithOneLine());
        when(catalog.reserve(any(), any())).thenReturn(reservation());
        when(tx.completePlacement(any(), any(), any())).thenReturn(Optional.of(order(OrderStatus.PENDING)));
        var orderId = org.mockito.ArgumentCaptor.forClass(UUID.class);

        assertThat(service().place(user, "tok", "k").created()).isTrue();

        verify(tx).createAttempt(orderId.capture(), eq(user), eq("k"));
        verify(catalog).reserve(eq(orderId.getValue()), eq(List.of(new CatalogClient.Line(product, 2))));
    }
}
