package io.datadynamics.ranger.client.core.auth;

import io.datadynamics.ranger.client.model.RangerJson;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Bearer token. Tokens from a {@link TokenSource} are refreshed {@code skew} (default 60 s) before they
 * expire; the expiry comes from the source or, failing that, from the token's {@code exp} claim.
 */
public final class JwtAuth implements AuthProvider {
    /** A token plus its expiry (null when unknown). */
    public static final class Token {
        final String value;
        final Instant expiresAt;

        public Token(String value, Instant expiresAt) {
            this.value = Objects.requireNonNull(value, "value");
            this.expiresAt = expiresAt;
        }
    }

    /** Fetches a fresh token (from an IdP, a file, a vault...). */
    @FunctionalInterface
    public interface TokenSource {
        Token fetch();
    }

    private final TokenSource source;
    private final boolean refreshable;
    private final Duration skew;
    private final Clock clock;
    private Token current;

    JwtAuth(TokenSource source, boolean refreshable, Duration skew, Clock clock) {
        this.source = source;
        this.refreshable = refreshable;
        this.skew = skew;
        this.clock = clock;
    }

    public static JwtAuth token(String token) {
        Token t = new Token(token, expiryOf(token));
        return new JwtAuth(() -> t, false, Duration.ofSeconds(60), Clock.systemUTC());
    }

    public static JwtAuth supplier(Supplier<String> tokens) {
        return source(() -> {
            String v = tokens.get();
            return new Token(v, expiryOf(v));
        });
    }

    public static JwtAuth source(TokenSource source) {
        return new JwtAuth(source, true, Duration.ofSeconds(60), Clock.systemUTC());
    }

    public JwtAuth withSkew(Duration skew) {
        return new JwtAuth(source, refreshable, skew, clock);
    }

    JwtAuth withClock(Clock clock) {
        return new JwtAuth(source, refreshable, skew, clock);
    }

    private synchronized String value() {
        boolean stale = current != null && current.expiresAt != null
                && !clock.instant().isBefore(current.expiresAt.minus(skew));
        if (current == null || (stale && refreshable)) {
            current = source.fetch();
        }
        return current.value;
    }

    @Override
    public Map<String, String> headers(URI target) {
        return Collections.singletonMap("Authorization", "Bearer " + value());
    }

    @Override
    public synchronized boolean refresh() {
        if (!refreshable) {
            return false;
        }
        current = source.fetch();
        return true;
    }

    @Override
    public String describe() {
        return "JwtAuth";
    }

    @Override
    public String toString() {
        return describe();
    }

    static Instant expiryOf(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            byte[] json = Base64.getUrlDecoder().decode(parts[1]);
            Object exp = RangerJson.read(new String(json, StandardCharsets.UTF_8), Map.class).get("exp");
            return exp instanceof Number ? Instant.ofEpochSecond(((Number) exp).longValue()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
