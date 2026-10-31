package io.datadynamics.ranger.demo.web.pdp;

import io.datadynamics.ranger.client.model.AccessInfo;
import io.datadynamics.ranger.client.model.PermissionResult;
import io.datadynamics.ranger.client.model.ResourceInfo;
import io.datadynamics.ranger.client.model.ResourcePermissions;
import io.datadynamics.ranger.client.model.UserInfo;
import io.datadynamics.ranger.client.pdp.RangerPdpClient;
import io.datadynamics.ranger.demo.trace.SdkCall;
import io.datadynamics.ranger.demo.trace.SdkCalls;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** PDP Playground: decisions, multi-decisions, resource permissions and the client-side decision cache. */
@Controller
@RequestMapping("/pdp")
public class PdpController {
    /** Seed scenarios: user, resource, access, what to expect. */
    public record Preset(String user, String resource, String access, String expect) {
    }

    static final List<Preset> PRESETS = List.of(
            new Preset("alice", "database=sales, table=orders", "select", "ALLOW + 행 필터 region = 'APAC' (역할 analysts, 그룹 analyst)"),
            new Preset("mallory", "database=sales, table=orders", "select", "DENY (sales_read의 거부 항목)"),
            new Preset("alice", "database=sales, table=customers, column=email", "select", "ALLOW + 마스킹 MASK_HASH"),
            new Preset("bob", "database=finance, table=x", "alter", "ALLOW (그룹 engineer, finance_admin)"),
            new Preset("alice", "database=finance, table=x", "select", "DENY (맞는 정책 없음: policy id -1)"));

    /** One principal's row in the resource-permissions table. */
    public record PrincipalRow(String type, String name, List<String> allowed, List<String> denied) {
    }

    private final RangerPdpClient pdp;
    private final CachedPdpClient cached;
    private final SdkCalls calls;

    public PdpController(RangerPdpClient pdp, CachedPdpClient cached, SdkCalls calls) {
        this.pdp = pdp;
        this.cached = cached;
        this.calls = calls;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("presets", PRESETS);
        model.addAttribute("service", pdp.context().getServiceName());
        model.addAttribute("serviceType", pdp.context().getServiceType());
        model.addAttribute("ttl", CachedPdpClient.TTL.toSeconds());
        return "pdp/pdp";
    }

    @PostMapping("/authorize")
    public String authorize(@RequestParam String user, @RequestParam(defaultValue = "") String groups,
                            @RequestParam(defaultValue = "") String roles, @RequestParam String resource,
                            @RequestParam String access, Model model) {
        List<String> g = Kv.list(groups);
        List<String> r = Kv.list(roles);
        List<String> perms = Kv.list(access);
        SdkCall call;
        String res = "ResourceInfo.of(" + safeArgs(resource) + ")";
        if (g.isEmpty() && r.isEmpty()) {
            call = calls.run("pdp.authorize(" + Kv.str(user) + ", " + res + ", " + Kv.args(perms) + ")",
                    () -> pdp.authorize(user, ResourceInfo.of(Kv.flat(Kv.map(resource))), perms.toArray(new String[0])));
        } else {
            String userInfo = "UserInfo.builder().name(" + Kv.str(user) + ")"
                    + (g.isEmpty() ? "" : ".groups(" + Kv.listOf(g) + ")")
                    + (r.isEmpty() ? "" : ".roles(" + Kv.listOf(r) + ")") + ".build()";
            call = calls.run("pdp.authorize(pdp.request(" + userInfo + ", " + res + ", " + Kv.args(perms) + "))",
                    () -> pdp.authorize(pdp.request(userInfo(user, g, r), ResourceInfo.of(Kv.flat(Kv.map(resource))),
                            perms.toArray(new String[0]))));
        }
        model.addAttribute("call", call);
        return "pdp/pdp :: authzResult";
    }

    @PostMapping("/multi")
    public String multi(@RequestParam String user, @RequestParam String accesses, Model model) {
        StringBuilder code = new StringBuilder("pdp.authorizeMulti(UserInfo.builder().name(" + Kv.str(user) + ").build(), List.of(");
        List<String> lines = accesses.lines().map(String::trim).filter(l -> !l.isEmpty()).toList();
        for (int i = 0; i < lines.size(); i++) {
            code.append(i == 0 ? "\n    " : ",\n    ");
            try {
                String[] parts = split(lines.get(i));
                code.append("AccessInfo.builder().resource(ResourceInfo.of(").append(safeArgs(parts[0]))
                        .append(")).permissions(").append(Kv.listOf(Kv.list(parts[1]))).append(").build()");
            } catch (IllegalArgumentException e) {
                code.append("/* ").append(lines.get(i)).append(" */");  // reported by the call below
            }
        }
        code.append("))");
        SdkCall call = calls.run(code.toString(), () -> {
            List<AccessInfo> list = new ArrayList<>();
            for (String line : lines) {
                String[] parts = split(line);
                list.add(AccessInfo.builder().resource(ResourceInfo.of(Kv.flat(Kv.map(parts[0]))))
                        .permissions(Kv.list(parts[1])).build());
            }
            return pdp.authorizeMulti(UserInfo.builder().name(user).build(), list);
        });
        model.addAttribute("call", call);
        model.addAttribute("lines", lines);
        return "pdp/pdp :: multiResult";
    }

    @PostMapping("/permissions")
    public String permissions(@RequestParam String resource, Model model) {
        SdkCall call = calls.run("pdp.permissions(ResourceInfo.of(" + safeArgs(resource) + "))",
                () -> pdp.permissions(ResourceInfo.of(Kv.flat(Kv.map(resource)))));
        model.addAttribute("call", call);
        model.addAttribute("resultOpen", false);
        if (call.ok()) {
            ResourcePermissions p = call.value();
            List<PrincipalRow> rows = new ArrayList<>();
            rows(rows, "user", p.getUsers());
            rows(rows, "group", p.getGroups());
            rows(rows, "role", p.getRoles());
            model.addAttribute("rows", rows);
        }
        return "pdp/pdp :: permissionsResult";
    }

    /** The same decision through the plain client once and through the caching client twice. */
    @PostMapping("/cache")
    public String cache(@RequestParam String user, @RequestParam String resource, @RequestParam String access, Model model) {
        String args = Kv.str(user) + ", ResourceInfo.of(" + safeArgs(resource) + "), " + Kv.args(Kv.list(access));
        String[] perms = Kv.list(access).toArray(new String[0]);
        List<SdkCall> list = new ArrayList<>();
        list.add(calls.run("pdp.authorize(" + args + ")  // starter bean, no cache",
                () -> pdp.authorize(user, ResourceInfo.of(Kv.flat(Kv.map(resource))), perms)));
        for (int i = 1; i <= 2; i++) {
            int n = i;
            list.add(calls.run("cachedPdp.authorize(" + args + ")  // call " + n + ", .cache(Duration.ofSeconds(30), 1000)",
                    () -> cached.get().authorize(user, ResourceInfo.of(Kv.flat(Kv.map(resource))), perms)));
        }
        model.addAttribute("cacheCalls", list);
        model.addAttribute("resultOpen", false);
        return "pdp/pdp :: cacheResult";
    }

    @PostMapping("/cache/clear")
    public String clearCache(Model model) {
        model.addAttribute("call", calls.runVoid("cachedPdp.clearCache()", () -> cached.get().clearCache()));
        return "fragments/call :: call";
    }

    private static UserInfo userInfo(String user, List<String> groups, List<String> roles) {
        UserInfo.Builder b = UserInfo.builder().name(user);
        if (!groups.isEmpty()) {
            b.groups(groups);
        }
        if (!roles.isEmpty()) {
            b.roles(roles);
        }
        return b.build();
    }

    private static void rows(List<PrincipalRow> rows, String type, Map<String, Map<String, PermissionResult>> byPrincipal) {
        if (byPrincipal == null) {
            return;
        }
        new TreeMap<>(byPrincipal).forEach((name, perms) -> {
            List<String> allowed = new ArrayList<>();
            List<String> denied = new ArrayList<>();
            new TreeMap<>(perms).forEach((perm, r) -> {
                boolean ok = r.getAccess() != null && r.getAccess().isAllowed();
                (ok ? allowed : denied).add(perm);
            });
            rows.add(new PrincipalRow(type, name, allowed, denied));
        });
    }

    /** "resource | access" line of the multi form. */
    private static String[] split(String line) {
        int bar = line.lastIndexOf('|');
        if (bar < 0) {
            throw new IllegalArgumentException("Expected 'key=value,... | access,...' but got '" + line + "'");
        }
        return new String[] {line.substring(0, bar).trim(), line.substring(bar + 1).trim()};
    }

    /** Resource arguments for the code string; a malformed field is reported by the call itself. */
    private static String safeArgs(String resource) {
        try {
            return Kv.args(Kv.map(resource));
        } catch (IllegalArgumentException e) {
            return Kv.str(resource);
        }
    }
}
