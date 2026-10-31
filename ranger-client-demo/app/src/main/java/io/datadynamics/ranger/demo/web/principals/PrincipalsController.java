package io.datadynamics.ranger.demo.web.principals;

import io.datadynamics.ranger.client.admin.RangerAdminClient;
import io.datadynamics.ranger.client.model.RangerGroup;
import io.datadynamics.ranger.client.model.RangerUser;
import io.datadynamics.ranger.demo.trace.SdkCall;
import io.datadynamics.ranger.demo.trace.SdkCalls;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

import static io.datadynamics.ranger.demo.web.support.Inputs.blank;
import static io.datadynamics.ranger.demo.web.support.Inputs.q;
import static io.datadynamics.ranger.demo.web.support.Inputs.refresh;

/** Ranger's internal users and groups (the xusers API): search, create, delete, group membership. */
@Controller
@RequestMapping("/principals")
public class PrincipalsController {
    private final RangerAdminClient admin;
    private final SdkCalls calls;

    public PrincipalsController(RangerAdminClient admin, SdkCalls calls) {
        this.admin = admin;
        this.calls = calls;
    }

    @GetMapping
    public String list(@RequestParam(required = false) String q, Model model) {
        String n = blank(q);
        SdkCall users = calls.run("admin.users().search(" + q(n) + ", 0, 100)", () -> admin.users().search(n, 0, 100));
        SdkCall groups = calls.run("admin.groups().search(" + q(n) + ", 0, 100)", () -> admin.groups().search(n, 0, 100));
        model.addAttribute("q", n);
        model.addAttribute("usersCall", users);
        model.addAttribute("groupsCall", groups);
        model.addAttribute("users", users.ok() ? users.<List<RangerUser>>value() : List.of());
        model.addAttribute("groups", groups.ok() ? groups.<List<RangerGroup>>value() : List.of());
        model.addAttribute("resultOpen", false);
        model.addAttribute("self", ServletUriComponentsBuilder.fromCurrentRequest().toUriString());
        return "principals/list";
    }

    @PostMapping("/users/create")
    public String createUser(@RequestParam String name, @RequestParam(required = false) String firstName,
                             @RequestParam String password, @RequestParam(required = false) String email,
                             @RequestParam(defaultValue = "ROLE_USER") String role, Model model, HttpServletResponse response) {
        String code = "admin.users().create(RangerUser.builder().name(" + q(name) + ").firstName(" + q(firstName) + ")"
                + (blank(email) == null ? "" : ".emailAddress(" + q(email) + ")")
                + "\n        .password(\"*****\").userRoleList(List.of(" + q(role) + ")).status(1).build())";
        SdkCall call = calls.run(code, () -> {
            RangerUser.Builder b = RangerUser.builder().name(name).firstName(blank(firstName) == null ? name : firstName)
                    .password(password).userRoleList(List.of(role)).status(1);
            if (blank(email) != null) {
                b.emailAddress(email);
            }
            return admin.users().create(b.build());
        });
        return panel(model, response, call);
    }

    @PostMapping("/users/find")
    public String findUser(@RequestParam String name, Model model) {
        return panel(model, null, calls.run("admin.users().find(" + q(name) + ")  // Optional, empty when absent",
                () -> admin.users().find(name)));
    }

    @PostMapping("/users/delete")
    public String deleteUser(@RequestParam String name, @RequestParam(defaultValue = "false") boolean force, Model model,
                             HttpServletResponse response) {
        return panel(model, response, calls.runVoid("admin.users().deleteByName(" + q(name) + ", " + force + ")",
                () -> admin.users().deleteByName(name, force)));
    }

    @PostMapping("/users/groups")
    public String groupsOfUser(@RequestParam String name, Model model) {
        return panel(model, null, calls.run("admin.users().groups(admin.users().getByName(" + q(name) + ").getId())",
                () -> admin.users().groups(admin.users().getByName(name).getId())));
    }

    @PostMapping("/groups/create")
    public String createGroup(@RequestParam String name, @RequestParam(required = false) String description, Model model,
                              HttpServletResponse response) {
        return panel(model, response, calls.run("admin.groups().create(RangerGroup.builder().name(" + q(name)
                        + ").description(" + q(blank(description)) + ").build())",
                () -> admin.groups().create(RangerGroup.builder().name(name).description(blank(description)).build())));
    }

    @PostMapping("/groups/delete")
    public String deleteGroup(@RequestParam String name, @RequestParam(defaultValue = "false") boolean force, Model model,
                              HttpServletResponse response) {
        return panel(model, response, calls.runVoid("admin.groups().deleteByName(" + q(name) + ", " + force + ")",
                () -> admin.groups().deleteByName(name, force)));
    }

    @PostMapping("/groups/users")
    public String usersOfGroup(@RequestParam String name, Model model) {
        return panel(model, null, calls.run("admin.groups().users(admin.groups().getByName(" + q(name) + ").getId())",
                () -> admin.groups().users(admin.groups().getByName(name).getId())));
    }

    @PostMapping("/members")
    public String membership(@RequestParam String group, @RequestParam String user, @RequestParam String op, Model model,
                             HttpServletResponse response) {
        SdkCall call = "remove".equals(op)
                ? calls.runVoid("admin.groups().removeUser(" + q(group) + ", " + q(user) + ")", () -> admin.groups().removeUser(group, user))
                : calls.run("admin.groups().addUser(" + q(group) + ", " + q(user) + ")", () -> admin.groups().addUser(group, user));
        return panel(model, response, call);
    }

    private String panel(Model model, HttpServletResponse response, SdkCall call) {
        if (response != null && call.ok()) {
            refresh(response);
        }
        model.addAttribute("call", call);
        return "fragments/call :: call";
    }
}
