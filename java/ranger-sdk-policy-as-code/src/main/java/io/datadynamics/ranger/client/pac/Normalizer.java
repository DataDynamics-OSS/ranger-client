package io.datadynamics.ranger.client.pac;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.datadynamics.ranger.client.model.RangerJson;
import io.datadynamics.ranger.client.model.RangerPolicy;
import io.datadynamics.ranger.client.model.RangerRole;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The semantically compared view of policies and roles: server bookkeeping stripped, defaults applied,
 * unordered lists sorted. Mirrors {@code ranger_sdk.pac.normalize_policy} (checked by spec/contract/pac.yaml).
 */
public final class Normalizer {
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    static final List<String> COMPARED = List.of("description", "isEnabled", "isAuditEnabled", "policyType", "policyPriority",
            "isDenyAllElse", "resources", "additionalResources", "conditions", "policyItems", "denyPolicyItems", "allowExceptions",
            "denyExceptions", "dataMaskPolicyItems", "rowFilterPolicyItems", "policyLabels", "validitySchedules");
    private static final Map<String, JsonNode> DEFAULTS = Map.of(
            "isEnabled", BooleanNode.TRUE, "isAuditEnabled", BooleanNode.TRUE, "policyType", IntNode.valueOf(0),
            "policyPriority", IntNode.valueOf(0), "isDenyAllElse", BooleanNode.FALSE, "description", TextNode.valueOf(""));
    private static final Set<String> UNORDERED = Set.of("policyItems", "denyPolicyItems", "allowExceptions", "denyExceptions");

    private Normalizer() {
    }

    public static Map<String, JsonNode> policy(RangerPolicy p) {
        JsonNode raw = RangerJson.tree(p);
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (String key : COMPARED) {
            JsonNode v = raw.get(key);
            if (isEmpty(v) && DEFAULTS.containsKey(key)) {
                v = DEFAULTS.get(key);
            }
            switch (key) {
                case "resources":
                    v = resources(v);
                    break;
                case "additionalResources": {
                    ArrayNode arr = F.arrayNode();
                    if (v != null && v.isArray()) {
                        v.forEach(m -> arr.add(resources(m)));
                    }
                    v = arr;
                    break;
                }
                case "policyItems":
                case "denyPolicyItems":
                case "allowExceptions":
                case "denyExceptions":
                case "dataMaskPolicyItems":
                case "rowFilterPolicyItems": {
                    List<JsonNode> items = new ArrayList<>();
                    if (v != null && v.isArray()) {
                        v.forEach(i -> items.add(item(i)));
                    }
                    if (UNORDERED.contains(key)) {
                        items.sort(Comparator.comparing(Normalizer::canonical));
                    }
                    ArrayNode arr = F.arrayNode();
                    items.forEach(arr::add);
                    v = arr;
                    break;
                }
                case "policyLabels":
                    v = sortedStrings(v);
                    break;
                case "conditions":
                case "validitySchedules":
                    v = v == null || v.isNull() ? F.arrayNode() : v;
                    break;
                default:
                    v = v == null ? NullNode.instance : v;
            }
            out.put(key, v);
        }
        return out;
    }

    public static Map<String, JsonNode> role(RangerRole r) {
        JsonNode raw = RangerJson.tree(r);
        Map<String, JsonNode> out = new LinkedHashMap<>();
        JsonNode d = raw.get("description");
        out.put("description", isEmpty(d) ? TextNode.valueOf("") : d);
        for (String k : new String[] {"users", "groups", "roles"}) {
            List<ObjectNode> members = new ArrayList<>();
            JsonNode list = raw.get(k);
            if (list != null && list.isArray()) {
                for (JsonNode m : list) {
                    ObjectNode o = F.objectNode();
                    o.set("name", m.get("name"));
                    o.put("isAdmin", m.path("isAdmin").asBoolean(false));
                    members.add(o);
                }
            }
            members.sort(Comparator.comparing(m -> m.get("name").asText()));
            ArrayNode arr = F.arrayNode();
            members.forEach(arr::add);
            out.put(k, arr);
        }
        return out;
    }

    private static boolean isEmpty(JsonNode v) {
        return v == null || v.isNull() || (v.isContainerNode() && v.size() == 0);
    }

    private static ObjectNode resources(JsonNode v) {
        TreeMap<String, JsonNode> sorted = new TreeMap<>();
        if (v != null && v.isObject()) {
            v.fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue()));
        }
        ObjectNode out = F.objectNode();
        sorted.forEach((k, r) -> {
            ObjectNode n = F.objectNode();
            n.set("values", sortedStrings(r.get("values")));
            n.put("isExcludes", r.path("isExcludes").asBoolean(false));
            n.put("isRecursive", r.path("isRecursive").asBoolean(false));
            out.set(k, n);
        });
        return out;
    }

    private static JsonNode item(JsonNode raw) {
        ObjectNode out = raw.isObject() ? ((ObjectNode) raw).deepCopy() : F.objectNode();
        for (String k : new String[] {"users", "groups", "roles"}) {
            out.set(k, sortedStrings(out.get(k)));
        }
        List<ObjectNode> acc = new ArrayList<>();
        out.path("accesses").forEach(a -> {
            ObjectNode o = F.objectNode();
            o.set("type", a.get("type"));
            o.put("isAllowed", a.path("isAllowed").asBoolean(true));
            acc.add(o);
        });
        acc.sort(Comparator.comparing(a -> a.get("type").asText()));
        ArrayNode accArr = F.arrayNode();
        acc.forEach(accArr::add);
        out.set("accesses", accArr);
        List<ObjectNode> conds = new ArrayList<>();
        out.path("conditions").forEach(c -> {
            ObjectNode o = F.objectNode();
            o.set("type", c.get("type"));
            o.set("values", sortedStrings(c.get("values")));
            conds.add(o);
        });
        conds.sort(Comparator.comparing(c -> c.get("type").asText()));
        ArrayNode condArr = F.arrayNode();
        conds.forEach(condArr::add);
        out.set("conditions", condArr);
        out.put("delegateAdmin", out.path("delegateAdmin").asBoolean(false));
        return out;
    }

    private static ArrayNode sortedStrings(JsonNode v) {
        List<String> list = new ArrayList<>();
        if (v != null && v.isArray()) {
            v.forEach(x -> list.add(x.asText()));
        }
        list.sort(Comparator.naturalOrder());
        ArrayNode arr = F.arrayNode();
        list.forEach(arr::add);
        return arr;
    }

    /** JSON with object keys sorted, for order-independent comparison of items. */
    static String canonical(JsonNode n) {
        if (n.isObject()) {
            TreeMap<String, String> m = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                m.put(e.getKey(), canonical(e.getValue()));
            }
            StringBuilder sb = new StringBuilder("{");
            m.forEach((k, v) -> sb.append(TextNode.valueOf(k)).append(':').append(v).append(','));
            return sb.append('}').toString();
        }
        if (n.isArray()) {
            StringBuilder sb = new StringBuilder("[");
            n.forEach(x -> sb.append(canonical(x)).append(','));
            return sb.append(']').toString();
        }
        return n.toString();
    }
}
