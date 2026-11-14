package io.github.joeyblanks.ecommerce.order.outbox;

import io.github.joeyblanks.ecommerce.order.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes one outbox row and only returns normally once RabbitMQ has CONFIRMED it (publisher
 * confirms). A nack, a timeout, or an unroutable message (mandatory + returns) is a failure: the row
 * stays unpublished and is retried. The message id is the event id, so consumers can deduplicate.
 */
@Component
public class EventPublisher {

    private final RabbitTemplate rabbit;
    private final AppProperties.Events events;
    private final AppProperties.Jobs jobs;

    public EventPublisher(RabbitTemplate rabbit, AppProperties.Events events, AppProperties.Jobs jobs) {
        this.rabbit = rabbit;
        this.events = events;
        this.jobs = jobs;
    }

    public void publish(OutboxRepository.Row row) throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(row.id().toString());
        properties.setType(row.eventType());
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding("UTF-8");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setTimestamp(Date.from(row.createdAt()));
        Message message = new Message(row.payload().getBytes(StandardCharsets.UTF_8), properties);

        CorrelationData correlation = new CorrelationData(row.id().toString());
        rabbit.send(events.exchange(), row.routingKey(), message, correlation);
        CorrelationData.Confirm confirm = correlation.getFuture()
                .get(jobs.publishConfirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
        if (!confirm.ack()) {
            throw new IllegalStateException("broker did not confirm: " + confirm.reason());
        }
        if (correlation.getReturned() != null) {
            throw new IllegalStateException("unroutable (no queue bound): " + correlation.getReturned().getReplyText());
        }
    }
}
