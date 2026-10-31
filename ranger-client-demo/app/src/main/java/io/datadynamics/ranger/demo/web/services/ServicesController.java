package io.datadynamics.ranger.demo.web.services;

import io.datadynamics.ranger.client.admin.RangerAdminClient;
import io.datadynamics.ranger.client.model.RangerJson;
import io.datadynamics.ranger.client.model.RangerService;
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

import java.util.List;
import java.util.Map;

import static io.datadynamics.ranger.demo.web.support.Inputs.blank;
import static io.datadynamics.ranger.demo.web.support.Inputs.mapOf;
import static io.datadynamics.ranger.demo.web.support.Inputs.pairs;
import static io.datadynamics.ranger.demo.web.support.Inputs.q;
import static io.datadynamics.ranger.demo.web.support.Inputs.refresh;

/** Services (instances of a service definition): search by type, upsert, connection test, delete. */
@Controller
@RequestMapping("/services")
public class ServicesController {
    private final RangerAdminClient admin;
    private final SdkCalls calls;

    public ServicesController(RangerAdminClient admin, SdkCalls calls) {
        this.admin = admin;
        this.calls = calls;
    }

    @GetMapping
    public String list(@RequestParam(required = false) String type, Model model) {
        String t = blank(type);
        SdkCall call = calls.run("admin.services().search(" + q(t) + ", Map.of(), 0, 200)",
                () -> admin.services().search(t, Map.of(), 0, 200));
        model.addAttribute("type", t);
        model.addAttribute("list", call);
        model.addAttribute("services", call.ok() ? call.<List<RangerService>>value() : List.of());
        model.addAttribute("resultOpen", false);
        model.addAttribute("self", ServletUriComponentsBuilder.fromCurrentRequest().toUriString());
        return "services/list";
    }

    @GetMapping("/{name}")
    public String detail(@PathVariable String name, Model model) {
        SdkCall call = calls.run("admin.services().getByName(" + q(name) + ")", () -> admin.services().getByName(name));
        model.addAttribute("get", call);
        model.addAttribute("service", call.ok() ? call.value() : null);
        model.addAttribute("resultOpen", false);
        return "services/detail";
    }

    /** Form: name, type, description, tag service and configs as key=value lines. */
    @PostMapping("/apply")
    public String applyForm(@RequestParam String name, @RequestParam String type, @RequestParam(required = false) String description,
                            @RequestParam(required = false) String tagService, @RequestParam(required = false) String configs,
                            Model model, HttpServletResponse response) {
        StringBuilder code = new StringBuilder("admin.services().apply(RangerService.builder()\n        .name(")
                .append(q(name)).append(").type(").append(q(type)).append(")");
        if (blank(description) != null) {
            code.append(".description(").append(q(description)).append(")");
        }
        if (blank(tagService) != null) {
            code.append(".tagService(").append(q(tagService)).append(")");
        }
        Map<String, String> shown;
        try {
            shown = pairs(configs);
        } catch (IllegalArgumentException e) {
            shown = Map.of();
        }
        code.append("\n        .configs(").append(mapOf(shown)).append(")\n        .build())  // create, or update by name");
        SdkCall call = calls.run(code.toString(), () -> {
            RangerService.Builder b = RangerService.builder().name(name).type(type).configs(pairs(configs));
            if (blank(description) != null) {
                b.description(description);
            }
            if (blank(tagService) != null) {
                b.tagService(tagService);
            }
            return admin.services().apply(b.build());
        });
        return panel(model, response, call);
    }

    @PostMapping("/{name}/save")
    public String save(@PathVariable String name, @RequestParam String json, @RequestParam String mode, Model model,
                       HttpServletResponse response) {
        String var = "RangerService service = RangerJson.read(json, RangerService.class);\n";
        SdkCall call = "update".equals(mode)
                ? calls.run(var + "admin.services().update(service)", () -> admin.services().update(RangerJson.read(json, RangerService.class)))
                : calls.run(var + "admin.services().apply(service)", () -> admin.services().apply(RangerJson.read(json, RangerService.class)));
        return panel(model, response, call);
    }

    @PostMapping("/{name}/test")
    public String test(@PathVariable String name, Model model) {
        SdkCall call = calls.run("admin.services().testConnection(admin.services().getByName(" + q(name) + "))",
                () -> admin.services().testConnection(admin.services().getByName(name)));
        return panel(model, null, call);
    }

    @PostMapping("/{name}/delete")
    public String delete(@PathVariable String name, Model model, HttpServletResponse response) {
        return panel(model, response, calls.runVoid("admin.services().deleteByName(" + q(name) + ")",
                () -> admin.services().deleteByName(name)));
    }

    private String panel(Model model, HttpServletResponse response, SdkCall call) {
        if (response != null && call.ok()) {
            refresh(response);
        }
        model.addAttribute("call", call);
        return "fragments/call :: call";
    }
}
