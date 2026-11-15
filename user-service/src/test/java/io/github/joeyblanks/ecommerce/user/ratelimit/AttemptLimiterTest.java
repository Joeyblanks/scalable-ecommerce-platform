package io.github.joeyblanks.ecommerce.user.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.joeyblanks.ecommerce.user.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AttemptLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-15T10:00:00Z"));

    private AttemptLimiter limiter(int max, Duration window, int maxKeys) {
        return new AttemptLimiter(max, window, maxKeys, clock);
    }

    @Test
    void blocksOnlyOnceMaxEventsHappenedInTheWindow() {
        AttemptLimiter l = limiter(3, Duration.ofMinutes(1), 100);

        l.record("a");
        l.record("a");
        assertThat(l.blockedFor("a")).isEmpty();
        l.record("a");

        assertThat(l.blockedFor("a")).isPresent();
    }

    @Test
    void keysAreIndependent() {
        AttemptLimiter l = limiter(2, Duration.ofMinutes(1), 100);
        l.record("a");
        l.record("a");

        assertThat(l.blockedFor("a")).isPresent();
        assertThat(l.blockedFor("b")).isEmpty();
    }

    @Test
    void retryAfterIsTheTimeUntilTheOldestEventLeavesTheWindow() {
        AttemptLimiter l = limiter(2, Duration.ofSeconds(60), 100);
        l.record("a");                       // t=0
        clock.advance(Duration.ofSeconds(20));
        l.record("a");                       // t=20 -> blocked

        assertThat(l.blockedFor("a")).contains(Duration.ofSeconds(40));
        clock.advance(Duration.ofSeconds(15));
        assertThat(l.blockedFor("a")).contains(Duration.ofSeconds(25));
    }

    @Test
    void theBlockEndsWhenTheOldestEventIsOlderThanTheWindow() {
        AttemptLimiter l = limiter(2, Duration.ofSeconds(60), 100);
        l.record("a");
        l.record("a");
        clock.advance(Duration.ofSeconds(59));
        assertThat(l.blockedFor("a")).isPresent();

        clock.advance(Duration.ofSeconds(1));

        assertThat(l.blockedFor("a")).isEmpty();
    }

    @Test
    void aBlockedKeyThatKeepsTryingExtendsNothingBeyondTheLastMaxEvents() {
        AttemptLimiter l = limiter(2, Duration.ofSeconds(60), 100);
        for (int i = 0; i < 50; i++) {
            l.record("a"); // 50 events at t=0
        }
        clock.advance(Duration.ofSeconds(30));
        l.record("a");

        // blocked until the oldest of the LAST 2 events leaves the window, not for 50 events
        assertThat(l.blockedFor("a").orElseThrow()).isLessThanOrEqualTo(Duration.ofSeconds(60));
        clock.advance(Duration.ofSeconds(31));
        assertThat(l.blockedFor("a")).as("the t=0 events are gone, one event (t=30) is left").isEmpty();
    }

    @Test
    void resetForgetsTheKey() {
        AttemptLimiter l = limiter(2, Duration.ofMinutes(1), 100);
        l.record("a");
        l.record("a");

        l.reset("a");

        assertThat(l.blockedFor("a")).isEmpty();
        l.record("a");
        assertThat(l.blockedFor("a")).as("counting starts again from zero").isEmpty();
    }

    @Test
    void memoryIsBoundedByTheNumberOfKeys() {
        AttemptLimiter l = limiter(2, Duration.ofMinutes(1), 50);

        for (int i = 0; i < 10_000; i++) {
            l.record("key-" + i);
        }

        assertThat(l.trackedKeys()).isLessThanOrEqualTo(50);
    }

    @Test
    void expiredKeysAreDroppedBeforeLiveOnesWhenMakingRoom() {
        AttemptLimiter l = limiter(1, Duration.ofSeconds(60), 3);
        l.record("old1");
        l.record("old2");
        clock.advance(Duration.ofSeconds(61));
        l.record("live");
        l.record("new1");

        l.record("new2"); // full: old keys expired, so "live" must survive

        assertThat(l.blockedFor("live")).isPresent();
    }

    @Test
    void rejectsNonsenseSettings() {
        assertThatThrownBy(() -> limiter(0, Duration.ofMinutes(1), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter(1, Duration.ZERO, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter(1, Duration.ofMinutes(1), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retryAfterSecondsRoundsUpAndIsAtLeastOne() {
        assertThat(new TooManyAttemptsException("x", Duration.ofMillis(1)).retryAfterSeconds()).isEqualTo(1);
        assertThat(new TooManyAttemptsException("x", Duration.ZERO).retryAfterSeconds()).isEqualTo(1);
        assertThat(new TooManyAttemptsException("x", Duration.ofMillis(1001)).retryAfterSeconds()).isEqualTo(2);
        assertThat(new TooManyAttemptsException("x", Duration.ofSeconds(60)).retryAfterSeconds()).isEqualTo(60);
    }
}
