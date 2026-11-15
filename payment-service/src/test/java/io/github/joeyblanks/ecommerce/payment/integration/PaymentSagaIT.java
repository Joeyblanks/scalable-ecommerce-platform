package io.github.joeyblanks.ecommerce.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.joeyblanks.ecommerce.payment.config.RabbitConfig;
import io.github.joeyblanks.ecommerce.payment.support.ControlledProvider;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The payment flow with the background workers and the outbox relay running, the way it runs for real:
 * events in over RabbitMQ, payment events out over RabbitMQ.
 */
@TestPropertySource(properties = {
        "app.jobs.outbox-relay-interval=100ms", "app.jobs.charge-worker-interval=100ms",
        "app.jobs.refund-worker-interval=100ms", "app.payments.retry-initial-delay=50ms",
        "app.payments.retry-max-delay=200ms"})
class PaymentSagaIT extends AbstractPaymentIT {

    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void captureQueue() {
        Queue capture = new Queue(CAPTURE, true, false, false); // RabbitMQ 4 has no transient shared queues
        admin.declareQueue(capture);
        admin.declareBinding(BindingBuilder.bind(capture).to(new TopicExchange(EXCHANGE)).with("payment.#"));
        admin.purgeQueue(CAPTURE, false);
    }

    private JsonNode nextPublished() throws Exception {
        Message m = rabbit.receive(CAPTURE, 10_000);
        assertThat(m).as("an event on the exchange").isNotNull();
        return json.readTree(m.getBody());
    }

    @Test
    void anOrderIsChargedOnceAndPaymentSucceededIsPublished() throws Exception {
        UUID order = UUID.randomUUID();
        sendPlaced(order, user, "59.80");

        awaitStatus(order, "SUCCEEDED");
        JsonNode event = nextPublished();
        assertThat(event.path("eventType").asString()).isEqualTo("PaymentSucceeded");
        assertThat(event.path("producer").asString()).isEqualTo("payment-service");
        assertThat(event.path("eventVersion").asInt()).isEqualTo(1);
        assertThat(event.path("data").path("orderId").asString()).isEqualTo(order.toString());
        assertThat(event.path("data").path("amount").asString()).isEqualTo("59.80");
        assertThat(event.path("data").path("paymentId").asString())
                .isEqualTo(jdbc.queryForObject("SELECT id::text FROM payments WHERE order_id = ?", String.class, order));
        assertThat(provider.chargeCalls.get()).isEqualTo(1);
        assertThat(ledgerCharges()).isEqualTo(1);
        // the outbox row is marked published once RabbitMQ confirmed it
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE published_at IS NOT NULL", Integer.class) == 1);
    }

    @Test
    void aDeclinedCardPublishesPaymentFailedWithAReason() throws Exception {
        UUID order = UUID.randomUUID();
        sendPlaced(order, user, "20.13");

        awaitStatus(order, "FAILED");
        JsonNode event = nextPublished();
        assertThat(event.path("eventType").asString()).isEqualTo("PaymentFailed");
        assertThat(event.path("data").path("reason").asString()).isEqualTo("CARD_DECLINED");
        assertThat(event.path("data").path("orderId").asString()).isEqualTo(order.toString());
        assertThat(ledgerCharges()).isZero();
        assertThat(outboxCount("PaymentSucceeded")).isZero();
    }

    @Test
    void insufficientFundsIsAlsoADecline() {
        UUID order = UUID.randomUUID();
        sendPlaced(order, user, "20.14");
        awaitStatus(order, "FAILED");
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM payments WHERE order_id = ?", String.class, order))
                .isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void transientProviderErrorsAreRetriedWithTheSameKeyAndChargeOnce() {
        UUID order = UUID.randomUUID();
        sendPlaced(order, user, "20.15"); // simulated provider: two errors, then success

        awaitStatus(order, "SUCCEEDED");
        assertThat(provider.chargeCalls.get()).isEqualTo(3);
        assertThat(ledgerCharges()).isEqualTo(1);
        assertThat(outboxCount("PaymentSucceeded")).isEqualTo(1);
        assertThat(outboxCount("PaymentFailed")).isZero(); // an unknown outcome is never reported as a failure
    }

    @Test
    void aChargeWhoseResponseWasLostIsNotChargedAgain() {
        provider.loseNextChargeResponse = true; // the provider takes the money, then we never hear back
        UUID order = UUID.randomUUID();
        sendPlaced(order, user, "30.00");

        awaitStatus(order, "SUCCEEDED");
        assertThat(provider.chargeCalls.get()).isEqualTo(2); // the retry happened...
        assertThat(ledgerCharges()).isEqualTo(1);              // ...but money moved once (same idempotency key)
        assertThat(outboxCount("PaymentSucceeded")).isEqualTo(1);
    }

    @Test
    void duplicateAndRepeatedOrderPlacedEventsCreateOnePayment() {
        UUID order = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            send("order.placed", placedJson(eventId, order, user, "40.00"));   // same event redelivered
            send("order.placed", placedJson(UUID.randomUUID(), order, user, "40.00")); // same order, new event id
        }
        awaitStatus(order, "SUCCEEDED");
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10)).until(() ->
                jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class) >= 6);

        assertThat(paymentRows()).isEqualTo(1);
        assertThat(provider.chargeCalls.get()).isEqualTo(1);
        assertThat(ledgerCharges()).isEqualTo(1);
        assertThat(outboxCount("PaymentSucceeded")).isEqualTo(1);
    }

    @Test
    void cancellingAPaidOrderRefundsItOnce() {
        UUID order = UUID.randomUUID();
        sendPlaced(order, user, "50.00");
        awaitStatus(order, "SUCCEEDED");

        UUID cancelEvent = UUID.randomUUID();
        send("order.cancelled", cancelledJson(cancelEvent, order, user));
        send("order.cancelled", cancelledJson(cancelEvent, order, user));       // redelivery
        send("order.cancelled", cancelledJson(UUID.randomUUID(), order, user)); // a second event, same meaning
        awaitStatus(order, "REFUNDED");

        assertThat(provider.refundCalls.get()).isEqualTo(1);
        assertThat(ledgerRefunds()).isEqualTo(1);
        assertThat(outboxCount("PaymentSucceeded")).isEqualTo(1); // announced before the cancellation; no new events
        assertThat(outboxCount("PaymentFailed")).isZero();
    }

    @Test
    void aRefundWhoseResponseWasLostIsRetriedAndRefundsOnce() {
        UUID order = UUID.randomUUID();
        sendPlaced(order, user, "50.00");
        awaitStatus(order, "SUCCEEDED");
        provider.loseNextRefundResponse = true;

        sendCancelled(order, user);
        awaitStatus(order, "REFUNDED");

        assertThat(provider.refundCalls.get()).isEqualTo(2);
        assertThat(ledgerRefunds()).isEqualTo(1);
    }

    @Test
    void cancellationBeforeOrderPlacedMeansNeverCharging() {
        UUID order = UUID.randomUUID();
        sendCancelled(order, user);
        awaitStatus(order, "CANCELLED"); // the tombstone

        sendPlaced(order, user, "25.00");
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM processed_events", Integer.class) == 2);
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).until(() -> "CANCELLED".equals(statusOf(order)));

        assertThat(provider.chargeCalls.get()).isZero();
        assertThat(paymentRows()).isEqualTo(1);
        assertThat(outboxCount("PaymentSucceeded") + outboxCount("PaymentFailed")).isZero();
    }

    @Test
    void cancellationDuringTheChargeRefundsWithoutAnnouncingSuccess() throws Exception {
        ControlledProvider.Gate gate = new ControlledProvider.Gate();
        provider.chargeGate = gate;
        UUID order = UUID.randomUUID();
        sendPlaced(order, user, "60.00");
        assertThat(gate.entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); // the charge is in flight

        sendCancelled(order, user);
        await().atMost(Duration.ofSeconds(10)).until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT cancel_requested FROM payments WHERE order_id = ?", Boolean.class, order)));
        gate.release.countDown(); // the charge now completes (money taken)

        awaitStatus(order, "REFUNDED");
        assertThat(ledgerCharges()).isEqualTo(1);
        assertThat(ledgerRefunds()).isEqualTo(1);
        assertThat(outboxCount("PaymentSucceeded")).isZero(); // order-service never hears "paid" for a cancelled order
        assertThat(outboxCount("PaymentFailed")).isZero();
    }

    @Test
    void cancellationDuringAChargeThatIsDeclinedPublishesNothing() throws Exception {
        ControlledProvider.Gate gate = new ControlledProvider.Gate();
        provider.chargeGate = gate;
        UUID order = UUID.randomUUID();
        sendPlaced(order, user, "60.13"); // will be declined
        assertThat(gate.entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        sendCancelled(order, user);
        await().atMost(Duration.ofSeconds(10)).until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT cancel_requested FROM payments WHERE order_id = ?", Boolean.class, order)));
        gate.release.countDown();

        awaitStatus(order, "CANCELLED");
        assertThat(outboxCount("PaymentFailed") + outboxCount("PaymentSucceeded")).isZero();
        assertThat(ledgerCharges()).isZero();
    }

    @Test
    void zeroTotalOrdersSucceedWithoutTouchingTheProvider() {
        UUID order = UUID.randomUUID();
        sendPlaced(order, user, "0.00");
        awaitStatus(order, "SUCCEEDED");
        assertThat(provider.chargeCalls.get()).isZero();
        assertThat(outboxCount("PaymentSucceeded")).isEqualTo(1);

        sendCancelled(order, user);
        awaitStatus(order, "REFUNDED");
        assertThat(provider.refundCalls.get()).isZero(); // nothing was charged, nothing to refund
    }

    @Test
    void poisonMessagesAreDeadLetteredNotLoopedForever() {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send("", RabbitConfig.ORDER_EVENTS_QUEUE, new Message("{ not json".getBytes(), props));
        send("order.placed", placedJson(UUID.randomUUID(), UUID.randomUUID(), user, "-5.00")); // invalid amount

        List<String> dead = new java.util.ArrayList<>();
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            Message m = rabbit.receive(RabbitConfig.ORDER_EVENTS_DLQ, 500);
            if (m != null) {
                dead.add(new String(m.getBody()));
            }
            return dead.size() == 2;
        });
        assertThat(paymentRows()).isZero();
    }

    @Test
    void customersSeeTheStatusOfTheirOwnOrdersOnly() throws Exception {
        UUID order = UUID.randomUUID();
        sendPlaced(order, user, "59.80");
        awaitStatus(order, "SUCCEEDED");
        String owner = "Bearer " + JWKS.userToken(user.toString(), "CUSTOMER");
        String stranger = "Bearer " + JWKS.userToken(UUID.randomUUID().toString(), "CUSTOMER");
        String service = "Bearer " + JWKS.serviceToken("order-service", "payment-service", List.of("x"));

        mvc.perform(get("/api/payments/orders/" + order).header("Authorization", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.amount").value("59.80"))
                .andExpect(jsonPath("$.providerRef").doesNotExist());
        mvc.perform(get("/api/payments/orders/" + order).header("Authorization", stranger)).andExpect(status().isNotFound());
        mvc.perform(get("/api/payments/orders/" + UUID.randomUUID()).header("Authorization", owner)).andExpect(status().isNotFound());
        mvc.perform(get("/api/payments/orders/" + order).header("Authorization", service)).andExpect(status().isForbidden());
        mvc.perform(get("/api/payments/orders/" + order)).andExpect(status().isUnauthorized());
    }

    @Test
    void cancelledAndFailedPaymentsAreVisibleToTheirOwnerAndTombstonesAreNot() throws Exception {
        UUID declined = UUID.randomUUID();
        sendPlaced(declined, user, "20.13");
        awaitStatus(declined, "FAILED");
        UUID tombstoneOnly = UUID.randomUUID();
        sendCancelled(tombstoneOnly, user);
        awaitStatus(tombstoneOnly, "CANCELLED");
        String owner = "Bearer " + JWKS.userToken(user.toString(), "CUSTOMER");

        mvc.perform(get("/api/payments/orders/" + declined).header("Authorization", owner))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("CARD_DECLINED"));
        mvc.perform(get("/api/payments/orders/" + tombstoneOnly).header("Authorization", owner)).andExpect(status().isNotFound());

        // once OrderPlaced arrives, the cancelled order becomes visible as CANCELLED, still never charged
        sendPlaced(tombstoneOnly, user, "10.00");
        await().atMost(Duration.ofSeconds(10)).until(() -> mvc.perform(get("/api/payments/orders/" + tombstoneOnly)
                .header("Authorization", owner)).andReturn().getResponse().getStatus() == 200);
        mvc.perform(get("/api/payments/orders/" + tombstoneOnly).header("Authorization", owner))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}
