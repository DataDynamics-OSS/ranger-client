package io.datadynamics.ranger.demo.web.zones;

import io.datadynamics.ranger.client.admin.RangerAdminClient;
import io.datadynamics.ranger.client.model.RangerJson;
import io.datadynamics.ranger.client.model.RangerPrincipal;
import io.datadynamics.ranger.client.model.RangerSecurityZone;
import io.datadynamics.ranger.client.model.SecurityZoneChangeRequest;
import io.datadynamics.ranger.client.model.ZoneService;
import io.datadynamics.ranger.client.model.ZoneServiceResource;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.datadynamics.ranger.demo.web.support.Inputs.blank;
import static io.datadynamics.ranger.demo.web.support.Inputs.q;
import static io.datadynamics.ranger.demo.web.support.Inputs.refresh;
import static io.datadynamics.ranger.demo.web.support.Inputs.resources;

/** Security zones (v2 API): list, create, full update, partial update, zone resources per service, delete. */
@Controller
@RequestMapping("/zones")
public class ZonesController {
    private final RangerAdminClient admin;
    private final SdkCalls calls;

    public ZonesController(RangerAdminClient admin, SdkCalls calls) {
        this.admin = admin;
        this.calls = calls;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("headers", calls.run("admin.zones().headers()  // id + name only, cheap", () -> admin.zones().headers()));
        SdkCall all = calls.run("admin.zones().stream().toList()", () -> admin.zones().stream().toList());
        model.addAttribute("list", all);
        model.addAttribute("zones", all.ok() ? all.<List<RangerSecurityZone>>value() : List.of());
        model.addAttribute("resultOpen", false);
        model.addAttribute("self", ServletUriComponentsBuilder.fromCurrentRequest().toUriString());
        return "zones/list";
    }

    @GetMapping("/{name}")
    public String detail(@PathVariable String name, Model model) {
        SdkCall get = calls.run("admin.zones().getByName(" + q(name) + ")", () -> admin.zones().getByName(name));
        model.addAttribute("get", get);
        RangerSecurityZone zone = get.ok() ? get.value() : null;
        model.addAttribute("zone", zone);
        List<SdkCall> resources = new ArrayList<>();
        if (zone != null && zone.getServices() != null) {
            for (String svc : zone.getServices().keySet()) {
                resources.add(calls.run("admin.zones().resources(" + zone.getId() + "L, " + q(svc) + ")",
                        () -> admin.zones().resources(zone.getId(), svc)));
            }
        }
        model.addAttribute("resources", resources);
        model.addAttribute("resultOpen", false);
        return "zones/detail";
    }

    @PostMapping("/create")
    public String create(@RequestParam String name, @RequestParam(required = false) String description,
                         @RequestParam String service, @RequestParam String resource,
                         @RequestParam(required = false) String tagService, @RequestParam String adminUser,
                         Model model, HttpServletResponse response) {
        String tag = blank(tagService);
        String code = "admin.zones().create(RangerSecurityZone.builder().name(" + q(name) + ").description(" + q(blank(description)) + ")"
                + "\n        .services(Map.of(" + q(service) + ", ZoneService.builder().resources(List.of(\n            "
                + resourceCode(resource) + ")).build()))"
                + (tag == null ? "" : "\n        .tagServices(List.of(" + q(tag) + "))")
                + "\n        .admins(List.of(RangerPrincipal.builder().type(\"USER\").name(" + q(adminUser) + ").build()))"
                + "\n        .auditors(List.of(RangerPrincipal.builder().type(\"USER\").name(" + q(adminUser) + ").build()))"
                + "\n        .build())";
        SdkCall call = calls.run(code, () -> {
            RangerPrincipal p = RangerPrincipal.builder().type("USER").name(adminUser).build();
            RangerSecurityZone.Builder b = RangerSecurityZone.builder().name(name).description(blank(description))
                    .services(Map.of(service, zoneService(resource))).admins(List.of(p)).auditors(List.of(p));
            if (tag != null) {
                b.tagServices(List.of(tag));
            }
            return admin.zones().create(b.build());
        });
        return panel(model, response, call);
    }

    /** Partial update: only what the form fills in is sent. */
    @PostMapping("/partial")
    public String partial(@RequestParam String zone, @RequestParam(required = false) String service,
                          @RequestParam(required = false) String resource, @RequestParam(defaultValue = "add") String resourceOp,
                          @RequestParam(required = false) String addTagService, @RequestParam(required = false) String addAdmin,
                          @RequestParam(required = false) String removeAdmin, Model model, HttpServletResponse response) {
        String svc = blank(service), res = blank(resource), tag = blank(addTagService), add = blank(addAdmin), rm = blank(removeAdmin);
        StringBuilder code = new StringBuilder("long id = admin.zones().getByName(" + q(zone) + ").getId();\n"
                + "admin.zones().updatePartial(id, SecurityZoneChangeRequest.builder()");
        if (svc != null && res != null) {
            code.append("\n        .resources").append("remove".equals(resourceOp) ? "ToRemove" : "ToUpdate").append("(Map.of(").append(q(svc))
                    .append(", ZoneService.builder().resources(List.of(\n            ").append(resourceCode(res)).append(")).build()))");
        }
        if (tag != null) {
            code.append("\n        .tagServicesToAdd(List.of(").append(q(tag)).append("))");
        }
        if (add != null) {
            code.append("\n        .adminsToAdd(List.of(RangerPrincipal.builder().type(\"USER\").name(").append(q(add)).append(").build()))");
        }
        if (rm != null) {
            code.append("\n        .adminsToRemove(List.of(RangerPrincipal.builder().type(\"USER\").name(").append(q(rm)).append(").build()))");
        }
        code.append("\n        .build())");
        SdkCall call = calls.run(code.toString(), () -> {
            long id = admin.zones().getByName(zone).getId();
            SecurityZoneChangeRequest.Builder b = SecurityZoneChangeRequest.builder();
            if (svc != null && res != null) {
                Map<String, ZoneService> m = Map.of(svc, zoneService(res));
                if ("remove".equals(resourceOp)) {
                    b.resourcesToRemove(m);
                } else {
                    b.resourcesToUpdate(m);
                }
            }
            if (tag != null) {
                b.tagServicesToAdd(List.of(tag));
            }
            if (add != null) {
                b.adminsToAdd(List.of(RangerPrincipal.builder().type("USER").name(add).build()));
            }
            if (rm != null) {
                b.adminsToRemove(List.of(RangerPrincipal.builder().type("USER").name(rm).build()));
            }
            return admin.zones().updatePartial(id, b.build());
        });
        return panel(model, response, call);
    }

    @PostMapping("/{name}/save")
    public String save(@PathVariable String name, @RequestParam String json, Model model, HttpServletResponse response) {
        return panel(model, response, calls.run("RangerSecurityZone zone = RangerJson.read(json, RangerSecurityZone.class);\n"
                        + "admin.zones().update(zone)  // replaces the whole zone",
                () -> admin.zones().update(RangerJson.read(json, RangerSecurityZone.class))));
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable long id, Model model, HttpServletResponse response) {
        return panel(model, response, calls.runVoid("admin.zones().delete(" + id + "L)", () -> admin.zones().delete(id)));
    }

    @PostMapping("/delete-by-name")
    public String deleteByName(@RequestParam String name, Model model, HttpServletResponse response) {
        return panel(model, response, calls.runVoid("admin.zones().deleteByName(" + q(name) + ")", () -> admin.zones().deleteByName(name)));
    }

    private static ZoneService zoneService(String resource) {
        Map<String, List<String>> r = new LinkedHashMap<>(resources(resource));
        return ZoneService.builder().resources(List.of(ZoneServiceResource.builder().resource(r).build())).build();
    }

    private static String resourceCode(String resource) {
        Map<String, List<String>> r;
        try {
            r = resources(resource);
        } catch (IllegalArgumentException e) {
            r = Map.of();
        }
        String body = r.entrySet().stream()
                .map(e -> q(e.getKey()) + ", List.of(" + e.getValue().stream().map(io.datadynamics.ranger.demo.web.support.Inputs::q)
                        .collect(Collectors.joining(", ")) + ")")
                .collect(Collectors.joining(", "));
        return "ZoneServiceResource.builder().resource(Map.of(" + body + ")).build()";
    }

    private String panel(Model model, HttpServletResponse response, SdkCall call) {
        if (response != null && call.ok()) {
            refresh(response);
        }
        model.addAttribute("call", call);
        return "fragments/call :: call";
    }
}
