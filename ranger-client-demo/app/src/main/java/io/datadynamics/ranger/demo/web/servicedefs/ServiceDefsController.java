package io.datadynamics.ranger.demo.web.servicedefs;

import io.datadynamics.ranger.client.admin.RangerAdminClient;
import io.datadynamics.ranger.client.model.RangerServiceDef;
import io.datadynamics.ranger.demo.trace.SdkCall;
import io.datadynamics.ranger.demo.trace.SdkCalls;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

import static io.datadynamics.ranger.demo.web.support.Inputs.q;

/** Service definitions: the schema (resource hierarchy, access types, mask types) every service and policy follows. */
@Controller
@RequestMapping("/servicedefs")
public class ServiceDefsController {
    private final RangerAdminClient admin;
    private final SdkCalls calls;

    public ServiceDefsController(RangerAdminClient admin, SdkCalls calls) {
        this.admin = admin;
        this.calls = calls;
    }

    @GetMapping
    public String list(Model model) {
        SdkCall call = calls.run("admin.serviceDefs().stream().toList()  // pages through all definitions",
                () -> admin.serviceDefs().stream().toList());
        model.addAttribute("list", call);
        model.addAttribute("defs", call.ok() ? call.<List<RangerServiceDef>>value() : List.of());
        model.addAttribute("resultOpen", false);
        return "servicedefs/list";
    }

    @GetMapping("/{name}")
    public String detail(@PathVariable String name, Model model) {
        SdkCall call = calls.run("admin.serviceDefs().getByName(" + q(name) + ")", () -> admin.serviceDefs().getByName(name));
        model.addAttribute("get", call);
        model.addAttribute("def", call.ok() ? call.value() : null);
        model.addAttribute("resultOpen", false);
        return "servicedefs/detail";
    }
}
