package io.datadynamics.ranger.client.admin;

import io.datadynamics.ranger.client.core.Capabilities;
import io.datadynamics.ranger.client.core.Paging;
import io.datadynamics.ranger.client.core.http.HttpCall;
import io.datadynamics.ranger.client.core.http.Transport;
import io.datadynamics.ranger.client.model.RangerService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** {@code /service/public/v2/api/service} */
public final class ServicesClient extends Resource {
    ServicesClient(Transport t, Capabilities c) {
        super(t, c);
    }

    public RangerService get(long id) {
        return one(HttpCall.get(API + "/service/" + id), RangerService.class);
    }

    public RangerService getByName(String name) {
        return one(HttpCall.get(API + "/service/name/" + seg(name)), RangerService.class);
    }

    public Optional<RangerService> find(String name) {
        return optional(() -> getByName(name));
    }

    public List<RangerService> search(String serviceType, Map<String, ?> filters, Integer startIndex, Integer pageSize) {
        HttpCall call = HttpCall.get(API + "/service").query("serviceType", serviceType).query(filters);
        return many(paged(call, startIndex, pageSize), RangerService.class);
    }

    public Stream<RangerService> stream(String serviceType) {
        return Paging.stream((s, n) -> search(serviceType, null, s, n), DEFAULT_PAGE);
    }

    public RangerService create(RangerService service) {
        return one(HttpCall.post(API + "/service").json(service), RangerService.class);
    }

    public RangerService update(RangerService service) {
        String path = service.getId() != null ? API + "/service/" + service.getId() : API + "/service/name/" + seg(service.getName());
        return one(HttpCall.put(path).json(service), RangerService.class);
    }

    /** Create the service, or update the existing one with the same name. */
    public RangerService apply(RangerService service) {
        Optional<RangerService> current = find(service.getName());
        if (current.isEmpty()) {
            return create(service);
        }
        RangerService c = current.get();
        return update(service.toBuilder().id(c.getId()).guid(c.getGuid()).version(c.getVersion()).build());
    }

    public void delete(long id) {
        exec(HttpCall.delete(API + "/service/" + id));
    }

    public void deleteByName(String name) {
        exec(HttpCall.delete(API + "/service/name/" + seg(name)));
    }

    /** Run the plugin's connection check ({@code validateConfig}); {@code statusCode} 0 means success. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> testConnection(RangerService service) {
        return one(HttpCall.post(PLUGINS + "/services/validateConfig").json(service).idempotent(true), Map.class);
    }
}
