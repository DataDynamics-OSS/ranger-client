package io.datadynamics.ranger.client.core;

import java.net.URI;
import java.time.Duration;

/**
 * Observability hook (metrics, tracing, audit). Called on the request thread; must not throw (exceptions
 * are logged and ignored). Headers passed in are already masked.
 */
public interface RequestListener {
    default void onRequest(String method, URI uri, int attempt, String requestId) {
    }

    default void onResponse(String method, URI uri, int status, Duration elapsed, int attempt, String requestId) {
    }

    default void onError(String method, URI uri, Throwable error, int attempt, String requestId) {
    }
}
