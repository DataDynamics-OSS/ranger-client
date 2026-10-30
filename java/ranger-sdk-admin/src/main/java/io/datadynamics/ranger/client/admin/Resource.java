package io.datadynamics.ranger.client.admin;

import com.fasterxml.jackson.databind.JavaType;
import io.datadynamics.ranger.client.core.Capabilities;
import io.datadynamics.ranger.client.core.RangerNotFoundException;
import io.datadynamics.ranger.client.core.http.HttpCall;
import io.datadynamics.ranger.client.core.http.HttpResult;
import io.datadynamics.ranger.client.core.http.Transport;
import io.datadynamics.ranger.client.model.RangerJson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** Helpers shared by the Admin sub-clients. */
abstract class Resource {
    static final String API = "/service/public/v2/api";
    static final String XUSERS = "/service/xusers";
    static final String TAGS = "/service/tags";
    static final String GDS = "/service/gds";
    static final String PLUGINS = "/service/plugins";
    static final int DEFAULT_PAGE = 200;

    final Transport transport;
    final Capabilities capabilities;

    Resource(Transport transport, Capabilities capabilities) {
        this.transport = transport;
        this.capabilities = capabilities;
    }

    static String seg(Object v) {
        return Transport.seg(v);
    }

    <T> T one(HttpCall call, Class<T> type) {
        return transport.send(call).as(type);
    }

    void exec(HttpCall call) {
        transport.send(call);
    }

    /** A JSON array, or a PList/VList wrapper ({@code list}, {@code vXUsers}, ...) under {@code key}. */
    @SuppressWarnings("unchecked")
    <T> List<T> many(HttpCall call, Class<T> type, String key) {
        HttpResult r = transport.send(call);
        if (r.isEmpty()) {
            return Collections.emptyList();
        }
        Object raw = RangerJson.read(r.body(), Object.class);
        if (raw instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) raw;
            raw = key != null ? m.get(key) : m.containsKey("list") ? m.get("list") : m.get("vXList");
        }
        if (raw == null) {
            return Collections.emptyList();
        }
        JavaType lt = RangerJson.mapper().getTypeFactory().constructCollectionType(List.class, type);
        return new ArrayList<>(RangerJson.mapper().convertValue(raw, lt));
    }

    <T> List<T> many(HttpCall call, Class<T> type) {
        return many(call, type, null);
    }

    static <T> Optional<T> optional(Supplier<T> call) {
        try {
            return Optional.ofNullable(call.get());
        } catch (RangerNotFoundException e) {
            return Optional.empty();
        }
    }

    static HttpCall paged(HttpCall call, Integer start, Integer size) {
        return call.query("startIndex", start).query("pageSize", size);
    }
}
