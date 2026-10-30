package io.datadynamics.ranger.client.admin;

import io.datadynamics.ranger.client.core.Capabilities;
import io.datadynamics.ranger.client.core.Paging;
import io.datadynamics.ranger.client.core.http.HttpCall;
import io.datadynamics.ranger.client.core.http.Transport;
import io.datadynamics.ranger.client.model.RangerServiceDef;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** {@code /service/public/v2/api/servicedef} */
public final class ServiceDefsClient extends Resource {
    ServiceDefsClient(Transport t, Capabilities c) {
        super(t, c);
    }

    public RangerServiceDef get(long id) {
        return one(HttpCall.get(API + "/servicedef/" + id), RangerServiceDef.class);
    }

    public RangerServiceDef getByName(String name) {
        return one(HttpCall.get(API + "/servicedef/name/" + seg(name)), RangerServiceDef.class);
    }

    public Optional<RangerServiceDef> find(String name) {
        return optional(() -> getByName(name));
    }

    public List<RangerServiceDef> search(Map<String, ?> filters, Integer startIndex, Integer pageSize) {
        return many(paged(HttpCall.get(API + "/servicedef").query(filters), startIndex, pageSize), RangerServiceDef.class);
    }

    public Stream<RangerServiceDef> stream() {
        return Paging.stream((s, n) -> search(null, s, n), DEFAULT_PAGE);
    }

    public RangerServiceDef create(RangerServiceDef def) {
        return one(HttpCall.post(API + "/servicedef").json(def), RangerServiceDef.class);
    }

    public RangerServiceDef update(RangerServiceDef def) {
        String path = def.getId() != null ? API + "/servicedef/" + def.getId() : API + "/servicedef/name/" + seg(def.getName());
        return one(HttpCall.put(path).json(def), RangerServiceDef.class);
    }

    public void delete(long id) {
        exec(HttpCall.delete(API + "/servicedef/" + id));
    }

    public void deleteByName(String name) {
        exec(HttpCall.delete(API + "/servicedef/name/" + seg(name)));
    }
}
