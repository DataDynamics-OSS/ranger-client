package io.datadynamics.ranger.client.core;

import java.net.URI;
import java.util.function.BiConsumer;

/**
 * Wraps each HTTP attempt (retries and failovers are separate attempts). Unlike {@link RequestListener}
 * it can add headers before the attempt is sent, e.g. W3C {@code traceparent} for distributed tracing,
 * and it gets one callback when the attempt ends. Called on the request thread; exceptions are logged
 * and ignored.
 */
@FunctionalInterface
public interface RequestInterceptor {
    /**
     * @param attempt 1 for the first attempt of a call, then 2, 3, ...
     * @param header  adds a request header to this attempt
     * @return receives the outcome of this attempt (exactly one of its methods is called)
     */
    Attempt begin(ServerKind server, String method, URI uri, int attempt, String requestId, BiConsumer<String, String> header);

    /** Outcome of one attempt. */
    interface Attempt {
        /** The server answered (any status, including errors that will be retried). */
        default void onResponse(int status) {
        }

        /** No response: connection failure, timeout, interruption. */
        default void onError(Throwable error) {
        }
    }

    Attempt NOOP = new Attempt() {
    };
}
