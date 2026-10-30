package io.datadynamics.ranger.client.core.http;

import io.datadynamics.ranger.client.core.RetryPolicy;
import io.datadynamics.ranger.client.model.RangerJson;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One logical request (method, path, query, body) independent of the server it is sent to. */
public final class HttpCall {
    private static final Set<String> IDEMPOTENT = Set.of("GET", "HEAD", "PUT", "DELETE", "OPTIONS");

    final String method;
    final String path;
    final List<String[]> query = new ArrayList<>();
    final Map<String, String> headers = new LinkedHashMap<>();
    byte[] body;
    String contentType;
    Boolean idempotent;
    Duration timeout;
    RetryPolicy retry;

    private HttpCall(String method, String path) {
        this.method = method;
        this.path = path;
    }

    public static HttpCall get(String path) {
        return new HttpCall("GET", path);
    }

    public static HttpCall post(String path) {
        return new HttpCall("POST", path);
    }

    public static HttpCall put(String path) {
        return new HttpCall("PUT", path);
    }

    public static HttpCall delete(String path) {
        return new HttpCall("DELETE", path);
    }

    public static HttpCall of(String method, String path) {
        return new HttpCall(method, path);
    }

    /** Add a query parameter; null values are skipped, collections become repeated parameters. */
    public HttpCall query(String name, Object value) {
        if (value instanceof Collection) {
            for (Object v : (Collection<?>) value) {
                query(name, v);
            }
        } else if (value != null) {
            query.add(new String[] {name, String.valueOf(value)});
        }
        return this;
    }

    public HttpCall query(Map<String, ?> params) {
        if (params != null) {
            params.forEach(this::query);
        }
        return this;
    }

    public HttpCall json(Object value) {
        this.body = RangerJson.writeBytes(value);
        this.contentType = "application/json";
        return this;
    }

    public HttpCall body(byte[] body, String contentType) {
        this.body = body;
        this.contentType = contentType;
        return this;
    }

    public HttpCall header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    /** Override whether the call may be retried automatically (defaults by HTTP method). */
    public HttpCall idempotent(boolean idempotent) {
        this.idempotent = idempotent;
        return this;
    }

    public HttpCall timeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    public HttpCall retry(RetryPolicy retry) {
        this.retry = retry;
        return this;
    }

    public String method() {
        return method;
    }

    public String path() {
        return path;
    }

    boolean isIdempotent() {
        return idempotent != null ? idempotent : IDEMPOTENT.contains(method);
    }
}
