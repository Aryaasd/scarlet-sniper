package com.scarletsniper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory sliding-window rate limiter keyed by an arbitrary string.
 *
 * Per-instance only — it wouldn't hold across a horizontally scaled
 * deployment, but this app runs as a single instance. Anything stricter
 * would need the counters in Postgres or Redis.
 */
public class SlidingWindowLimiter {

    private final int maxEvents;
    private final Duration window;
    private final Map<String, Deque<Instant>> eventsByKey = new ConcurrentHashMap<>();

    public SlidingWindowLimiter(int maxEvents, Duration window) {
        this.maxEvents = maxEvents;
        this.window = window;
    }

    /**
     * Records an event against the key and reports whether it should be
     * blocked. When it returns true the event is NOT recorded, so a caller
     * that's already over the limit can't push its own window forward.
     */
    public boolean isLimited(String key) {
        Instant now = Instant.now();
        Deque<Instant> events = eventsByKey.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (events) {
            evictExpired(events, now);
            if (events.size() >= maxEvents) {
                return true;
            }
            events.addLast(now);
            return false;
        }
    }

    /** Clears a key's history, e.g. once a new verification code is issued. */
    public void reset(String key) {
        eventsByKey.remove(key);
    }

    private void evictExpired(Deque<Instant> events, Instant now) {
        while (!events.isEmpty() && Duration.between(events.peekFirst(), now).compareTo(window) > 0) {
            events.pollFirst();
        }
    }
}
