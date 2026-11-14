package io.github.joeyblanks.ecommerce.order.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.joeyblanks.ecommerce.order.outbox.OutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Side effects of failing an attempt happen only if the attempt really was failed by this call. */
@ExtendWith(MockitoExtension.class)
class OrderTransactionsTest {

    @Mock OrderRepository orders;
    @Mock OutboxRepository outbox;
    @Mock StockReleaseRepository releases;
    final Clock clock = Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC);
    final UUID orderId = UUID.randomUUID();

    private OrderTransactions tx() {
        return new OrderTransactions(orders, outbox, releases, clock);
    }

    @Test
    void failingAStillReservingAttemptQueuesItsRelease() {
        when(orders.markFailed(eq(orderId), eq("CATALOG_UNAVAILABLE"), any())).thenReturn(1);

        assertThat(tx().failAttempt(orderId, "CATALOG_UNAVAILABLE", true)).isTrue();

        verify(releases).enqueue(orderId, clock.instant());
    }

    @Test
    void anAttemptThatIsNoLongerReservingIsLeftAlone_noReleaseForALiveOrder() {
        when(orders.markFailed(eq(orderId), any(), any())).thenReturn(0); // TX2 already made it PENDING

        assertThat(tx().failAttempt(orderId, "STUCK", true)).isFalse();

        verify(releases, never()).enqueue(any(), any());
    }

    @Test
    void outOfStockFailsWithoutARelease() {
        when(orders.markFailed(eq(orderId), any(), any())).thenReturn(1);

        assertThat(tx().failAttempt(orderId, "OUT_OF_STOCK", false)).isTrue();

        verify(releases, never()).enqueue(any(), any());
    }
}
