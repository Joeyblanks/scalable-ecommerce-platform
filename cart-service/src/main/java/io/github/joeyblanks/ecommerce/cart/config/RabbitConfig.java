package io.github.joeyblanks.ecommerce.cart.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Topology from docs/events: cart-service owns its queue for OrderPlaced and its dead-letter queue. */
@Configuration
@EnableConfigurationProperties(EventProperties.class)
public class RabbitConfig {

    public static final String ORDER_PLACED_QUEUE = "cart-service.order-placed";
    public static final String ORDER_PLACED_DLQ = ORDER_PLACED_QUEUE + ".dlq";
    public static final String DEAD_LETTER_EXCHANGE = "ecommerce.events.dlx";
    public static final String ORDER_PLACED_ROUTING_KEY = "order.placed";

    @Bean
    public Declarables topology(EventProperties events) {
        // Declared exactly like order-service declares them; declarations are idempotent.
        TopicExchange exchange = new TopicExchange(events.exchange(), true, false);
        DirectExchange dlx = new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
        Queue queue = QueueBuilder.durable(ORDER_PLACED_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(ORDER_PLACED_DLQ)
                .build();
        Queue dlq = QueueBuilder.durable(ORDER_PLACED_DLQ).build();
        Binding placed = BindingBuilder.bind(queue).to(exchange).with(ORDER_PLACED_ROUTING_KEY);
        Binding deadLetters = BindingBuilder.bind(dlq).to(dlx).with(ORDER_PLACED_DLQ);
        return new Declarables(exchange, dlx, queue, dlq, placed, deadLetters);
    }
}
