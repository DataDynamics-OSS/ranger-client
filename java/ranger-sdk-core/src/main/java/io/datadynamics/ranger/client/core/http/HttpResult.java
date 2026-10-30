package io.datadynamics.ranger.client.core.http;

import com.fasterxml.jackson.core.type.TypeReference;
import io.datadynamics.ranger.client.model.RangerJson;

import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;

/** A successful (status &lt; 400) response. */
public final class HttpResult {
    private final int status;
    private final HttpHeaders headers;
    private final byte[] body;

    HttpResult(int status, HttpHeaders headers, byte[] body) {
        this.status = status;
        this.headers = headers;
        this.body = body == null ? new byte[0] : body;
    }

    public int status() {
        return status;
    }

    public HttpHeaders headers() {
        return headers;
    }

    public byte[] body() {
        return body;
    }

    public boolean isEmpty() {
        return status == 204 || body.length == 0;
    }

    public String text() {
        return new String(body, StandardCharsets.UTF_8);
    }

    public <T> T as(Class<T> type) {
        return isEmpty() ? null : RangerJson.read(body, type);
    }

    public <T> T as(TypeReference<T> type) {
        return isEmpty() ? null : RangerJson.read(body, type);
    }
}
