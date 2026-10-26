package com.robotrac.recruitment.core;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory fixed-window rate limiter keyed by strings such as "candidate-login:ip:1.2.3.4".
 * Suitable for a single application instance. For several instances behind a load balancer, move this
 * to a shared store (documented limitation).
 */
public class FixedWindowRateLimiter {

    private record Window(long startMillis, int count) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public FixedWindowRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Records one hit and returns true while the key is within {@code max} hits per window. */
    public boolean tryAcquire(String key, int max, Duration window) {
        long now = clock.millis();
        long windowMillis = window.toMillis();
        Window updated = windows.compute(key, (k, w) -> {
            if (w == null || now - w.startMillis() >= windowMillis) {
                return new Window(now, 1);
            }
            return new Window(w.startMillis(), w.count() + 1);
        });
        return updated.count() <= max;
    }

    /** Returns true if the key has already reached {@code max} hits in the current window (no hit recorded). */
    public boolean isBlocked(String key, int max, Duration window) {
        Window w = windows.get(key);
        return w != null && clock.millis() - w.startMillis() < window.toMillis() && w.count() >= max;
    }

    public void reset(String key) {
        windows.remove(key);
    }

    /** Drops expired windows; called periodically. */
    public void evictOlderThan(Duration age) {
        long cutoff = clock.millis() - age.toMillis();
        windows.entrySet().removeIf(e -> e.getValue().startMillis() < cutoff);
    }

    public int size() {
        return windows.size();
    }
}
