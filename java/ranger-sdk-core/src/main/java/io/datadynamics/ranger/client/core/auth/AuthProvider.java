package io.datadynamics.ranger.client.core.auth;

import io.datadynamics.ranger.client.core.ServerKind;

import java.net.URI;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Authentication SPI: decorate a request with headers and/or query parameters, and refresh once when
 * the server answers 401. Implementations must be thread-safe.
 */
public interface AuthProvider {
    /** Headers to add to a request sent to {@code target}. */
    default Map<String, String> headers(URI target) {
        return Collections.emptyMap();
    }

    /** Query parameters to add to every request (e.g. Hadoop simple auth's {@code user.name}). */
    default Map<String, String> queryParams() {
        return Collections.emptyMap();
    }

    /** Called once after a 401. Return true if the request should be retried with fresh credentials. */
    default boolean refresh() {
        return false;
    }

    /** True if the transport may skip {@link #headers} while it holds a Ranger Admin session cookie. */
    default boolean supportsSessionReuse() {
        return false;
    }

    /** Servers this provider may be used against. */
    default Set<ServerKind> servers() {
        return EnumSet.allOf(ServerKind.class);
    }

    /** Human readable, secret-free description (used in toString and logs). */
    default String describe() {
        return getClass().getSimpleName();
    }
}
