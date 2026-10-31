package io.datadynamics.ranger.demo.web.pac;

import io.datadynamics.ranger.client.admin.RangerAdminClient;
import io.datadynamics.ranger.client.pac.Plan;
import io.datadynamics.ranger.client.pac.PolicyAsCode;
import io.datadynamics.ranger.client.pac.PolicySet;
import io.datadynamics.ranger.client.pac.SpecLoader;
import io.datadynamics.ranger.demo.seed.DemoSeeder;
import io.datadynamics.ranger.demo.trace.SdkCall;
import io.datadynamics.ranger.demo.trace.SdkCalls;
import io.datadynamics.ranger.demo.web.pdp.Kv;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Policy as Code: YAML spec -> plan (diff against the server) -> apply, and export of a service's policies. */
@Controller
@RequestMapping("/pac")
public class PacController {
    static final String SOURCE = "editor.yaml";

    private final PolicyAsCode pac;
    private final SdkCalls calls;

    public PacController(RangerAdminClient admin, SdkCalls calls) {
        this.pac = new PolicyAsCode(admin);
        this.calls = calls;
    }

    @GetMapping
    public String page(Model model) throws IOException {
        model.addAttribute("yaml", new String(new ClassPathResource("seed/demo_hive.yaml").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8));
        model.addAttribute("service", DemoSeeder.SERVICE);
        return "pac/pac";
    }

    @PostMapping("/plan")
    public String plan(@RequestParam String yaml, Model model) {
        SdkCall parse = parse(yaml);
        model.addAttribute("parse", parse);
        if (parse.ok()) {
            List<PolicySet> specs = List.of(parse.<PolicySet>value());
            SdkCall plan = calls.run("Plan plan = pac.plan(List.of(spec))  // pac = new PolicyAsCode(admin)", () -> pac.plan(specs));
            model.addAttribute("call", plan);
        }
        model.addAttribute("resultOpen", false);
        return "pac/pac :: planResult";
    }

    /** Plans again (the server may have changed since the last plan) and applies. */
    @PostMapping("/apply")
    public String apply(@RequestParam String yaml, Model model) {
        SdkCall parse = parse(yaml);
        model.addAttribute("parse", parse);
        if (parse.ok()) {
            List<PolicySet> specs = List.of(parse.<PolicySet>value());
            SdkCall plan = calls.run("Plan plan = pac.plan(List.of(spec))", () -> pac.plan(specs));
            model.addAttribute("plan", plan);
            if (plan.ok()) {
                model.addAttribute("call", calls.run("List<Change> applied = pac.apply(plan)", () -> pac.apply(plan.<Plan>value())));
            }
        }
        model.addAttribute("resultOpen", false);
        return "pac/pac :: applyResult";
    }

    @PostMapping("/export")
    public String export(@RequestParam String service, @RequestParam(defaultValue = "") String zone, Model model) {
        String z = zone.isBlank() ? null : zone.trim();
        SdkCall call = calls.run("String yaml = PolicyAsCode.toYaml(pac.export(" + Kv.str(service) + ", " + Kv.str(z) + "))",
                () -> PolicyAsCode.toYaml(pac.export(service, z)));
        model.addAttribute("call", call);
        model.addAttribute("resultOpen", false);
        return "pac/pac :: exportResult";
    }

    private SdkCall parse(String yaml) {
        return calls.run("PolicySet spec = SpecLoader.parseYaml(yaml, \"" + SOURCE + "\")", () -> SpecLoader.parseYaml(yaml, SOURCE));
    }
}
