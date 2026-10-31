package io.datadynamics.ranger.demo.web.tags;

import io.datadynamics.ranger.client.admin.RangerAdminClient;
import io.datadynamics.ranger.client.model.PolicyResource;
import io.datadynamics.ranger.client.model.RangerServiceResource;
import io.datadynamics.ranger.client.model.RangerTag;
import io.datadynamics.ranger.client.model.RangerTagDef;
import io.datadynamics.ranger.client.model.TagAttributeDef;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.datadynamics.ranger.demo.web.support.Inputs.args;
import static io.datadynamics.ranger.demo.web.support.Inputs.csv;
import static io.datadynamics.ranger.demo.web.support.Inputs.mapOf;
import static io.datadynamics.ranger.demo.web.support.Inputs.pairs;
import static io.datadynamics.ranger.demo.web.support.Inputs.q;
import static io.datadynamics.ranger.demo.web.support.Inputs.refresh;
import static io.datadynamics.ranger.demo.web.support.Inputs.resources;

/** Tag store: tag definitions, tags, service resources, and the tag-to-resource mappings tag-based policies match on. */
@Controller
@RequestMapping("/tags")
public class TagsController {
    private final RangerAdminClient admin;
    private final SdkCalls calls;

    public TagsController(RangerAdminClient admin, SdkCalls calls) {
        this.admin = admin;
        this.calls = calls;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "demo_hive") String service, Model model) {
        model.addAttribute("service", service);
        model.addAttribute("types", calls.run("admin.tags().types()", () -> admin.tags().types()));
        SdkCall defs = calls.run("admin.tags().listDefs()", () -> admin.tags().listDefs());
        SdkCall tags = calls.run("admin.tags().list()", () -> admin.tags().list());
        SdkCall res = calls.run("admin.tags().listResources(" + q(service) + ")", () -> admin.tags().listResources(service));
        model.addAttribute("defsCall", defs);
        model.addAttribute("tagsCall", tags);
        model.addAttribute("resCall", res);
        model.addAttribute("defs", defs.ok() ? defs.<List<RangerTagDef>>value() : List.of());
        model.addAttribute("tags", tags.ok() ? tags.<List<RangerTag>>value() : List.of());
        model.addAttribute("resources", res.ok() ? res.<List<RangerServiceResource>>value() : List.of());
        model.addAttribute("resultOpen", false);
        model.addAttribute("self", ServletUriComponentsBuilder.fromCurrentRequest().toUriString());
        return "tags/list";
    }

    @PostMapping("/defs/create")
    public String createDef(@RequestParam String name, @RequestParam(required = false) String attributes,
                            @RequestParam(defaultValue = "false") boolean updateIfExists, Model model, HttpServletResponse response) {
        List<String> attrs = csv(attributes);
        String code = "admin.tags().createDef(RangerTagDef.builder().name(" + q(name) + ").source(\"demo\")"
                + "\n        .attributeDefs(List.of(" + attrs.stream().map(a -> "TagAttributeDef.builder().name(" + q(a) + ").type(\"string\").build()")
                .collect(Collectors.joining(", ")) + ")).build(), " + updateIfExists + ")";
        return panel(model, response, calls.run(code, () -> admin.tags().createDef(RangerTagDef.builder().name(name).source("demo")
                .attributeDefs(attrs.stream().map(a -> TagAttributeDef.builder().name(a).type("string").build()).toList()).build(),
                updateIfExists)));
    }

    @PostMapping("/defs/delete")
    public String deleteDef(@RequestParam long id, Model model, HttpServletResponse response) {
        return panel(model, response, calls.runVoid("admin.tags().deleteDef(" + id + "L)", () -> admin.tags().deleteDef(id)));
    }

    @PostMapping("/create")
    public String createTag(@RequestParam String type, @RequestParam(required = false) String attributes,
                            Model model, HttpServletResponse response) {
        Map<String, String> shown;
        try {
            shown = pairs(attributes);
        } catch (IllegalArgumentException e) {
            shown = Map.of();
        }
        String code = "admin.tags().create(RangerTag.builder().type(" + q(type) + ").attributes(" + mapOf(shown) + ").build(), false)";
        return panel(model, response, calls.run(code,
                () -> admin.tags().create(RangerTag.builder().type(type).attributes(pairs(attributes)).build(), false)));
    }

    @PostMapping("/delete")
    public String deleteTag(@RequestParam long id, Model model, HttpServletResponse response) {
        return panel(model, response, calls.runVoid("admin.tags().delete(" + id + "L)", () -> admin.tags().delete(id)));
    }

    @PostMapping("/resources/create")
    public String createResource(@RequestParam String service, @RequestParam String resource,
                                 @RequestParam(defaultValue = "false") boolean updateIfExists, Model model, HttpServletResponse response) {
        Map<String, List<String>> shown;
        try {
            shown = resources(resource);
        } catch (IllegalArgumentException e) {
            shown = Map.of();
        }
        String elements = shown.entrySet().stream().map(e -> q(e.getKey()) + ", PolicyResource.of(" + args(e.getValue()) + ")")
                .collect(Collectors.joining(",\n            "));
        String code = "admin.tags().createResource(RangerServiceResource.builder().serviceName(" + q(service) + ")"
                + "\n        .resourceElements(Map.of(" + elements + ")).build(), " + updateIfExists + ")";
        return panel(model, response, calls.run(code, () -> {
            Map<String, PolicyResource> el = new LinkedHashMap<>();
            resources(resource).forEach((k, v) -> el.put(k, PolicyResource.of(v.toArray(String[]::new))));
            return admin.tags().createResource(RangerServiceResource.builder().serviceName(service).resourceElements(el).build(),
                    updateIfExists);
        }));
    }

    @PostMapping("/resources/delete")
    public String deleteResource(@RequestParam long id, Model model, HttpServletResponse response) {
        return panel(model, response, calls.runVoid("admin.tags().deleteResource(" + id + "L)", () -> admin.tags().deleteResource(id)));
    }

    @PostMapping("/map")
    public String map(@RequestParam String tagGuid, @RequestParam String resourceGuid, @RequestParam String op,
                      Model model, HttpServletResponse response) {
        SdkCall call = "unmap".equals(op)
                ? calls.runVoid("admin.tags().unmap(" + q(tagGuid) + ", " + q(resourceGuid) + ")", () -> admin.tags().unmap(tagGuid, resourceGuid))
                : calls.run("admin.tags().map(" + q(tagGuid) + ", " + q(resourceGuid) + ")", () -> admin.tags().map(tagGuid, resourceGuid));
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
