package io.datadynamics.ranger.client.admin;

import io.datadynamics.ranger.client.core.Capabilities;
import io.datadynamics.ranger.client.core.Feature;
import io.datadynamics.ranger.client.core.http.HttpCall;
import io.datadynamics.ranger.client.core.http.Transport;

import java.util.List;
import java.util.Map;

/**
 * Governed Data Sharing ({@code /service/gds}). GDS is not part of the Ranger 2.x release line, so every
 * call first checks {@link Feature#GDS} and throws {@code UnsupportedFeatureException} on 2.x servers.
 * Objects are plain maps until GDS models are added to the spec.
 */
public final class GdsClient extends Resource {
    GdsClient(Transport t, Capabilities c) {
        super(t, c);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> get(String kind, long id) {
        capabilities.require(Feature.GDS);
        return one(HttpCall.get(GDS + "/" + kind + "/" + id), Map.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<Map<String, Object>> search(String kind, Map<String, ?> filters, Integer startIndex, Integer pageSize) {
        capabilities.require(Feature.GDS);
        return (List) many(paged(HttpCall.get(GDS + "/" + kind).query(filters), startIndex, pageSize), Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> create(String kind, Map<String, Object> obj) {
        capabilities.require(Feature.GDS);
        return one(HttpCall.post(GDS + "/" + kind).json(obj), Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> update(String kind, long id, Map<String, Object> obj) {
        capabilities.require(Feature.GDS);
        return one(HttpCall.put(GDS + "/" + kind + "/" + id).json(obj), Map.class);
    }

    public void delete(String kind, long id) {
        capabilities.require(Feature.GDS);
        exec(HttpCall.delete(GDS + "/" + kind + "/" + id));
    }
}
