package io.datadynamics.ranger.demo.web.roles;

import io.datadynamics.ranger.client.admin.RangerAdminClient;
import io.datadynamics.ranger.client.model.GrantRevokeRoleRequest;
import io.datadynamics.ranger.client.model.RangerJson;
import io.datadynamics.ranger.client.model.RangerRole;
import io.datadynamics.ranger.client.model.RoleMember;
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
import java.util.List;
import java.util.stream.Collectors;

import static io.datadynamics.ranger.demo.web.support.Inputs.blank;
import static io.datadynamics.ranger.demo.web.support.Inputs.csv;
import static io.datadynamics.ranger.demo.web.support.Inputs.listOf;
import static io.datadynamics.ranger.demo.web.support.Inputs.q;
import static io.datadynamics.ranger.demo.web.support.Inputs.refresh;

/** Roles: search, upsert, delete, grant/revoke (Ranger's SQL-style GRANT ROLE), and roles of a user. */
@Controller
@RequestMapping("/roles")
public class RolesController {
    private final RangerAdminClient admin;
    private final SdkCalls calls;

    public RolesController(RangerAdminClient admin, SdkCalls calls) {
        this.admin = admin;
        this.calls = calls;
    }

    @GetMapping
    public String list(@RequestParam(required = false) String name, Model model) {
        String n = blank(name);
        SdkCall call = calls.run("admin.roles().search(" + q(n) + ", 0, 200)", () -> admin.roles().search(n, 0, 200));
        model.addAttribute("name", n);
        model.addAttribute("list", call);
        model.addAttribute("roles", call.ok() ? call.<List<RangerRole>>value() : List.of());
        model.addAttribute("resultOpen", false);
        model.addAttribute("self", ServletUriComponentsBuilder.fromCurrentRequest().toUriString());
        return "roles/list";
    }

    @GetMapping("/{name}")
    public String detail(@PathVariable String name, Model model) {
        SdkCall call = calls.run("admin.roles().getByName(" + q(name) + ")", () -> admin.roles().getByName(name));
        model.addAttribute("get", call);
        model.addAttribute("role", call.ok() ? call.value() : null);
        model.addAttribute("resultOpen", false);
        return "roles/detail";
    }

    @PostMapping("/apply")
    public String apply(@RequestParam String name, @RequestParam(required = false) String description,
                        @RequestParam(required = false) String users, @RequestParam(required = false) String adminUsers,
                        @RequestParam(required = false) String groups, @RequestParam(required = false) String roles,
                        Model model, HttpServletResponse response) {
        List<String> u = csv(users), a = csv(adminUsers), g = csv(groups), r = csv(roles);
        List<String> userCode = new ArrayList<>();
        u.forEach(x -> userCode.add("RoleMember.of(" + q(x) + ")"));
        a.forEach(x -> userCode.add("RoleMember.admin(" + q(x) + ")"));
        String code = "admin.roles().apply(RangerRole.builder().name(" + q(name) + ")"
                + (blank(description) == null ? "" : ".description(" + q(description) + ")")
                + "\n        .users(List.of(" + String.join(", ", userCode) + "))"
                + "\n        .groups(List.of(" + g.stream().map(x -> "RoleMember.of(" + q(x) + ")").collect(Collectors.joining(", ")) + "))"
                + "\n        .roles(List.of(" + r.stream().map(x -> "RoleMember.of(" + q(x) + ")").collect(Collectors.joining(", ")) + "))"
                + "\n        .build())  // create, or replace the role with this name";
        SdkCall call = calls.run(code, () -> {
            List<RoleMember> members = new ArrayList<>();
            u.forEach(x -> members.add(RoleMember.of(x)));
            a.forEach(x -> members.add(RoleMember.admin(x)));
            RangerRole.Builder b = RangerRole.builder().name(name).users(members)
                    .groups(g.stream().map(RoleMember::of).toList()).roles(r.stream().map(RoleMember::of).toList());
            if (blank(description) != null) {
                b.description(description);
            }
            return admin.roles().apply(b.build());
        });
        return panel(model, response, call);
    }

    @PostMapping("/{name}/save")
    public String save(@PathVariable String name, @RequestParam String json, @RequestParam String mode, Model model,
                       HttpServletResponse response) {
        String var = "RangerRole role = RangerJson.read(json, RangerRole.class);\n";
        SdkCall call = "update".equals(mode)
                ? calls.run(var + "admin.roles().update(role)", () -> admin.roles().update(RangerJson.read(json, RangerRole.class)))
                : calls.run(var + "admin.roles().apply(role)", () -> admin.roles().apply(RangerJson.read(json, RangerRole.class)));
        return panel(model, response, call);
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable long id, Model model, HttpServletResponse response) {
        return panel(model, response, calls.runVoid("admin.roles().delete(" + id + "L)", () -> admin.roles().delete(id)));
    }

    @PostMapping("/delete-by-name")
    public String deleteByName(@RequestParam String name, Model model, HttpServletResponse response) {
        return panel(model, response, calls.runVoid("admin.roles().deleteByName(" + q(name) + ", null, null)",
                () -> admin.roles().deleteByName(name, null, null)));
    }

    /** GRANT / REVOKE role(s) TO users/groups/roles, as a plugin would on behalf of a SQL user. */
    @PostMapping("/grant")
    public String grant(@RequestParam String service, @RequestParam String grantor, @RequestParam String targetRoles,
                        @RequestParam(required = false) String users, @RequestParam(required = false) String groups,
                        @RequestParam(required = false) String roles, @RequestParam(defaultValue = "false") boolean grantOption,
                        @RequestParam String op, Model model, HttpServletResponse response) {
        List<String> t = csv(targetRoles), u = csv(users), g = csv(groups), r = csv(roles);
        String method = "revoke".equals(op) ? "revoke" : "grant";
        String code = "admin.roles()." + method + "(" + q(service) + ", GrantRevokeRoleRequest.builder()"
                + "\n        .grantor(" + q(grantor) + ").targetRoles(" + listOf(t) + ")"
                + "\n        .users(" + listOf(u) + ").groups(" + listOf(g) + ").roles(" + listOf(r) + ")"
                + "\n        .grantOption(" + grantOption + ").build())";
        SdkCall call = calls.run(code, () -> {
            GrantRevokeRoleRequest req = GrantRevokeRoleRequest.builder().grantor(grantor).targetRoles(t)
                    .users(u).groups(g).roles(r).grantOption(grantOption).build();
            return "revoke".equals(method) ? admin.roles().revoke(service, req) : admin.roles().grant(service, req);
        });
        return panel(model, response, call);
    }

    @PostMapping("/for-user")
    public String forUser(@RequestParam String user, Model model) {
        return panel(model, null, calls.run("admin.roles().forUser(" + q(user) + ")", () -> admin.roles().forUser(user)));
    }

    @PostMapping("/names")
    public String names(Model model) {
        return panel(model, null, calls.run("admin.roles().names()", () -> admin.roles().names()));
    }

    private String panel(Model model, HttpServletResponse response, SdkCall call) {
        if (response != null && call.ok()) {
            refresh(response);
        }
        model.addAttribute("call", call);
        return "fragments/call :: call";
    }
}
