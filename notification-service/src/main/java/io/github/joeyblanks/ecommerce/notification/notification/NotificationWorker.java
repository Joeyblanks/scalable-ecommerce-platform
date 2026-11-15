package io.github.joeyblanks.ecommerce.notification.notification;

import io.github.joeyblanks.ecommerce.notification.config.AppProperties;
import io.github.joeyblanks.ecommerce.notification.events.OrderEventParser;
import io.github.joeyblanks.ecommerce.notification.mail.Email;
import io.github.joeyblanks.ecommerce.notification.mail.EmailSender;
import io.github.joeyblanks.ecommerce.notification.user.Contact;
import io.github.joeyblanks.ecommerce.notification.user.UserContactClient;
import io.github.joeyblanks.ecommerce.notification.user.UserNotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends the pending notifications. No database transaction is open while talking to user-service or the mail
 * server. Claiming (with a lease) and completing (conditional update) are single statements, so two workers never
 * send the same notification at the same time, and a crashed worker's notification is picked up after the lease.
 *
 * <p>Order of events inside one send: look up the address, send, then mark SENT. A crash between "send" and "mark
 * SENT" re-sends once after the lease expires: SMTP offers no idempotency, and a rare duplicate was chosen over
 * a lost email (ADR 010). Everything else is exactly once per event.
 */
@Component
public class NotificationWorker {

    private static final Logger log = LoggerFactory.getLogger(NotificationWorker.class);

    private final NotificationRepository notifications;
    private final OrderEventParser parser;
    private final EmailRenderer renderer;
    private final UserContactClient contacts;
    private final EmailSender sender;
    private final AppProperties.Notifications config;
    private final Clock clock;

    public NotificationWorker(NotificationRepository notifications, OrderEventParser parser, EmailRenderer renderer,
                              UserContactClient contacts, EmailSender sender, AppProperties.Notifications config, Clock clock) {
        this.notifications = notifications;
        this.parser = parser;
        this.renderer = renderer;
        this.contacts = contacts;
        this.sender = sender;
        this.config = config;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.jobs.worker-interval}")
    public void loop() {
        try {
            sendDue();
        } catch (RuntimeException e) {
            log.warn("Notification worker run failed (will retry): {}", e.toString()); // e.g. database unavailable
        }
    }

    /** @return how many notifications were handled */
    public int sendDue() {
        Instant now = clock.instant();
        List<NotificationRepository.Claimed> claimed = notifications.claimDue(now, now.plus(config.lease()), config.batchSize());
        claimed.forEach(this::send);
        return claimed.size();
    }

    private void send(NotificationRepository.Claimed claim) {
        try {
            Contact contact = contacts.contactOf(claim.userId());
            Email email = renderer.render(parser.parse(claim.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    contact, config.from());
            sender.send(email);
        } catch (UserNotFoundException e) {
            // No address can ever be found: do not retry.
            log.error("Notification {} ({}) for order {} FAILED: {}", claim.id(), claim.kind(), claim.orderId(), e.getMessage());
            notifications.markFailed(claim.id(), e.getMessage(), clock.instant());
            return;
        } catch (RuntimeException e) {
            retryOrGiveUp(claim, e);
            return;
        }
        if (notifications.markSent(claim.id(), clock.instant())) {
            log.info("Notification {} ({}) for order {} sent", claim.id(), claim.kind(), claim.orderId());
        } else {
            log.info("Notification {} was already completed by someone else", claim.id());
        }
    }

    private void retryOrGiveUp(NotificationRepository.Claimed claim, RuntimeException e) {
        Instant now = clock.instant();
        String error = e.getClass().getSimpleName() + ": " + e.getMessage();
        if (claim.attempt() >= config.maxAttempts() || Duration.between(claim.createdAt(), now).compareTo(config.giveUpAfter()) > 0) {
            log.error("Notification {} ({}) for order {} FAILED after {} attempts: {}", claim.id(), claim.kind(), claim.orderId(),
                    claim.attempt(), error);
            notifications.markFailed(claim.id(), error, now);
        } else {
            log.warn("Notification {} ({}) attempt {} failed, will retry: {}", claim.id(), claim.kind(), claim.attempt(), error);
            notifications.scheduleRetry(claim.id(), error, now.plus(backoff(claim.attempt())), now);
        }
    }

    Duration backoff(int attempt) {
        long factor = 1L << Math.min(Math.max(attempt - 1, 0), 20);
        Duration d = config.retryInitialDelay().multipliedBy(factor);
        return d.compareTo(config.retryMaxDelay()) > 0 ? config.retryMaxDelay() : d;
    }
}
