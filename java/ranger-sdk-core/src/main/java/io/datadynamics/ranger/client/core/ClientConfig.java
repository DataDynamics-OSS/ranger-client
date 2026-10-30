package io.datadynamics.ranger.client.core;

import io.datadynamics.ranger.client.core.auth.AuthProvider;
import io.datadynamics.ranger.client.core.auth.NoAuth;

import java.net.ProxySelector;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/** Immutable client settings, created by a client builder ({@link AbstractClientBuilder}). */
public final class ClientConfig {
    final ServerKind server;
    final List<URI> urls;
    final AuthProvider auth;
    final Tls tls;
    final RetryPolicy retry;
    final Duration connectTimeout;
    final Duration readTimeout;
    final Duration cooldown;
    final Map<String, String> headers;
    final List<RequestListener> listeners;
    final List<RequestInterceptor> interceptors;
    final boolean retryNonIdempotent;
    final ProxySelector proxy;
    final Executor executor;
    final String serverVersion;

    ClientConfig(AbstractClientBuilder<?, ?> b) {
        this.server = b.server;
        List<URI> list = new ArrayList<>();
        for (String u : b.urls) {
            list.add(URI.create(u.endsWith("/") ? u.substring(0, u.length() - 1) : u));
        }
        if (list.isEmpty()) {
            throw new IllegalArgumentException("at least one server URL is required");
        }
        this.urls = Collections.unmodifiableList(list);
        this.auth = b.auth == null ? NoAuth.INSTANCE : b.auth;
        if (!auth.servers().contains(server)) {
            throw new IllegalArgumentException(auth.describe() + " cannot be used against the " + server + " server");
        }
        this.tls = b.tls == null ? Tls.system() : b.tls;
        this.retry = b.retry == null ? RetryPolicy.defaults() : b.retry;
        this.connectTimeout = b.connectTimeout;
        this.readTimeout = b.readTimeout;
        this.cooldown = b.cooldown;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
        this.listeners = List.copyOf(b.listeners);
        this.interceptors = List.copyOf(b.interceptors);
        this.retryNonIdempotent = b.retryNonIdempotent;
        this.proxy = b.proxy;
        this.executor = b.executor;
        this.serverVersion = b.serverVersion;
    }

    public ServerKind server() {
        return server;
    }

    public List<URI> urls() {
        return urls;
    }

    public AuthProvider auth() {
        return auth;
    }

    public RetryPolicy retry() {
        return retry;
    }

    public Duration readTimeout() {
        return readTimeout;
    }

    /** Server version declared by the user (skips capability probing), or null. */
    public String serverVersion() {
        return serverVersion;
    }

    public Executor executor() {
        return executor;
    }

    public Tls tls() {
        return tls;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration cooldown() {
        return cooldown;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public List<RequestListener> listeners() {
        return listeners;
    }

    public List<RequestInterceptor> interceptors() {
        return interceptors;
    }

    public boolean retryNonIdempotent() {
        return retryNonIdempotent;
    }

    public ProxySelector proxy() {
        return proxy;
    }

    @Override
    public String toString() {
        return "ClientConfig{server=" + server + ", urls=" + urls + ", auth=" + auth.describe() + "}";
    }
}
