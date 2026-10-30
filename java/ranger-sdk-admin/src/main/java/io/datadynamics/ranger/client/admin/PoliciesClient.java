package io.datadynamics.ranger.client.admin;

import io.datadynamics.ranger.client.core.Capabilities;
import io.datadynamics.ranger.client.core.Paging;
import io.datadynamics.ranger.client.core.RangerConflictException;
import io.datadynamics.ranger.client.core.RangerException.ErrorContext;
import io.datadynamics.ranger.client.core.http.HttpCall;
import io.datadynamics.ranger.client.core.http.HttpResult;
import io.datadynamics.ranger.client.core.http.Transport;
import io.datadynamics.ranger.client.model.PolicyItem;
import io.datadynamics.ranger.client.model.RangerJson;
import io.datadynamics.ranger.client.model.RangerPolicy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/** Policies: {@code /service/public/v2/api/policy} and friends. */
public final class PoliciesClient extends Resource {
    /** Which item list of a policy an item operation targets. */
    public enum ItemKind {
        ALLOW, DENY, ALLOW_EXCEPTIONS, DENY_EXCEPTIONS
    }

    PoliciesClient(Transport t, Capabilities c) {
        super(t, c);
    }

    public RangerPolicy get(long id) {
        return one(HttpCall.get(API + "/policy/" + id), RangerPolicy.class);
    }

    public RangerPolicy getByName(String service, String name) {
        return getByName(service, name, null);
    }

    public RangerPolicy getByName(String service, String name, String zone) {
        return one(HttpCall.get(API + "/service/" + seg(service) + "/policy/" + seg(name)).query("zoneName", zone), RangerPolicy.class);
    }

    /** Like {@link #getByName} but empty when the policy does not exist. */
    public Optional<RangerPolicy> find(String service, String name, String zone) {
        return optional(() -> getByName(service, name, zone));
    }

    public Page<RangerPolicy> search(PolicyFilter filter) {
        HttpCall call = paged(HttpCall.get(API + "/policy").query(filter.params), filter.startIndex, filter.pageSize);
        return new Page<>(many(call, RangerPolicy.class), filter.startIndex, filter.pageSize);
    }

    /** Every matching policy, fetched page by page as the stream is consumed. */
    public Stream<RangerPolicy> stream(PolicyFilter filter) {
        return Paging.stream((start, size) -> search(filter.copyAt(start).limit(size)).items(), filter.pageSize);
    }

    public List<RangerPolicy> listInService(String service) {
        return many(HttpCall.get(API + "/service/" + seg(service) + "/policy"), RangerPolicy.class);
    }

    /** Policies matching a resource, e.g. {@code forResource("hive", "cm_hive", Map.of("database", "sales"))}. */
    public List<RangerPolicy> forResource(String serviceType, String service, Map<String, String> resource) {
        HttpCall call = HttpCall.get(API + "/policies/" + seg(serviceType) + "/for-resource/").query("serviceName", service);
        resource.forEach((k, v) -> call.query("resource:" + k, v));
        return many(call, RangerPolicy.class);
    }

    /** Policies naming {@code user} directly (groups/roles are not expanded; ask the PDP for decisions). */
    public Stream<RangerPolicy> forUser(String user, String service) {
        PolicyFilter f = service == null ? PolicyFilter.all() : PolicyFilter.service(service);
        return stream(f.user(user));
    }

    public RangerPolicy create(RangerPolicy policy) {
        return one(HttpCall.post(API + "/policy").json(policy), RangerPolicy.class);
    }

    /** Update by id, failing with {@link RangerConflictException} if the server's version moved on. */
    public RangerPolicy update(RangerPolicy policy) {
        return update(policy, true);
    }

    public RangerPolicy update(RangerPolicy policy, boolean checkVersion) {
        Long id = Objects.requireNonNull(policy.getId(), "policy id is required for update(); use apply()");
        if (checkVersion && policy.getVersion() != null) {
            RangerPolicy current = get(id);
            if (current.getVersion() != null && !current.getVersion().equals(policy.getVersion())) {
                throw new RangerConflictException("Policy " + id + " changed on the server (version " + current.getVersion()
                        + ", expected " + policy.getVersion() + ")",
                        new ErrorContext(409, "PUT", API + "/policy/" + id, null, null, null, null));
            }
        }
        return one(HttpCall.put(API + "/policy/" + id).json(policy), RangerPolicy.class);
    }

    public RangerPolicy updateByName(String service, String name, RangerPolicy policy, String zone) {
        return one(HttpCall.put(API + "/service/" + seg(service) + "/policy/" + seg(name)).query("zoneName", zone).json(policy),
                RangerPolicy.class);
    }

    /**
     * Name-based upsert: create when absent, otherwise replace the existing policy (keeping its id).
     * Safe to re-run, unlike a bare create.
     */
    public RangerPolicy apply(RangerPolicy policy) {
        Objects.requireNonNull(policy.getService(), "policy.service");
        Objects.requireNonNull(policy.getName(), "policy.name");
        for (int attempt = 0; ; attempt++) {
            Optional<RangerPolicy> current = find(policy.getService(), policy.getName(), policy.getZoneName());
            if (current.isEmpty()) {
                try {
                    return create(policy);
                } catch (RangerConflictException e) {
                    if (attempt > 0) {
                        throw e;
                    }
                    continue; // created concurrently: update it instead
                }
            }
            RangerPolicy c = current.get();
            return update(policy.toBuilder().id(c.getId()).guid(c.getGuid()).version(c.getVersion()).build(), false);
        }
    }

    /** Ranger's own {@code /policy/apply} (merges into a policy with the same resources). */
    public RangerPolicy serverApply(RangerPolicy policy) {
        return one(HttpCall.post(API + "/policy/apply").json(policy), RangerPolicy.class);
    }

    public void delete(long id) {
        exec(HttpCall.delete(API + "/policy/" + id));
    }

    public void deleteByName(String service, String name) {
        deleteByName(service, name, null);
    }

    public void deleteByName(String service, String name, String zone) {
        exec(HttpCall.delete(API + "/policy").query("servicename", service).query("policyname", name).query("zoneName", zone));
    }

    /** Append items to a policy (read-modify-write with version check). */
    public RangerPolicy addItems(String service, String name, ItemKind kind, Collection<PolicyItem> items) {
        RangerPolicy p = getByName(service, name);
        List<PolicyItem> list = new ArrayList<>(itemsOf(p, kind));
        list.addAll(items);
        return update(withItems(p, kind, list));
    }

    /** Remove principals from a policy's items; items left without any principal are dropped. */
    public RangerPolicy removePrincipals(String service, String name, ItemKind kind,
                                         Set<String> users, Set<String> groups, Set<String> roles) {
        RangerPolicy p = getByName(service, name);
        List<PolicyItem> kept = new ArrayList<>();
        for (PolicyItem item : itemsOf(p, kind)) {
            List<String> u = minus(item.getUsers(), users);
            List<String> g = minus(item.getGroups(), groups);
            List<String> r = minus(item.getRoles(), roles);
            if (!u.isEmpty() || !g.isEmpty() || !r.isEmpty()) {
                kept.add(item.toBuilder().users(u).groups(g).roles(r).build());
            }
        }
        return update(withItems(p, kind, kept));
    }

    /** Bulk export ({@code exportJson}): {@code {"metaDataInfo": ..., "policies": [...]}}. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> exportJson(Collection<String> services, String zone) {
        HttpResult r = transport.send(HttpCall.get(PLUGINS + "/policies/exportJson")
                .query("serviceName", services == null ? null : String.join(",", services))
                .query("zoneName", zone).query("checkPoliciesExists", false));
        return r.isEmpty() ? Map.of("policies", List.of()) : RangerJson.read(r.body(), Map.class);
    }

    /** Bulk import ({@code importPoliciesFromFile}). Not idempotent unless {@code override}. */
    public void importJson(Map<String, Object> data, boolean override, Map<String, String> servicesMap, Map<String, String> zoneMap) {
        String boundary = "rangersdk" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder sb = new StringBuilder();
        part(sb, boundary, "file", "policies.json", RangerJson.write(data));
        if (servicesMap != null) {
            part(sb, boundary, "servicesMapJson", "servicesMap.json", RangerJson.write(servicesMap));
        }
        if (zoneMap != null) {
            part(sb, boundary, "zoneMapJson", "zoneMap.json", RangerJson.write(zoneMap));
        }
        sb.append("--").append(boundary).append("--\r\n");
        exec(HttpCall.post(PLUGINS + "/policies/importPoliciesFromFile").query("isOverride", override)
                .body(sb.toString().getBytes(StandardCharsets.UTF_8), "multipart/form-data; boundary=" + boundary)
                .idempotent(override));
    }

    private static void part(StringBuilder sb, String boundary, String name, String file, String json) {
        sb.append("--").append(boundary).append("\r\n")
          .append("Content-Disposition: form-data; name=\"").append(name).append("\"; filename=\"").append(file).append("\"\r\n")
          .append("Content-Type: application/json\r\n\r\n").append(json).append("\r\n");
    }

    private static List<String> minus(List<String> from, Set<String> drop) {
        List<String> out = new ArrayList<>(from);
        if (drop != null) {
            out.removeAll(drop);
        }
        return out;
    }

    private static List<PolicyItem> itemsOf(RangerPolicy p, ItemKind kind) {
        switch (kind) {
            case DENY: return p.getDenyPolicyItems();
            case ALLOW_EXCEPTIONS: return p.getAllowExceptions();
            case DENY_EXCEPTIONS: return p.getDenyExceptions();
            default: return p.getPolicyItems();
        }
    }

    private static RangerPolicy withItems(RangerPolicy p, ItemKind kind, List<PolicyItem> items) {
        RangerPolicy.Builder b = p.toBuilder();
        switch (kind) {
            case DENY: return b.denyPolicyItems(items).build();
            case ALLOW_EXCEPTIONS: return b.allowExceptions(items).build();
            case DENY_EXCEPTIONS: return b.denyExceptions(items).build();
            default: return b.policyItems(items).build();
        }
    }
}
