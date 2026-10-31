package io.datadynamics.ranger.demo.web.policies;

import io.datadynamics.ranger.client.admin.Page;
import io.datadynamics.ranger.client.admin.PoliciesClient.ItemKind;
import io.datadynamics.ranger.client.admin.PolicyFilter;
import io.datadynamics.ranger.client.admin.RangerAdminClient;
import io.datadynamics.ranger.client.model.PolicyItem;
import io.datadynamics.ranger.client.model.RangerJson;
import io.datadynamics.ranger.client.model.RangerPolicy;
import io.datadynamics.ranger.demo.trace.SdkCall;
import io.datadynamics.ranger.demo.trace.SdkCalls;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.datadynamics.ranger.demo.web.support.Inputs.args;
import static io.datadynamics.ranger.demo.web.support.Inputs.blank;
import static io.datadynamics.ranger.demo.web.support.Inputs.csv;
import static io.datadynamics.ranger.demo.web.support.Inputs.listOf;
import static io.datadynamics.ranger.demo.web.support.Inputs.mapOf;
import static io.datadynamics.ranger.demo.web.support.Inputs.pairs;
import static io.datadynamics.ranger.demo.web.support.Inputs.q;
import static io.datadynamics.ranger.demo.web.support.Inputs.refresh;
import static io.datadynamics.ranger.demo.web.support.Inputs.resources;
import static io.datadynamics.ranger.demo.web.support.Inputs.setOf;

/** Policy search, detail, JSON edit, the simple policy builder, item operations, lookups and bulk export/import. */
@Controller
@RequestMapping("/policies")
public class PoliciesController {
    private final RangerAdminClient admin;
    private final SdkCalls calls;

    public PoliciesController(RangerAdminClient admin, SdkCalls calls) {
        this.admin = admin;
        this.calls = calls;
    }

    /** Search form values (also used to rebuild the paging links). */
    public record Search(String service, String name, String zone, String user, String group, String role, String label,
                         String resType, String resValue, String enabled, Integer policyType, int start, int limit) {
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "demo_hive") String service, @RequestParam(required = false) String name,
                       @RequestParam(required = false) String zone, @RequestParam(required = false) String user,
                       @RequestParam(required = false) String group, @RequestParam(required = false) String role,
                       @RequestParam(required = false) String label, @RequestParam(required = false) String resType,
                       @RequestParam(required = false) String resValue, @RequestParam(required = false) String enabled,
                       @RequestParam(required = false) Integer policyType,
                       @RequestParam(defaultValue = "0") int start, @RequestParam(defaultValue = "10") int limit, Model model) {
        Search s = new Search(blank(service), blank(name), blank(zone), blank(user), blank(group), blank(role), blank(label),
                blank(resType), blank(resValue), blank(enabled), policyType, start, limit);
        StringBuilder code = new StringBuilder(s.service() == null ? "PolicyFilter.all()" : "PolicyFilter.service(" + q(s.service()) + ")");
        PolicyFilter f = s.service() == null ? PolicyFilter.all() : PolicyFilter.service(s.service());
        if (s.name() != null) { f.name(s.name()); code.append(".name(").append(q(s.name())).append(")"); }
        if (s.zone() != null) { f.zone(s.zone()); code.append(".zone(").append(q(s.zone())).append(")"); }
        if (s.user() != null) { f.user(s.user()); code.append(".user(").append(q(s.user())).append(")"); }
        if (s.group() != null) { f.group(s.group()); code.append(".group(").append(q(s.group())).append(")"); }
        if (s.role() != null) { f.role(s.role()); code.append(".role(").append(q(s.role())).append(")"); }
        if (s.label() != null) { f.label(s.label()); code.append(".label(").append(q(s.label())).append(")"); }
        if (s.resType() != null && s.resValue() != null) {
            f.resource(s.resType(), s.resValue());
            code.append(".resource(").append(q(s.resType())).append(", ").append(q(s.resValue())).append(")");
        }
        if (s.enabled() != null) {
            boolean en = Boolean.parseBoolean(s.enabled());
            f.enabled(en);
            code.append(".enabled(").append(en).append(")");
        }
        if (policyType != null) { f.policyType(policyType); code.append(".policyType(").append(policyType).append(")"); }
        f.start(start).limit(limit);
        code.append(".start(").append(start).append(").limit(").append(limit).append(")");

        SdkCall call = calls.run("admin.policies().search(" + code + ")", () -> admin.policies().search(f));
        model.addAttribute("s", s);
        model.addAttribute("search", call);
        model.addAttribute("page", call.ok() ? call.<Page<RangerPolicy>>value() : null);
        model.addAttribute("resultOpen", false);
        model.addAttribute("self", ServletUriComponentsBuilder.fromCurrentRequest().toUriString());
        return "policies/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable long id, Model model) {
        SdkCall call = calls.run("admin.policies().get(" + id + "L)", () -> admin.policies().get(id));
        model.addAttribute("get", call);
        model.addAttribute("policy", call.ok() ? call.value() : null);
        model.addAttribute("resultOpen", false);
        return "policies/detail";
    }

    /** Save the JSON from the detail page with the chosen method. */
    @PostMapping("/{id}/save")
    public String save(@PathVariable long id, @RequestParam String json, @RequestParam String mode, Model model, HttpServletResponse response) {
        String var = "RangerPolicy policy = RangerJson.read(json, RangerPolicy.class);\n";
        SdkCall call = switch (mode) {
            case "update" -> calls.run(var + "admin.policies().update(policy)  // same as update(policy, true): version check",
                    () -> admin.policies().update(read(json)));
            case "update-nocheck" -> calls.run(var + "admin.policies().update(policy, false)  // last write wins",
                    () -> admin.policies().update(read(json), false));
            case "apply" -> calls.run(var + "admin.policies().apply(policy)  // upsert by service + name",
                    () -> admin.policies().apply(read(json)));
            case "server-apply" -> calls.run(var + "admin.policies().serverApply(policy)  // Ranger's /policy/apply merge",
                    () -> admin.policies().serverApply(read(json)));
            case "create" -> calls.run(var + "admin.policies().create(policy.toBuilder().id(null).guid(null).version(null).build())",
                    () -> admin.policies().create(read(json).toBuilder().id(null).guid(null).version(null).build()));
            default -> throw new IllegalArgumentException("mode " + mode);
        };
        return panel(model, response, call);
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable long id, Model model, HttpServletResponse response) {
        return panel(model, response, calls.runVoid("admin.policies().delete(" + id + "L)", () -> admin.policies().delete(id)));
    }

    @PostMapping("/delete-by-name")
    public String deleteByName(@RequestParam String service, @RequestParam String name, Model model, HttpServletResponse response) {
        return panel(model, response, calls.runVoid("admin.policies().deleteByName(" + q(service) + ", " + q(name) + ")",
                () -> admin.policies().deleteByName(service, name)));
    }

    /** The simple builder form: resources + one allow item + one deny item. */
    @PostMapping("/build")
    public String build(@RequestParam String service, @RequestParam String name, @RequestParam(required = false) String description,
                        @RequestParam String resources, @RequestParam(required = false) String allowUsers,
                        @RequestParam(required = false) String allowGroups, @RequestParam(required = false) String allowRoles,
                        @RequestParam(required = false) String allowAccesses, @RequestParam(required = false) String denyUsers,
                        @RequestParam(required = false) String denyGroups, @RequestParam(required = false) String denyAccesses,
                        @RequestParam(required = false) String label, @RequestParam String mode,
                        Model model, HttpServletResponse response) {
        StringBuilder code = new StringBuilder("RangerPolicy policy = RangerPolicy.builder()\n        .service(")
                .append(q(service)).append(").name(").append(q(name)).append(")");
        if (blank(description) != null) {
            code.append(".description(").append(q(description)).append(")");
        }
        for (Map.Entry<String, List<String>> r : safeResources(resources).entrySet()) {
            code.append("\n        .resource(").append(q(r.getKey())).append(", ").append(args(r.getValue())).append(")");
        }
        String allow = item(allowUsers, allowGroups, allowRoles, allowAccesses);
        if (allow != null) {
            code.append("\n        .allow(").append(allow).append(")");
        }
        String deny = item(denyUsers, denyGroups, null, denyAccesses);
        if (deny != null) {
            code.append("\n        .deny(").append(deny).append(")");
        }
        if (blank(label) != null) {
            code.append("\n        .label(").append(q(label)).append(")");
        }
        code.append("\n        .build();\n");
        String method = switch (mode) {
            case "create" -> "create";
            case "server-apply" -> "serverApply";
            default -> "apply";
        };
        code.append("admin.policies().").append(method).append("(policy)");

        SdkCall call = calls.run(code.toString(), () -> {
            RangerPolicy.Builder b = RangerPolicy.builder().service(service).name(name);
            if (blank(description) != null) {
                b.description(description);
            }
            resources(resources).forEach((k, v) -> b.resource(k, v.toArray(String[]::new)));
            PolicyItem a = buildItem(allowUsers, allowGroups, allowRoles, allowAccesses);
            if (a != null) {
                b.allow(a);
            }
            PolicyItem d = buildItem(denyUsers, denyGroups, null, denyAccesses);
            if (d != null) {
                b.deny(d);
            }
            if (blank(label) != null) {
                b.label(label);
            }
            RangerPolicy p = b.build();
            return switch (method) {
                case "create" -> admin.policies().create(p);
                case "serverApply" -> admin.policies().serverApply(p);
                default -> admin.policies().apply(p);
            };
        });
        return panel(model, response, call);
    }

    @PostMapping("/items/add")
    public String addItems(@RequestParam String service, @RequestParam String name, @RequestParam ItemKind kind,
                           @RequestParam(required = false) String users, @RequestParam(required = false) String groups,
                           @RequestParam(required = false) String roles, @RequestParam String accesses,
                           Model model, HttpServletResponse response) {
        String item = item(users, groups, roles, accesses);
        SdkCall call = calls.run("admin.policies().addItems(" + q(service) + ", " + q(name) + ", ItemKind." + kind
                        + ",\n        List.of(" + item + ".build()))",
                () -> admin.policies().addItems(service, name, kind, List.of(buildItem(users, groups, roles, accesses))));
        return panel(model, response, call);
    }

    @PostMapping("/items/remove")
    public String removePrincipals(@RequestParam String service, @RequestParam String name, @RequestParam ItemKind kind,
                                   @RequestParam(required = false) String users, @RequestParam(required = false) String groups,
                                   @RequestParam(required = false) String roles, Model model, HttpServletResponse response) {
        List<String> u = csv(users), g = csv(groups), r = csv(roles);
        SdkCall call = calls.run("admin.policies().removePrincipals(" + q(service) + ", " + q(name) + ", ItemKind." + kind
                        + ",\n        " + setOf(u) + ", " + setOf(g) + ", " + setOf(r) + ")",
                () -> admin.policies().removePrincipals(service, name, kind, new HashSet<>(u), new HashSet<>(g), new HashSet<>(r)));
        return panel(model, response, call);
    }

    @PostMapping("/for-resource")
    public String forResource(@RequestParam String serviceType, @RequestParam String service, @RequestParam String resource, Model model) {
        Map<String, String> res = new LinkedHashMap<>();
        safeResources(resource).forEach((k, v) -> res.put(k, String.join(",", v)));
        SdkCall call = calls.run("admin.policies().forResource(" + q(serviceType) + ", " + q(service) + ", "
                        + mapOf(res) + ")",
                () -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    resources(resource).forEach((k, v) -> m.put(k, String.join(",", v)));
                    return admin.policies().forResource(serviceType, service, m);
                });
        return panel(model, null, call);
    }

    @PostMapping("/for-user")
    public String forUser(@RequestParam String user, @RequestParam(required = false) String service, Model model) {
        String svc = blank(service);
        SdkCall call = calls.run("admin.policies().forUser(" + q(user) + ", " + q(svc) + ").toList()",
                () -> admin.policies().forUser(user, svc).toList());
        return panel(model, null, call);
    }

    @PostMapping("/export")
    public String export(@RequestParam String services, @RequestParam(required = false) String zone, Model model) {
        List<String> svcs = csv(services);
        String z = blank(zone);
        SdkCall call = calls.run("admin.policies().exportJson(" + listOf(svcs) + ", " + q(z) + ")",
                () -> admin.policies().exportJson(svcs, z));
        return panel(model, null, call);
    }

    @PostMapping("/import")
    public String importJson(@RequestParam String json, @RequestParam(defaultValue = "false") boolean override,
                             @RequestParam(required = false) String servicesMap, Model model, HttpServletResponse response) {
        Map<String, String> map = safePairs(servicesMap);
        String mapCode = map.isEmpty() ? "null" : mapOf(map);
        SdkCall call = calls.runVoid("Map<String, Object> data = RangerJson.read(json, Map.class);\n"
                        + "admin.policies().importJson(data, " + override + ", " + mapCode + ", null)",
                () -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = RangerJson.read(json, Map.class);
                    admin.policies().importJson(data, override, map.isEmpty() ? null : map, null);
                });
        return panel(model, response, call);
    }

    private String panel(Model model, HttpServletResponse response, SdkCall call) {
        if (response != null && call.ok()) {
            refresh(response);
        }
        model.addAttribute("call", call);
        return "fragments/call :: call";
    }

    private static RangerPolicy read(String json) {
        return RangerJson.read(json, RangerPolicy.class);
    }

    /** Builder source for one item, or null when it names nobody. */
    private static String item(String users, String groups, String roles, String accesses) {
        List<String> u = csv(users), g = csv(groups), r = csv(roles);
        if (u.isEmpty() && g.isEmpty() && r.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (!u.isEmpty()) parts.add("users(" + args(u) + ")");
        if (!g.isEmpty()) parts.add("groups(" + args(g) + ")");
        if (!r.isEmpty()) parts.add("roles(" + args(r) + ")");
        String first = "PolicyItem." + parts.get(0);
        StringBuilder sb = new StringBuilder(first);
        for (int i = 1; i < parts.size(); i++) {
            sb.append(".and").append(Character.toUpperCase(parts.get(i).charAt(0))).append(parts.get(i).substring(1));
        }
        return sb.append(".accesses(").append(args(csv(accesses))).append(")").toString();
    }

    private static PolicyItem buildItem(String users, String groups, String roles, String accesses) {
        List<String> u = csv(users), g = csv(groups), r = csv(roles);
        if (u.isEmpty() && g.isEmpty() && r.isEmpty()) {
            return null;
        }
        return PolicyItem.builder().users(u).groups(g).roles(r).accesses(csv(accesses).toArray(String[]::new)).build();
    }

    /** For code rendering only: bad input is reported by the call itself. */
    private static Map<String, List<String>> safeResources(String s) {
        try {
            return resources(s);
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
    }

    private static Map<String, String> safePairs(String s) {
        try {
            return pairs(s);
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
    }
}
