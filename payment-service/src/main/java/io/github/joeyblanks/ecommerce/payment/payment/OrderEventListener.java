package io.github.joeyblanks.ecommerce.payment.payment;

import io.github.joeyblanks.ecommerce.payment.config.RabbitConfig;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class OrderEventListener {

    private final OrderEventHandler handler;

    public OrderEventListener(OrderEventHandler handler) {
        this.handler = handler;
    }

    /** Ack after the transaction commits; an exception triggers the retry policy, then the dead-letter queue. */
    @RabbitListener(queues = RabbitConfig.ORDER_EVENTS_QUEUE)
    public void onMessage(Message message) {
        handler.handle(handler.parse(message.getBody()));
    }
}
