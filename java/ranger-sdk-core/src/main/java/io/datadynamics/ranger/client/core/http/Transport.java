package io.datadynamics.ranger.client.core.http;

import io.datadynamics.ranger.client.core.ClientConfig;
import io.datadynamics.ranger.client.core.RangerException;
import io.datadynamics.ranger.client.core.RangerException.ErrorContext;
import io.datadynamics.ranger.client.core.RangerNotFoundException;
import io.datadynamics.ranger.client.core.RangerTransportException;
import io.datadynamics.ranger.client.core.RequestInterceptor;
import io.datadynamics.ranger.client.core.RequestListener;
import io.datadynamics.ranger.client.core.RetryPolicy;
import io.datadynamics.ranger.client.core.auth.AuthProvider;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.ConnectException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Thread-safe HTTP transport: node selection with failover, authentication, retry with backoff, Ranger
 * session-cookie reuse and error mapping. One instance per client.
 */
public final class Transport implements AutoCloseable {
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String SESSION_COOKIE = "RANGERADMINSESSIONID";
    static final String USER_AGENT = "ranger-sdk-java/0.2.0";
    private static final System.Logger LOG = System.getLogger("io.datadynamics.ranger.client");

    private final ClientConfig cfg;
    private final Endpoints endpoints;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http;
    private final Sleeper sleeper;

    /** Pluggable for tests. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    public Transport(ClientConfig cfg) {
        this(cfg, d -> Thread.sleep(d.toMillis()));
    }

    public Transport(ClientConfig cfg, Sleeper sleeper) {
        this.cfg = cfg;
        this.sleeper = sleeper;
        this.endpoints = new Endpoints(cfg.urls(), cfg.cooldown());
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(cfg.connectTimeout())
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .sslContext(cfg.tls().sslContext())
                .version(HttpClient.Version.HTTP_1_1);
        if (cfg.proxy() != null) {
            b.proxy(cfg.proxy());
        } else {
            b.proxy(HttpClient.Builder.NO_PROXY);
        }
        this.http = b.build();
    }

    public ClientConfig config() {
        return cfg;
    }

    /** Server URLs and whether each is currently in rotation (see {@link Endpoints#availability()}). */
    public Map<URI, Boolean> nodeAvailability() {
        return endpoints.availability();
    }

    /** Send a call and return the successful response; errors are mapped to {@code RangerException}s. */
    public HttpResult send(HttpCall call) {
        RetryPolicy policy = call.retry != null ? call.retry : cfg.retry();
        boolean retryable = call.isIdempotent() || cfg.retryNonIdempotent();
        String requestId = call.headers.getOrDefault(REQUEST_ID_HEADER, UUID.randomUUID().toString().replace("-", ""));
        List<URI> failed = new ArrayList<>();
        boolean authRefreshed = false;
        int attempt = 0;
        while (true) {
            attempt++;
            URI base = endpoints.pick(failed);
            URI target = target(call, base);
            Map<String, String> extra = new LinkedHashMap<>();
            List<RequestInterceptor.Attempt> attempts = begin(call.method, target, attempt, requestId, extra);
            HttpRequest request = build(call, target, requestId, extra);
            if (call.body != null) {
                requestBody(attempts, call.contentType, call.body);
            }
            fireRequest(call.method, request.uri(), attempt, requestId);
            long started = System.nanoTime();
            HttpResponse<byte[]> resp;
            try {
                resp = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException e) {
                end(attempts, null, null, e);
                endpoints.markDown(base);
                failed.add(base);
                fireError(call.method, request.uri(), e, attempt, requestId);
                LOG.log(Level.WARNING, "{0} {1} failed (attempt {2}/{3}): {4}", call.method, request.uri(), attempt,
                        policy.maxAttempts(), e.toString());
                boolean neverSent = e instanceof ConnectException || e instanceof HttpConnectTimeoutException;
                if ((retryable || neverSent) && attempt < policy.maxAttempts()) {
                    pause(endpoints.size() > 1 ? Duration.ZERO : policy.delay(attempt), requestId);
                    continue;
                }
                throw new RangerTransportException("Could not reach Ranger " + cfg.server() + ": " + e,
                        new ErrorContext(null, call.method, call.path, base.toString(), requestId, null, null), e);
            } catch (InterruptedException e) {
                end(attempts, null, null, e);
                Thread.currentThread().interrupt();
                throw new RangerTransportException("Interrupted",
                        new ErrorContext(null, call.method, call.path, base.toString(), requestId, null, null), e);
            }
            int status = resp.statusCode();
            end(attempts, status, resp.body(), null);
            fireResponse(call.method, request.uri(), status, Duration.ofNanos(System.nanoTime() - started), attempt, requestId);
            LOG.log(Level.DEBUG, "<- {0} {1} status={2} id={3}", call.method, request.uri(), status, requestId);
            if (status < 400) {
                endpoints.markUp(base);
                return new HttpResult(status, resp.headers(), resp.body());
            }
            if (status == 401 && !authRefreshed) {
                authRefreshed = true;
                boolean hadSession = clearSession();
                if (cfg.auth().refresh() || hadSession) {
                    attempt--; // re-authentication does not consume a retry
                    continue;
                }
            }
            if (status == 429 || status >= 500) {
                RangerException mapped = ErrorMapper.map(status, resp.body(), call.method, call.path, base.toString(), requestId);
                if (mapped instanceof RangerNotFoundException) {
                    throw mapped; // e.g. KMS "key does not exist": an answer, not a server fault; no retry, node stays up
                }
                if (status >= 500) {
                    endpoints.markDown(base);
                    if (endpoints.size() > 1) {
                        failed.add(base);
                    }
                }
                if (retryable && attempt < policy.maxAttempts()) {
                    Duration wait = retryAfter(resp);
                    pause(wait != null ? wait : policy.delay(attempt), requestId);
                    continue;
                }
            }
            throw ErrorMapper.map(status, resp.body(), call.method, call.path, base.toString(), requestId);
        }
    }

    private URI target(HttpCall call, URI base) {
        StringBuilder url = new StringBuilder(base.toString()).append(call.path);
        List<String[]> query = new ArrayList<>();
        cfg.auth().queryParams().forEach((k, v) -> query.add(new String[] {k, v}));
        query.addAll(call.query);
        char sep = call.path.contains("?") ? '&' : '?';
        for (String[] kv : query) {
            url.append(sep).append(enc(kv[0])).append('=').append(enc(kv[1]));
            sep = '&';
        }
        return URI.create(url.toString());
    }

    private HttpRequest build(HttpCall call, URI uri, String requestId, Map<String, String> extra) {
        AuthProvider auth = cfg.auth();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("User-Agent", USER_AGENT);
        headers.put(REQUEST_ID_HEADER, requestId);
        headers.putAll(cfg.headers());
        if (!(auth.supportsSessionReuse() && hasSession(uri))) {
            headers.putAll(auth.headers(uri));
        }
        headers.putAll(call.headers);
        headers.putAll(extra);
        HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
                .timeout(call.timeout != null ? call.timeout : cfg.readTimeout());
        HttpRequest.BodyPublisher body = call.body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(call.body);
        if (call.contentType != null) {
            headers.put("Content-Type", call.contentType);
        }
        headers.forEach(rb::header);
        rb.method(call.method, body);
        if (LOG.isLoggable(Level.DEBUG)) {
            LOG.log(Level.DEBUG, "-> {0} {1} id={2} headers={3}", call.method, uri, requestId, Masking.headers(headers));
        }
        return rb.build();
    }

    private boolean hasSession(URI uri) {
        for (HttpCookie c : cookies.getCookieStore().get(uri)) {
            if (SESSION_COOKIE.equals(c.getName()) && !c.hasExpired()) {
                return true;
            }
        }
        return false;
    }

    private boolean clearSession() {
        boolean had = false;
        for (HttpCookie c : new ArrayList<>(cookies.getCookieStore().getCookies())) {
            if (SESSION_COOKIE.equals(c.getName())) {
                had = true;
            }
        }
        cookies.getCookieStore().removeAll();
        return had;
    }

    private void pause(Duration d, String requestId) {
        if (d.isZero() || d.isNegative()) {
            return;
        }
        try {
            sleeper.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RangerTransportException("Interrupted during retry backoff",
                    new ErrorContext(null, null, null, null, requestId, null, null), e);
        }
    }

    static Duration retryAfter(HttpResponse<?> resp) {
        String v = resp.headers().firstValue("Retry-After").orElse(null);
        if (v == null) {
            return null;
        }
        try {
            return Duration.ofMillis((long) (Double.parseDouble(v.trim()) * 1000));
        } catch (NumberFormatException e) {
            try {
                Duration d = Duration.between(ZonedDateTime.now(), ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME));
                return d.isNegative() ? Duration.ZERO : d;
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Encode one path segment (names may contain spaces or slashes). */
    public static String seg(Object value) {
        return enc(String.valueOf(value));
    }

    private List<RequestInterceptor.Attempt> begin(String method, URI uri, int attempt, String id, Map<String, String> extra) {
        List<RequestInterceptor> interceptors = cfg.interceptors();
        if (interceptors.isEmpty()) {
            return List.of();
        }
        List<RequestInterceptor.Attempt> out = new ArrayList<>(interceptors.size());
        for (RequestInterceptor i : interceptors) {
            try {
                RequestInterceptor.Attempt a = i.begin(cfg.server(), method, uri, attempt, id, extra::put);
                out.add(a == null ? RequestInterceptor.NOOP : a);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "request interceptor failed", e);
            }
        }
        return out;
    }

    private static void requestBody(List<RequestInterceptor.Attempt> attempts, String contentType, byte[] body) {
        for (RequestInterceptor.Attempt a : attempts) {
            try {
                a.onRequestBody(contentType, body);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "request interceptor failed", e);
            }
        }
    }

    private static void end(List<RequestInterceptor.Attempt> attempts, Integer status, byte[] body, Throwable error) {
        for (RequestInterceptor.Attempt a : attempts) {
            try {
                if (status != null) {
                    a.onResponse(status, body);
                } else {
                    a.onError(error);
                }
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "request interceptor failed", e);
            }
        }
    }

    private void fireRequest(String method, URI uri, int attempt, String id) {
        for (RequestListener l : cfg.listeners()) {
            try {
                l.onRequest(method, uri, attempt, id);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "request listener failed", e);
            }
        }
    }

    private void fireResponse(String method, URI uri, int status, Duration elapsed, int attempt, String id) {
        for (RequestListener l : cfg.listeners()) {
            try {
                l.onResponse(method, uri, status, elapsed, attempt, id);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "request listener failed", e);
            }
        }
    }

    private void fireError(String method, URI uri, Throwable error, int attempt, String id) {
        for (RequestListener l : cfg.listeners()) {
            try {
                l.onError(method, uri, error, attempt, id);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "request listener failed", e);
            }
        }
    }

    @Override
    public void close() {
        // java.net.http.HttpClient has no close() before JDK 21; connections are released when idle.
    }
}
