package io.github.joeyblanks.ecommerce.user.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Counts events per key in a sliding window: {@code max} events within {@code window} block the key until the
 * oldest of them is older than the window. In memory and per instance (a second user-service instance would have its
 * own counts; the limit is then at most N times larger). Memory is bounded: each key keeps at most {@code max}
 * timestamps, and at most {@code maxKeys} keys are tracked (expired keys are dropped first, then the
 * least recently used), so a flood of distinct keys cannot grow it without limit.
 */
public final class AttemptLimiter {

    private final int max;
    private final Duration window;
    private final int maxKeys;
    private final Clock clock;
    private final Map<String, ArrayDeque<Instant>> events = new LinkedHashMap<>(16, 0.75f, true); // access order = LRU

    public AttemptLimiter(int max, Duration window, int maxKeys, Clock clock) {
        if (max < 1 || window.isNegative() || window.isZero() || maxKeys < 1) {
            throw new IllegalArgumentException("max, window and maxKeys must be positive");
        }
        this.max = max;
        this.window = window;
        this.maxKeys = maxKeys;
        this.clock = clock;
    }

    /** Records one event for the key. */
    public synchronized void record(String key) {
        Instant now = clock.instant();
        ArrayDeque<Instant> times = events.get(key);
        if (times == null) {
            if (events.size() >= maxKeys) {
                makeRoom(now);
            }
            times = new ArrayDeque<>();
            events.put(key, times);
        }
        drop(times, now);
        times.addLast(now);
        while (times.size() > max) { // only the last `max` matter
            times.removeFirst();
        }
    }

    /** @return how long until the key may try again, or empty if it is not blocked */
    public synchronized Optional<Duration> blockedFor(String key) {
        ArrayDeque<Instant> times = events.get(key);
        if (times == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        drop(times, now);
        if (times.size() < max) {
            if (times.isEmpty()) {
                events.remove(key);
            }
            return Optional.empty();
        }
        Duration wait = Duration.between(now, times.peekFirst().plus(window));
        return Optional.of(wait.isNegative() ? Duration.ZERO : wait);
    }

    /** Forgets the key (for example after a successful login). */
    public synchronized void reset(String key) {
        events.remove(key);
    }

    synchronized int trackedKeys() {
        return events.size();
    }

    private void drop(ArrayDeque<Instant> times, Instant now) {
        Instant cutoff = now.minus(window);
        while (!times.isEmpty() && !times.peekFirst().isAfter(cutoff)) {
            times.removeFirst();
        }
    }

    private void makeRoom(Instant now) {
        for (Iterator<Map.Entry<String, ArrayDeque<Instant>>> it = events.entrySet().iterator(); it.hasNext(); ) {
            ArrayDeque<Instant> times = it.next().getValue();
            drop(times, now);
            if (times.isEmpty()) {
                it.remove();
            }
        }
        while (events.size() >= maxKeys) { // still full: drop the least recently used key
            Iterator<String> it = events.keySet().iterator();
            it.next();
            it.remove();
        }
    }
}
