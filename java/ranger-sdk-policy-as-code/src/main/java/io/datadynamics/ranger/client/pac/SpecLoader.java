package io.datadynamics.ranger.client.pac;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.datadynamics.ranger.client.model.RangerJson;
import io.datadynamics.ranger.client.model.RangerPolicy;
import io.datadynamics.ranger.client.model.RangerRole;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Loads policy-as-code specs (same format as the Python SDK).
 *
 * <pre>
 * service: cm_hive          # required for policies
 * zone: finance             # optional
 * prune: false              # delete server policies missing from the spec
 * managed_label: gitops     # optional: label applied policies; prune only labelled ones
 * policies:
 *   - name: sales_read
 *     resources: {database: sales, table: "*"}
 *     allow: [{groups: [analyst], accesses: [select]}]
 * roles:
 *   - {name: analysts, users: [alice]}
 * </pre>
 */
public final class SpecLoader {
    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Set<String> TOP_LEVEL = Set.of("service", "zone", "prune", "managed_label", "policies", "roles", "apiVersion", "kind");
    private static final Map<String, String> SHORT = Map.of(
            "enabled", "isEnabled", "labels", "policyLabels", "audit", "isAuditEnabled", "priority", "policyPriority",
            "allow", "policyItems", "deny", "denyPolicyItems");
    private static final Set<String> ITEM_LISTS = Set.of("policyItems", "denyPolicyItems", "allowExceptions", "denyExceptions",
            "dataMaskPolicyItems", "rowFilterPolicyItems");

    private SpecLoader() {
    }

    /** Load files and directories (walked recursively, *.yaml / *.yml, sorted). */
    public static List<PolicySet> load(Collection<Path> paths) {
        List<Path> files = new ArrayList<>();
        for (Path p : paths) {
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.walk(p)) {
                    files.addAll(s.filter(f -> f.toString().endsWith(".yaml") || f.toString().endsWith(".yml"))
                            .sorted().collect(Collectors.toList()));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else {
                files.add(p);
            }
        }
        List<PolicySet> out = new ArrayList<>();
        for (Path f : files) {
            try (MappingIterator<Object> docs = YAML.readerFor(Object.class).readValues(f.toFile())) {
                int i = 0;
                while (docs.hasNext()) {
                    Object doc = docs.next();
                    if (doc != null) {
                        out.add(parse(doc, i == 0 ? f.toString() : f + "#" + i));
                    }
                    i++;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }

    public static PolicySet parseYaml(String yaml, String source) {
        try {
            return parse(YAML.readValue(yaml, Object.class), source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static PolicySet parse(Object raw, String source) {
        if (!(raw instanceof Map)) {
            throw new IllegalArgumentException(source + ": a spec document must be a mapping");
        }
        Map<String, Object> doc = (Map<String, Object>) raw;
        Set<String> unknown = new TreeSet<>(doc.keySet());
        unknown.removeAll(TOP_LEVEL);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(source + ": unknown top-level keys " + unknown);
        }
        String service = (String) doc.get("service");
        String zone = (String) doc.get("zone");
        String label = (String) doc.get("managed_label");
        boolean prune = Boolean.TRUE.equals(doc.get("prune"));
        List<RangerPolicy> policies = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        Set<String> dups = new TreeSet<>();
        for (Object p : (List<Object>) doc.getOrDefault("policies", List.of())) {
            RangerPolicy policy = policy((Map<String, Object>) p, service, zone, label, source);
            if (!names.add(policy.getName())) {
                dups.add(policy.getName());
            }
            policies.add(policy);
        }
        if (!dups.isEmpty()) {
            throw new IllegalArgumentException(source + ": duplicate policy names " + dups);
        }
        List<RangerRole> roles = new ArrayList<>();
        for (Object r : (List<Object>) doc.getOrDefault("roles", List.of())) {
            roles.add(role((Map<String, Object>) r));
        }
        return new PolicySet(service, zone, prune, label, policies, roles, source);
    }

    @SuppressWarnings("unchecked")
    static RangerPolicy policy(Map<String, Object> raw, String service, String zone, String label, String source) {
        Map<String, Object> p = new LinkedHashMap<>();
        raw.forEach((k, v) -> p.put(SHORT.getOrDefault(camel(k), camel(k)), v));
        p.putIfAbsent("service", service);
        if (zone != null) {
            p.putIfAbsent("zoneName", zone);
        }
        if (p.get("service") == null) {
            throw new IllegalArgumentException(source + ": policy '" + raw.get("name") + "' has no service");
        }
        if (p.get("name") == null) {
            throw new IllegalArgumentException(source + ": a policy is missing 'name'");
        }
        if (p.get("resources") instanceof Map) {
            p.put("resources", resources((Map<String, Object>) p.get("resources")));
        }
        if (p.get("additionalResources") instanceof List) {
            List<Object> list = new ArrayList<>();
            for (Object m : (List<Object>) p.get("additionalResources")) {
                list.add(resources((Map<String, Object>) m));
            }
            p.put("additionalResources", list);
        }
        for (String k : ITEM_LISTS) {
            if (p.get(k) instanceof List) {
                List<Object> items = new ArrayList<>();
                for (Object i : (List<Object>) p.get(k)) {
                    items.add(item((Map<String, Object>) i));
                }
                p.put(k, items);
            }
        }
        if (p.get("conditions") instanceof Map) {
            p.put("conditions", conditions((Map<String, Object>) p.get("conditions")));
        }
        RangerPolicy policy = RangerJson.convert(p, RangerPolicy.class);
        if (label != null && !policy.getPolicyLabels().contains(label)) {
            policy = policy.toBuilder().label(label).build();
        }
        return policy;
    }

    @SuppressWarnings("unchecked")
    static RangerRole role(Map<String, Object> raw) {
        Map<String, Object> r = new LinkedHashMap<>();
        raw.forEach((k, v) -> r.put(camel(k), v));
        for (String k : new String[] {"users", "groups", "roles"}) {
            if (r.get(k) instanceof List) {
                List<Object> members = new ArrayList<>();
                for (Object m : (List<Object>) r.get(k)) {
                    Map<String, Object> mm = m instanceof Map ? camelKeys((Map<String, Object>) m) : new LinkedHashMap<>(Map.of("name", m));
                    mm.putIfAbsent("isAdmin", false);
                    members.add(mm);
                }
                r.put(k, members);
            }
        }
        return RangerJson.convert(r, RangerRole.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resources(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        in.forEach((k, v) -> {
            if (v instanceof String) {
                out.put(k, Map.of("values", List.of(v)));
            } else if (v instanceof List) {
                out.put(k, Map.of("values", v));
            } else if (v instanceof Map) {
                out.put(k, camelKeys((Map<String, Object>) v));
            } else {
                out.put(k, v);
            }
        });
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> item(Map<String, Object> raw) {
        Map<String, Object> i = camelKeys(raw);
        if (i.get("accesses") instanceof List) {
            List<Object> acc = new ArrayList<>();
            for (Object a : (List<Object>) i.get("accesses")) {
                Map<String, Object> m = a instanceof Map ? camelKeys((Map<String, Object>) a) : new LinkedHashMap<>(Map.of("type", a));
                m.putIfAbsent("isAllowed", true);
                acc.add(m);
            }
            i.put("accesses", acc);
        }
        if (i.get("conditions") instanceof Map) {
            i.put("conditions", conditions((Map<String, Object>) i.get("conditions")));
        }
        return i;
    }

    private static List<Object> conditions(Map<String, Object> m) {
        List<Object> out = new ArrayList<>();
        m.forEach((type, v) -> out.add(Map.of("type", type, "values", v instanceof List ? v : List.of(v))));
        return out;
    }

    private static Map<String, Object> camelKeys(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(camel(k), v));
        return out;
    }

    static String camel(String key) {
        if (key.indexOf('_') < 0) {
            return key;
        }
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : key.toCharArray()) {
            if (c == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return sb.toString();
    }
}
