package io.datadynamics.ranger.client.admin;

import io.datadynamics.ranger.client.core.Capabilities;
import io.datadynamics.ranger.client.core.Feature;
import io.datadynamics.ranger.client.core.Paging;
import io.datadynamics.ranger.client.core.RangerValidationException;
import io.datadynamics.ranger.client.core.http.HttpCall;
import io.datadynamics.ranger.client.core.http.Transport;
import io.datadynamics.ranger.client.model.RangerSecurityZone;
import io.datadynamics.ranger.client.model.SecurityZoneChangeRequest;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/** Security zones, v2 API ({@code /service/public/v2/api/zones-v2}). */
public final class ZonesClient extends Resource {
    ZonesClient(Transport t, Capabilities c) {
        super(t, c);
    }

    public RangerSecurityZone get(long id) {
        capabilities.require(Feature.ZONES_V2);
        return one(HttpCall.get(API + "/zones-v2/" + id), RangerSecurityZone.class);
    }

    public RangerSecurityZone getByName(String name) {
        capabilities.require(Feature.ZONES_V2);
        try {
            return one(HttpCall.get(API + "/zones-v2/name/" + seg(name)), RangerSecurityZone.class);
        } catch (RangerValidationException e) {
            // Ranger 2.x answers an unknown zone name with 400 "security-zone with name: x does not exist".
            String msg = e.getServerMessage();
            if (msg != null && msg.contains("does not exist")) {
                throw notFound(e);
            }
            throw e;
        }
    }

    /** Like {@link #getByName} but empty when the zone does not exist. */
    public Optional<RangerSecurityZone> find(String name) {
        return optional(() -> getByName(name));
    }

    public List<RangerSecurityZone> search(String name, Integer startIndex, Integer pageSize) {
        capabilities.require(Feature.ZONES_V2);
        return many(paged(HttpCall.get(API + "/zones-v2").query("zoneName", name), startIndex, pageSize), RangerSecurityZone.class);
    }

    public Stream<RangerSecurityZone> stream() {
        return Paging.stream((s, n) -> search(null, s, n), DEFAULT_PAGE);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<Map<String, Object>> headers() {
        return (List) many(HttpCall.get(API + "/zone-headers"), Map.class);
    }

    public RangerSecurityZone create(RangerSecurityZone zone) {
        capabilities.require(Feature.ZONES_V2);
        return one(HttpCall.post(API + "/zones-v2").json(zone), RangerSecurityZone.class);
    }

    public RangerSecurityZone update(RangerSecurityZone zone) {
        capabilities.require(Feature.ZONES_V2);
        Long id = Objects.requireNonNull(zone.getId(), "zone id is required for update()");
        return one(HttpCall.put(API + "/zones-v2/" + id).json(zone), RangerSecurityZone.class);
    }

    /** Add/remove resources, tag services, admins or auditors without resending the whole zone. */
    public boolean updatePartial(long id, SecurityZoneChangeRequest change) {
        capabilities.require(Feature.ZONES_V2);
        Boolean ok = one(HttpCall.put(API + "/zones-v2/" + id + "/partial").json(change), Boolean.class);
        return Boolean.TRUE.equals(ok);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<Map<String, Object>> resources(long id, String service) {
        capabilities.require(Feature.ZONES_V2);
        return (List) many(HttpCall.get(API + "/zones-v2/" + id + "/resources/" + seg(service)), Map.class, "list");
    }

    public void delete(long id) {
        exec(HttpCall.delete(API + "/zones/" + id));
    }

    public void deleteByName(String name) {
        exec(HttpCall.delete(API + "/zones/name/" + seg(name)));
    }
}
