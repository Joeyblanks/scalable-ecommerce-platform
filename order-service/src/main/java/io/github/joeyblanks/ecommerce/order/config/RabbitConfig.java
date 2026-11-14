package io.github.joeyblanks.ecommerce.order.config;

import io.github.joeyblanks.ecommerce.order.outbox.EventTypes;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topology (docs/events/README.md): one durable topic exchange for all domain events; each consumer
 * owns its queue and a dead-letter queue. Declaring is idempotent, so every service declares what it uses.
 */
@Configuration
public class RabbitConfig {

    public static final String PAYMENT_EVENTS_QUEUE = "order-service.payment-events";
    public static final String DEAD_LETTER_EXCHANGE = "ecommerce.events.dlx";
    public static final String PAYMENT_EVENTS_DLQ = PAYMENT_EVENTS_QUEUE + ".dlq";

    @Bean
    public Declarables topology(AppProperties.Events events) {
        TopicExchange exchange = new TopicExchange(events.exchange(), true, false);
        DirectExchange dlx = new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
        Queue paymentEvents = QueueBuilder.durable(PAYMENT_EVENTS_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(PAYMENT_EVENTS_DLQ)
                .build();
        Queue dlq = QueueBuilder.durable(PAYMENT_EVENTS_DLQ).build();
        Binding succeeded = BindingBuilder.bind(paymentEvents).to(exchange).with(EventTypes.RK_PAYMENT_SUCCEEDED);
        Binding failed = BindingBuilder.bind(paymentEvents).to(exchange).with(EventTypes.RK_PAYMENT_FAILED);
        Binding deadLetters = BindingBuilder.bind(dlq).to(dlx).with(PAYMENT_EVENTS_DLQ);
        return new Declarables(exchange, dlx, paymentEvents, dlq, succeeded, failed, deadLetters);
    }
}
