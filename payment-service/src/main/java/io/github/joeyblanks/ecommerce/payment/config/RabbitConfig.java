package io.github.joeyblanks.ecommerce.payment.config;

import io.github.joeyblanks.ecommerce.payment.outbox.EventTypes;
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
 * Topology (docs/events/README.md): the shared topic exchange; this service owns one queue for the order
 * events it consumes, plus its dead-letter queue. Declaring is idempotent, so every service declares what it uses.
 */
@Configuration
public class RabbitConfig {

    public static final String ORDER_EVENTS_QUEUE = "payment-service.order-events";
    public static final String DEAD_LETTER_EXCHANGE = "ecommerce.events.dlx";
    public static final String ORDER_EVENTS_DLQ = ORDER_EVENTS_QUEUE + ".dlq";

    @Bean
    public Declarables topology(AppProperties.Events events) {
        TopicExchange exchange = new TopicExchange(events.exchange(), true, false);
        DirectExchange dlx = new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
        Queue orderEvents = QueueBuilder.durable(ORDER_EVENTS_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(ORDER_EVENTS_DLQ)
                .build();
        Queue dlq = QueueBuilder.durable(ORDER_EVENTS_DLQ).build();
        Binding placed = BindingBuilder.bind(orderEvents).to(exchange).with(EventTypes.RK_ORDER_PLACED);
        Binding cancelled = BindingBuilder.bind(orderEvents).to(exchange).with(EventTypes.RK_ORDER_CANCELLED);
        Binding deadLetters = BindingBuilder.bind(dlq).to(dlx).with(ORDER_EVENTS_DLQ);
        return new Declarables(exchange, dlx, orderEvents, dlq, placed, cancelled, deadLetters);
    }
}
