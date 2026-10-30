package io.datadynamics.ranger.client.core;

import io.datadynamics.ranger.client.core.auth.AuthProvider;
import io.datadynamics.ranger.client.core.http.Transport;

import java.net.ProxySelector;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/** Settings shared by every client builder (Admin, KMS, PDP). */
public abstract class AbstractClientBuilder<C, B extends AbstractClientBuilder<C, B>> {
    final ServerKind server;
    final List<String> urls = new ArrayList<>();
    AuthProvider auth;
    Tls tls;
    RetryPolicy retry;
    Duration connectTimeout = Duration.ofSeconds(5);
    Duration readTimeout = Duration.ofSeconds(30);
    Duration cooldown = Duration.ofSeconds(30);
    final Map<String, String> headers = new LinkedHashMap<>();
    final List<RequestListener> listeners = new ArrayList<>();
    final List<RequestInterceptor> interceptors = new ArrayList<>();
    boolean retryNonIdempotent;
    ProxySelector proxy;
    Executor executor;
    String serverVersion;
    Transport.Sleeper sleeper;

    protected AbstractClientBuilder(ServerKind server) {
        this.server = server;
    }

    @SuppressWarnings("unchecked")
    protected B self() {
        return (B) this;
    }

    /** One or more server URLs; requests fail over between them. */
    public B urls(String... urls) {
        this.urls.clear();
        this.urls.addAll(Arrays.asList(urls));
        return self();
    }

    public B urls(List<String> urls) {
        this.urls.clear();
        this.urls.addAll(urls);
        return self();
    }

    public B url(String url) {
        return urls(url);
    }

    public B auth(AuthProvider auth) {
        this.auth = auth;
        return self();
    }

    public B tls(Tls tls) {
        this.tls = tls;
        return self();
    }

    public B retry(RetryPolicy retry) {
        this.retry = retry;
        return self();
    }

    public B connectTimeout(Duration timeout) {
        this.connectTimeout = timeout;
        return self();
    }

    public B readTimeout(Duration timeout) {
        this.readTimeout = timeout;
        return self();
    }

    /** How long a failed node is skipped (default 30 s). */
    public B cooldown(Duration cooldown) {
        this.cooldown = cooldown;
        return self();
    }

    public B header(String name, String value) {
        this.headers.put(name, value);
        return self();
    }

    public B listener(RequestListener listener) {
        this.listeners.add(listener);
        return self();
    }

    /** Add a per-attempt interceptor (e.g. OpenTelemetry tracing from {@code ranger-sdk-opentelemetry}). */
    public B interceptor(RequestInterceptor interceptor) {
        this.interceptors.add(interceptor);
        return self();
    }

    /** Also retry POSTs (off by default: a retried create may run twice). */
    public B retryNonIdempotent(boolean enabled) {
        this.retryNonIdempotent = enabled;
        return self();
    }

    /** HTTP proxy; by default none is used (closed networks rarely want the JVM's proxy settings). */
    public B proxy(ProxySelector proxy) {
        this.proxy = proxy;
        return self();
    }

    /** Executor for {@code async()} calls (default: a shared daemon pool). */
    public B executor(Executor executor) {
        this.executor = executor;
        return self();
    }

    /** Declare the server version (e.g. "2.9.0") instead of probing capabilities. */
    public B serverVersion(String version) {
        this.serverVersion = version;
        return self();
    }

    /** Replace the retry backoff sleep (tests). */
    public B sleeper(Transport.Sleeper sleeper) {
        this.sleeper = sleeper;
        return self();
    }

    protected ClientConfig config() {
        return new ClientConfig(this);
    }

    /** A transport for the configured settings. */
    protected Transport transport() {
        ClientConfig cfg = config();
        return sleeper == null ? new Transport(cfg) : new Transport(cfg, sleeper);
    }

    public abstract C build();
}
