package io.github.joeyblanks.ecommerce.notification.notification;

import io.github.joeyblanks.ecommerce.notification.config.RabbitConfig;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class OrderEventListener {

    private final NotificationRecorder recorder;

    public OrderEventListener(NotificationRecorder recorder) {
        this.recorder = recorder;
    }

    /** Ack after the row is committed; an exception triggers the retry policy, then the dead-letter queue. */
    @RabbitListener(queues = RabbitConfig.ORDER_EVENTS_QUEUE)
    public void onMessage(Message message) {
        recorder.record(message.getBody());
    }
}
