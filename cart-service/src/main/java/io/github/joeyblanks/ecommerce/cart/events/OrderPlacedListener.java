package io.github.joeyblanks.ecommerce.cart.events;

import io.github.joeyblanks.ecommerce.cart.config.RabbitConfig;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class OrderPlacedListener {

    private final OrderPlacedHandler handler;

    public OrderPlacedListener(OrderPlacedHandler handler) {
        this.handler = handler;
    }

    @RabbitListener(queues = RabbitConfig.ORDER_PLACED_QUEUE)
    public void onMessage(Message message) {
        handler.handle(handler.parse(message.getBody()));
    }
}
