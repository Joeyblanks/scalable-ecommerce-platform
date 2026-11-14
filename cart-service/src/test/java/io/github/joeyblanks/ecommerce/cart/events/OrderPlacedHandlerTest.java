package io.github.joeyblanks.ecommerce.cart.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.joeyblanks.ecommerce.cart.model.CartItem;
import io.github.joeyblanks.ecommerce.cart.repository.CartRepository;
import io.github.joeyblanks.ecommerce.cart.repository.CartRepository.LineChange;
import io.github.joeyblanks.ecommerce.cart.repository.CartRepository.OrderApplyResult;
import io.github.joeyblanks.ecommerce.cart.repository.CartRepository.Stored;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class OrderPlacedHandlerTest {

    @Mock CartRepository carts;
    final JsonMapper json = JsonMapper.builder().build();
    final UUID user = UUID.randomUUID();
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();

    private OrderPlacedHandler handler() {
        return new OrderPlacedHandler(json, carts);
    }

    private Stored line(UUID productId, int quantity) {
        CartItem item = new CartItem(productId, "SKU", "Name", new BigDecimal("10.00"), quantity, Instant.EPOCH, Instant.EPOCH);
        return new Stored(item, "raw-" + productId + "-" + quantity);
    }

    private OrderPlacedHandler.OrderPlaced event(Map<UUID, Integer> quantities) {
        return new OrderPlacedHandler.OrderPlaced(UUID.randomUUID(), UUID.randomUUID(), user, quantities);
    }

    @SuppressWarnings("unchecked")
    private List<LineChange> appliedChanges() {
        ArgumentCaptor<List<LineChange>> captor = ArgumentCaptor.forClass(List.class);
        verify(carts, org.mockito.Mockito.atLeastOnce()).applyOrderedLines(eq(user), any(), captor.capture());
        return captor.getValue();
    }

    @Test
    void subtractsTheOrderedQuantityAndRemovesFullyOrderedLines() {
        when(carts.peek(user, a)).thenReturn(Optional.of(line(a, 5)));
        when(carts.peek(user, b)).thenReturn(Optional.of(line(b, 2)));
        when(carts.applyOrderedLines(eq(user), any(), any())).thenReturn(OrderApplyResult.APPLIED);

        handler().handle(event(new java.util.LinkedHashMap<>(Map.of(a, 2, b, 2))));

        List<LineChange> changes = appliedChanges();
        assertThat(changes).hasSize(2);
        LineChange ca = changes.stream().filter(c -> c.productId().equals(a)).findFirst().orElseThrow();
        LineChange cb = changes.stream().filter(c -> c.productId().equals(b)).findFirst().orElseThrow();
        assertThat(ca.replacement().quantity()).isEqualTo(3);
        assertThat(ca.replacement().unitPrice()).isEqualByComparingTo("10.00"); // snapshot untouched
        assertThat(cb.replacement()).as("ordered everything: line removed").isNull();
    }

    @Test
    void aLineReducedBelowTheOrderedQuantityIsRemovedNeverNegative() {
        when(carts.peek(user, a)).thenReturn(Optional.of(line(a, 1)));
        when(carts.applyOrderedLines(eq(user), any(), any())).thenReturn(OrderApplyResult.APPLIED);

        handler().handle(event(Map.of(a, 3)));

        assertThat(appliedChanges().get(0).replacement()).isNull();
    }

    @Test
    void linesNoLongerInTheCartNeedNoChangeButTheEventIsStillRecorded() {
        when(carts.peek(user, a)).thenReturn(Optional.empty());
        when(carts.applyOrderedLines(eq(user), any(), any())).thenReturn(OrderApplyResult.APPLIED);

        handler().handle(event(Map.of(a, 1)));

        assertThat(appliedChanges()).isEmpty();
    }

    @Test
    void aConcurrentCustomerChangeIsRetriedFromAFreshRead() {
        when(carts.peek(user, a)).thenReturn(Optional.of(line(a, 5)), Optional.of(line(a, 6)));
        when(carts.applyOrderedLines(eq(user), any(), any()))
                .thenReturn(OrderApplyResult.CHANGED_CONCURRENTLY, OrderApplyResult.APPLIED);

        handler().handle(event(Map.of(a, 2)));

        assertThat(appliedChanges().get(0).replacement().quantity()).as("6 - 2, from the fresh read").isEqualTo(4);
        verify(carts, times(2)).applyOrderedLines(eq(user), any(), any());
    }

    @Test
    void givesUpAfterTenAttemptsSoTheListenerCanRetryAndDeadLetter() {
        when(carts.peek(user, a)).thenReturn(Optional.of(line(a, 5)));
        when(carts.applyOrderedLines(eq(user), any(), any())).thenReturn(OrderApplyResult.CHANGED_CONCURRENTLY);

        assertThatThrownBy(() -> handler().handle(event(Map.of(a, 1)))).isInstanceOf(IllegalStateException.class);
        verify(carts, times(10)).applyOrderedLines(eq(user), any(), any());
    }

    @Test
    void parsesTheContractAndMergesRepeatedProducts() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String body = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"OrderPlaced\",\"eventVersion\":1,\"producer\":\"order-service\","
                + "\"data\":{\"orderId\":\"" + orderId + "\",\"userId\":\"" + user + "\",\"totalAmount\":\"30.00\",\"items\":["
                + "{\"productId\":\"" + a + "\",\"sku\":\"A\",\"name\":\"A\",\"unitPrice\":\"10.00\",\"quantity\":2,\"lineTotal\":\"20.00\"},"
                + "{\"productId\":\"" + a + "\",\"sku\":\"A\",\"name\":\"A\",\"unitPrice\":\"10.00\",\"quantity\":1,\"lineTotal\":\"10.00\"}],"
                + "\"futureField\":\"ignored\"}}";

        var event = handler().parse(body.getBytes(StandardCharsets.UTF_8));

        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.orderId()).isEqualTo(orderId);
        assertThat(event.userId()).isEqualTo(user);
        assertThat(event.orderedQuantities()).containsExactly(Map.entry(a, 3));
    }

    @Test
    void malformedOrUnexpectedEventsAreRejectedWithoutRequeue() {
        String ok = "\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"OrderPlaced\"";
        String[] bad = {
                "not json",
                "{" + ok + "}",
                "{" + ok + ",\"data\":{\"orderId\":\"" + UUID.randomUUID() + "\",\"userId\":\"" + user + "\",\"items\":[]}}",
                "{" + ok + ",\"data\":{\"orderId\":\"x\",\"userId\":\"" + user + "\",\"items\":[{\"productId\":\"" + a + "\",\"quantity\":1}]}}",
                "{" + ok + ",\"data\":{\"orderId\":\"" + UUID.randomUUID() + "\",\"userId\":\"" + user + "\",\"items\":[{\"productId\":\"" + a + "\",\"quantity\":0}]}}",
                "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"OrderPaid\",\"data\":{}}"};
        for (String body : bad) {
            assertThatThrownBy(() -> handler().parse(body.getBytes(StandardCharsets.UTF_8))).as(body)
                    .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        }
    }
}
