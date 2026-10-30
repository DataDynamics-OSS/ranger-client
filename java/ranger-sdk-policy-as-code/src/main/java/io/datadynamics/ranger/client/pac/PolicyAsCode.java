package io.datadynamics.ranger.client.pac;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.datadynamics.ranger.client.admin.PolicyFilter;
import io.datadynamics.ranger.client.admin.RangerAdminClient;
import io.datadynamics.ranger.client.model.RangerJson;
import io.datadynamics.ranger.client.model.RangerPolicy;
import io.datadynamics.ranger.client.model.RangerRole;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Declarative policy management: {@link #plan} diffs spec files against the server, {@link #apply}
 * executes the plan, {@link #export} turns current policies back into a spec.
 */
public final class PolicyAsCode {
    private static final int PAGE = 500;
    private final RangerAdminClient admin;

    public PolicyAsCode(RangerAdminClient admin) {
        this.admin = admin;
    }

    public Plan plan(List<PolicySet> specs) {
        return plan(specs, null);
    }

    /** @param prune overrides the specs' {@code prune} setting when not null */
    public Plan plan(List<PolicySet> specs, Boolean prune) {
        List<Change> changes = new ArrayList<>();
        Map<List<String>, List<PolicySet>> scopes = new LinkedHashMap<>();
        Map<List<String>, List<RangerPolicy>> desiredByScope = new LinkedHashMap<>();
        for (PolicySet ps : specs) {
            for (RangerPolicy p : ps.policies) {
                List<String> scope = Arrays.asList(p.getService(), blankToNull(p.getZoneName()));
                scopes.computeIfAbsent(scope, k -> new ArrayList<>()).add(ps);
                desiredByScope.computeIfAbsent(scope, k -> new ArrayList<>()).add(p);
            }
            if (ps.service != null && ps.policies.isEmpty()) {
                List<String> scope = Arrays.asList(ps.service, blankToNull(ps.zone));
                scopes.computeIfAbsent(scope, k -> new ArrayList<>()).add(ps);
                desiredByScope.computeIfAbsent(scope, k -> new ArrayList<>());
            }
        }
        for (Map.Entry<List<String>, List<RangerPolicy>> e : desiredByScope.entrySet()) {
            String service = e.getKey().get(0);
            String zone = e.getKey().get(1);
            PolicyFilter filter = PolicyFilter.service(service).zone(zone).limit(PAGE);
            Map<String, RangerPolicy> current = new TreeMap<>();
            admin.policies().stream(filter)
                    .filter(p -> Objects.equals(blankToNull(p.getZoneName()), zone))
                    .forEach(p -> current.put(p.getName(), p));
            Set<String> seen = new HashSet<>();
            for (RangerPolicy desired : e.getValue()) {
                seen.add(desired.getName());
                RangerPolicy cur = current.get(desired.getName());
                if (cur == null) {
                    changes.add(new Change(Change.Action.CREATE, Change.Kind.POLICY, desired.getName(), service, zone, desired, null,
                            diff(Map.of(), Normalizer.policy(desired))));
                } else {
                    List<Change.FieldDiff> d = diff(Normalizer.policy(cur), Normalizer.policy(desired));
                    changes.add(new Change(d.isEmpty() ? Change.Action.NOOP : Change.Action.UPDATE, Change.Kind.POLICY,
                            desired.getName(), service, zone, desired, cur, d));
                }
            }
            List<PolicySet> sets = scopes.get(e.getKey());
            boolean doPrune = prune != null ? prune : sets.stream().anyMatch(s -> s.prune);
            Set<String> labels = sets.stream().map(s -> s.managedLabel).filter(Objects::nonNull).collect(Collectors.toSet());
            if (doPrune) {
                for (RangerPolicy cur : current.values()) {
                    if (seen.contains(cur.getName())) {
                        continue;
                    }
                    if (!labels.isEmpty() && cur.getPolicyLabels().stream().noneMatch(labels::contains)) {
                        continue; // not managed by these specs
                    }
                    changes.add(new Change(Change.Action.DELETE, Change.Kind.POLICY, cur.getName(), service, zone, null, cur, null));
                }
            }
        }
        for (PolicySet ps : specs) {
            for (RangerRole role : ps.roles) {
                Optional<RangerRole> cur = admin.roles().find(role.getName());
                if (cur.isEmpty()) {
                    changes.add(new Change(Change.Action.CREATE, Change.Kind.ROLE, role.getName(), null, null, role, null,
                            diff(Map.of(), Normalizer.role(role))));
                } else {
                    List<Change.FieldDiff> d = diff(Normalizer.role(cur.get()), Normalizer.role(role));
                    changes.add(new Change(d.isEmpty() ? Change.Action.NOOP : Change.Action.UPDATE, Change.Kind.ROLE, role.getName(),
                            null, null, role, cur.get(), d));
                }
            }
        }
        return new Plan(changes);
    }

    /**
     * Execute a plan. Updates carry the version seen at plan time, so a policy changed on the server in
     * between fails with {@code RangerConflictException}.
     */
    public List<Change> apply(Plan plan) {
        List<Change> done = new ArrayList<>();
        for (Change c : plan.pending()) {
            if (c.kind == Change.Kind.POLICY) {
                RangerPolicy desired = (RangerPolicy) c.desired;
                RangerPolicy current = (RangerPolicy) c.current;
                switch (c.action) {
                    case CREATE:
                        admin.policies().create(desired);
                        break;
                    case UPDATE:
                        admin.policies().update(desired.toBuilder().id(current.getId()).guid(current.getGuid())
                                .version(current.getVersion()).build(), true);
                        break;
                    case DELETE:
                        admin.policies().delete(current.getId());
                        break;
                    default:
                        break;
                }
            } else {
                RangerRole desired = (RangerRole) c.desired;
                if (c.action == Change.Action.CREATE) {
                    admin.roles().create(desired);
                } else if (c.action == Change.Action.UPDATE) {
                    admin.roles().update(desired.toBuilder().id(((RangerRole) c.current).getId()).build());
                }
            }
            done.add(c);
        }
        return done;
    }

    /** The server's current policies of a service as a spec document. */
    public Map<String, Object> export(String service, String zone) {
        List<Map<String, Object>> policies = admin.policies().stream(PolicyFilter.service(service).zone(zone).limit(PAGE))
                .map(PolicyAsCode::toSpec)
                .sorted(Comparator.comparing(m -> String.valueOf(m.get("name"))))
                .collect(Collectors.toList());
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("service", service);
        if (zone != null) {
            doc.put("zone", zone);
        }
        doc.put("policies", policies);
        return doc;
    }

    public static String toYaml(Map<String, Object> doc) {
        try {
            return YAMLMapper.builder().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES).build().writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> toSpec(RangerPolicy p) {
        Map<String, Object> raw = RangerJson.convert(p, Map.class);
        for (String k : new String[] {"id", "guid", "version", "createTime", "updateTime", "createdBy", "updatedBy",
                "resourceSignature", "service", "serviceType", "zoneName"}) {
            raw.remove(k);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", raw.remove("name"));
        Object desc = raw.remove("description");
        if (desc != null && !"".equals(desc)) {
            out.put("description", desc);
        }
        if (Boolean.FALSE.equals(raw.remove("isEnabled"))) {
            out.put("enabled", false);
        }
        if (Boolean.FALSE.equals(raw.remove("isAuditEnabled"))) {
            out.put("audit", false);
        }
        dropIf(raw, "policyType", 0);
        dropIf(raw, "policyPriority", 0);
        dropIf(raw, "isDenyAllElse", false);
        Map<String, Object> resources = new LinkedHashMap<>();
        Object res = raw.remove("resources");
        if (res instanceof Map) {
            ((Map<String, Object>) res).forEach((k, v) -> resources.put(k, compactResource((Map<String, Object>) v)));
        }
        out.put("resources", resources);
        for (String[] kv : new String[][] {{"policyItems", "allow"}, {"denyPolicyItems", "deny"}}) {
            Object items = raw.remove(kv[0]);
            if (items instanceof List && !((List<?>) items).isEmpty()) {
                out.put(kv[1], compactItems((List<Map<String, Object>>) items));
            }
        }
        for (String k : new String[] {"allowExceptions", "denyExceptions", "dataMaskPolicyItems", "rowFilterPolicyItems"}) {
            Object items = raw.remove(k);
            if (items instanceof List && !((List<?>) items).isEmpty()) {
                out.put(k, compactItems((List<Map<String, Object>>) items));
            }
        }
        Object labels = raw.remove("policyLabels");
        if (labels instanceof List && !((List<?>) labels).isEmpty()) {
            out.put("labels", labels);
        }
        raw.forEach((k, v) -> {
            if (v != null && !(v instanceof List && ((List<?>) v).isEmpty()) && !(v instanceof Map && ((Map<?, ?>) v).isEmpty())) {
                out.put(k, v);
            }
        });
        return out;
    }

    private static void dropIf(Map<String, Object> m, String key, Object dflt) {
        Object v = m.get(key);
        if (v == null || dflt.equals(v)) {
            m.remove(key);
        }
    }

    private static Object compactResource(Map<String, Object> r) {
        if (Boolean.TRUE.equals(r.get("isExcludes")) || Boolean.TRUE.equals(r.get("isRecursive"))) {
            Map<String, Object> out = new LinkedHashMap<>();
            r.forEach((k, v) -> {
                if (v != null && !Boolean.FALSE.equals(v)) {
                    out.put(k, v);
                }
            });
            return out;
        }
        List<?> values = r.get("values") instanceof List ? (List<?>) r.get("values") : List.of();
        return values.size() == 1 ? values.get(0) : values;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> compactItems(List<Map<String, Object>> items) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Map<String, Object> o = new LinkedHashMap<>();
            item.forEach((k, v) -> {
                if (v != null && !Boolean.FALSE.equals(v) && !(v instanceof List && ((List<?>) v).isEmpty())) {
                    o.put(k, v);
                }
            });
            List<Map<String, Object>> acc = (List<Map<String, Object>>) item.getOrDefault("accesses", List.of());
            if (acc.stream().allMatch(a -> !Boolean.FALSE.equals(a.get("isAllowed")))) {
                o.put("accesses", acc.stream().map(a -> a.get("type")).collect(Collectors.toList()));
            }
            out.add(o);
        }
        return out;
    }

    private static List<Change.FieldDiff> diff(Map<String, JsonNode> before, Map<String, JsonNode> after) {
        List<Change.FieldDiff> out = new ArrayList<>();
        after.forEach((k, v) -> {
            JsonNode b = before.get(k);
            if (!Objects.equals(b, v)) {
                out.add(new Change.FieldDiff(k, b, v));
            }
        });
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
