package io.datadynamics.ranger.client.core;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with full jitter. Only idempotent requests (GET, PUT, DELETE) and PDP decisions
 * are retried automatically; {@code Retry-After} is honoured.
 */
public final class RetryPolicy {
    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final boolean jitter;

    private RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay, boolean jitter) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.maxAttempts = maxAttempts;
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.jitter = jitter;
    }

    public static RetryPolicy exponential(int maxAttempts, Duration baseDelay) {
        return new RetryPolicy(maxAttempts, baseDelay, Duration.ofSeconds(5), true);
    }

    public static RetryPolicy defaults() {
        return exponential(3, Duration.ofMillis(200));
    }

    public static RetryPolicy none() {
        return new RetryPolicy(1, Duration.ZERO, Duration.ZERO, false);
    }

    public RetryPolicy withMaxDelay(Duration maxDelay) {
        return new RetryPolicy(maxAttempts, baseDelay, maxDelay, jitter);
    }

    public RetryPolicy withoutJitter() {
        return new RetryPolicy(maxAttempts, baseDelay, maxDelay, false);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /** Delay before attempt {@code attempt + 1}. */
    public Duration delay(int attempt) {
        long base = baseDelay.toMillis();
        long d = Math.min(maxDelay.toMillis(), base * (1L << Math.min(attempt - 1, 20)));
        if (jitter && d > 0) {
            d = ThreadLocalRandom.current().nextLong(d + 1);
        }
        return Duration.ofMillis(d);
    }
}
