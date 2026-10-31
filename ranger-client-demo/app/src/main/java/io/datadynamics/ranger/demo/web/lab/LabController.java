package io.datadynamics.ranger.demo.web.lab;

import io.datadynamics.ranger.client.admin.RangerAdminClient;
import io.datadynamics.ranger.client.model.PolicyItem;
import io.datadynamics.ranger.client.model.RangerPolicy;
import io.datadynamics.ranger.client.model.RangerService;
import io.datadynamics.ranger.client.model.ResourceInfo;
import io.datadynamics.ranger.client.model.UserInfo;
import io.datadynamics.ranger.demo.trace.SdkCall;
import io.datadynamics.ranger.demo.trace.SdkCalls;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resilience Lab: failover, retry, exception mapping and optimistic locking, each triggered on purpose
 * against the live stack with the clients in {@link LabClients}.
 */
@Controller
@RequestMapping("/lab")
public class LabController {
    private final RangerAdminClient admin;
    private final LabClients lab;
    private final SdkCalls calls;

    public LabController(RangerAdminClient admin, LabClients lab, SdkCalls calls) {
        this.admin = admin;
        this.lab = lab;
        this.calls = calls;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("deadUrl", LabClients.DEAD_URL);
        model.addAttribute("adminUrls", admin.config().urls());
        model.addAttribute("nodes", lab.failover.nodeAvailability());
        model.addAttribute("pdpAvailable", lab.pdpAsMallory != null);
        model.addAttribute("kmsAvailable", lab.kmsAsMallory != null);
        return "lab/lab";
    }

    /** ① Failover: the first URL never resolves; the transport moves to the next node and puts the dead one in cooldown. */
    @PostMapping("/failover")
    public String failover(Model model) {
        SdkCall call = calls.run("failover.serviceDefs().getByName(\"hive\")  // urls: [" + LabClients.DEAD_URL + ", <admin>]",
                () -> lab.failover.serviceDefs().getByName("hive"));
        model.addAttribute("resultOpen", false);
        model.addAttribute("call", call);
        model.addAttribute("nodes", new LinkedHashMap<URI, Boolean>(lab.failover.nodeAvailability()));
        return "lab/lab :: failoverResult";
    }

    /** ② Retry: a 1 ms timeout makes every attempt of an idempotent GET time out; the request id stays the same. */
    @PostMapping("/timeout")
    public String timeout(Model model) {
        return call(model, calls.run("slow.serviceDefs().getByName(\"hive\")  // readTimeout(1 ms), RetryPolicy.exponential(3, 100 ms)",
                () -> lab.slow.serviceDefs().getByName("hive")));
    }

    /** ③ Exception catalog. */
    @PostMapping("/error/{kind}")
    public String error(@PathVariable String kind, Model model) {
        SdkCall call = switch (kind) {
            case "404" -> calls.run("admin.policies().get(999999)", () -> admin.policies().get(999999));
            case "409" -> calls.run("admin.services().create(RangerService.builder().name(\"demo_hive\").type(\"hive\")...)  // already exists",
                    () -> admin.services().create(RangerService.builder().name("demo_hive").type("hive")
                            .configs(Map.of("username", "hive", "password", "hive",
                                    "jdbc.driverClassName", "org.apache.hive.jdbc.HiveDriver", "jdbc.url", "jdbc:hive2://hive.example:10000"))
                            .build()));
            case "400" -> calls.run("""
                            admin.policies().create(RangerPolicy.builder().service("demo_hive").name("lab_invalid")
                                    .resource("no_such_resource", "x").allow(PolicyItem.users("alice").accesses("select")).build())""",
                    () -> admin.policies().create(RangerPolicy.builder().service("demo_hive").name("lab_invalid")
                            .resource("no_such_resource", "x").allow(PolicyItem.users("alice").accesses("select")).build()));
            case "401" -> calls.run("wrongPassword.services().find(\"demo_hive\")  // BasicAuth.of(\"admin\", \"wrong-password\")",
                    () -> lab.wrongPassword.services().find("demo_hive"));
            case "403-pdp" -> lab.pdpAsMallory == null ? null : calls.run("""
                            pdpAsMallory.authorize(pdpAsMallory.request(UserInfo.builder().name("alice").build(),
                                    ResourceInfo.of("database", "sales", "table", "orders"), "select"))  // caller mallory is no delegation user""",
                    () -> lab.pdpAsMallory.authorize(lab.pdpAsMallory.request(UserInfo.builder().name("alice").build(),
                            ResourceInfo.of("database", "sales", "table", "orders"), "select")));
            case "403-kms" -> lab.kmsAsMallory == null ? null : calls.run("kmsAsMallory.names()  // HadoopSimpleAuth.of(\"mallory\")",
                    () -> lab.kmsAsMallory.names());
            case "unsupported" -> calls.run("admin.gds().search(\"dataset\", Map.of(), 0, 10)",
                    () -> admin.gds().search("dataset", Map.of(), 0, 10));
            case "transport" -> calls.run("unreachable.services().find(\"demo_hive\")  // urls: [" + LabClients.DEAD_URL + "]",
                    () -> lab.unreachable.services().find("demo_hive"));
            default -> calls.run("// unknown lab action: " + kind, () -> {
                throw new IllegalArgumentException("Unknown lab action: " + kind);
            });
        };
        if (call == null) {
            call = calls.run("// server not configured", () -> {
                throw new IllegalStateException("This client is not configured (ranger.pdp / ranger.kms urls missing)");
            });
        }
        return call(model, call);
    }

    /**
     * ④ Optimistic locking: two copies of the same policy; the first update bumps the server version, the stale
     * second update is refused by update(policy, true). The description is restored afterwards.
     */
    @PostMapping("/locking")
    public String locking(Model model) {
        List<SdkCall> steps = new ArrayList<>();
        SdkCall readA = calls.run("RangerPolicy a = admin.policies().getByName(\"demo_hive\", \"sales_read\")",
                () -> admin.policies().getByName("demo_hive", "sales_read"));
        steps.add(readA);
        if (readA.ok()) {
            SdkCall readB = calls.run("RangerPolicy b = admin.policies().getByName(\"demo_hive\", \"sales_read\")",
                    () -> admin.policies().getByName("demo_hive", "sales_read"));
            steps.add(readB);
            RangerPolicy a = readA.value();
            RangerPolicy b = readB.ok() ? readB.value() : a;
            String original = a.getDescription();
            SdkCall updateA = calls.run("admin.policies().update(a.toBuilder().description(\"edited by copy A\").build(), true)",
                    () -> admin.policies().update(a.toBuilder().description("edited by copy A").build(), true));
            steps.add(updateA);
            steps.add(calls.run("admin.policies().update(b.toBuilder().description(\"edited by stale copy B\").build(), true)  // b.version is stale",
                    () -> admin.policies().update(b.toBuilder().description("edited by stale copy B").build(), true)));
            if (updateA.ok()) {
                RangerPolicy current = updateA.value();
                steps.add(calls.run("admin.policies().update(current.toBuilder().description(original).build(), true)  // restore",
                        () -> admin.policies().update(current.toBuilder().description(original).build(), true)));
            }
        }
        model.addAttribute("steps", steps);
        model.addAttribute("resultOpen", false);
        return "lab/lab :: steps";
    }

    private static String call(Model model, SdkCall call) {
        model.addAttribute("call", call);
        model.addAttribute("resultOpen", false);
        return "fragments/call :: call";
    }
}
