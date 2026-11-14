package io.github.joeyblanks.ecommerce.order.payment;

import io.github.joeyblanks.ecommerce.order.config.RabbitConfig;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventListener {

    private final PaymentEventHandler handler;

    public PaymentEventListener(PaymentEventHandler handler) {
        this.handler = handler;
    }

    /** Ack after the transaction commits; an exception triggers the retry policy, then the dead-letter queue. */
    @RabbitListener(queues = RabbitConfig.PAYMENT_EVENTS_QUEUE)
    public void onMessage(Message message) {
        handler.handle(handler.parse(message.getBody()));
    }
}
