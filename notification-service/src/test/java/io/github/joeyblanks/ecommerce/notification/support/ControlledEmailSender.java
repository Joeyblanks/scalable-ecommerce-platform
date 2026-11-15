package io.github.joeyblanks.ecommerce.notification.support;

import io.github.joeyblanks.ecommerce.notification.mail.Email;
import io.github.joeyblanks.ecommerce.notification.mail.EmailSender;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Wraps the real SMTP sender to count calls and to inject failures, a crash after sending, or a slow send. */
public class ControlledEmailSender implements EmailSender {

    private final EmailSender real;
    public final AtomicInteger calls = new AtomicInteger();
    public final List<String> messageIds = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private volatile boolean failAfterSending;
    private volatile CountDownLatch gate;

    public ControlledEmailSender(EmailSender real) {
        this.real = real;
    }

    @Override
    public String name() {
        return "controlled-" + real.name();
    }

    @Override
    public void send(Email email) {
        calls.incrementAndGet();
        CountDownLatch g = gate;
        if (g != null) {
            try {
                g.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (failuresLeft.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            if (failAfterSending) {
                real.send(email); // delivered, but the caller never learns of it (like a lost SMTP response)
                messageIds.add(email.messageId());
            }
            throw new IllegalStateException("simulated mail server failure");
        }
        real.send(email);
        messageIds.add(email.messageId());
    }

    /** The next {@code n} sends fail (nothing is delivered). */
    public void failNext(int n) {
        failAfterSending = false;
        failuresLeft.set(n);
    }

    /** The next send is delivered but reports a failure: the "unknown outcome" case. */
    public void deliverButReportFailureOnce() {
        failAfterSending = true;
        failuresLeft.set(1);
    }

    public void alwaysFail() {
        failAfterSending = false;
        failuresLeft.set(Integer.MAX_VALUE);
    }

    public void holdSends(CountDownLatch latch) {
        this.gate = latch;
    }

    public void reset() {
        calls.set(0);
        messageIds.clear();
        failuresLeft.set(0);
        failAfterSending = false;
        gate = null;
    }
}
