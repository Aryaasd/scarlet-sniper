package com.scarletsniper;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SlidingWindowLimiterTest {

    @Test
    void allowsUpToTheLimitThenBlocks() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(3, Duration.ofMinutes(10));

        assertThat(limiter.isLimited("k")).isFalse();
        assertThat(limiter.isLimited("k")).isFalse();
        assertThat(limiter.isLimited("k")).isFalse();
        assertThat(limiter.isLimited("k")).isTrue();
    }

    @Test
    void tracksKeysIndependently() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(1, Duration.ofMinutes(10));

        assertThat(limiter.isLimited("a")).isFalse();
        assertThat(limiter.isLimited("a")).isTrue();
        assertThat(limiter.isLimited("b")).isFalse();
    }

    @Test
    void resetClearsHistoryForOneKeyOnly() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(1, Duration.ofMinutes(10));
        limiter.isLimited("a");
        limiter.isLimited("b");

        limiter.reset("a");

        assertThat(limiter.isLimited("a")).isFalse();
        assertThat(limiter.isLimited("b")).isTrue();
    }

    @Test
    void eventsAgeOutOfTheWindow() throws InterruptedException {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(1, Duration.ofMillis(80));

        assertThat(limiter.isLimited("k")).isFalse();
        assertThat(limiter.isLimited("k")).isTrue();

        Thread.sleep(140);

        assertThat(limiter.isLimited("k")).isFalse();
    }

    // A blocked call must not record itself, otherwise a caller hammering
    // the endpoint would keep pushing its own window forward and stay
    // locked out indefinitely rather than for the configured duration.
    //
    // Timings are chosen so the two behaviours give opposite results: the
    // last rejected call lands well inside the window, while the original
    // accepted call has aged out by the final check. If rejections were
    // recorded, that last one would still be blocking at the end.
    @Test
    void blockedCallsDoNotExtendTheWindow() throws InterruptedException {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(1, Duration.ofMillis(500));

        assertThat(limiter.isLimited("k")).isFalse(); // t≈0, recorded

        for (int i = 0; i < 3; i++) {
            Thread.sleep(50);
            assertThat(limiter.isLimited("k")).isTrue(); // t≈50/100/150, rejected
        }

        // Final check at t≈600, with ~100ms of margin either side:
        //   600ms since the accepted call  -> past the 500ms window, evicted.
        //   450ms since the last rejection -> still inside the window, so if
        //   rejections were being recorded this would report limited.
        Thread.sleep(450);
        assertThat(limiter.isLimited("k")).isFalse();
    }
}
