package io.datadynamics.ranger.demo.web.dashboard;

import io.datadynamics.ranger.client.admin.RangerAdminClient;
import io.datadynamics.ranger.client.core.AbstractRangerClient;
import io.datadynamics.ranger.client.core.Feature;
import io.datadynamics.ranger.client.core.ServerKind;
import io.datadynamics.ranger.client.kms.RangerKmsClient;
import io.datadynamics.ranger.client.pdp.RangerPdpClient;
import io.datadynamics.ranger.demo.seed.DemoSeeder;
import io.datadynamics.ranger.demo.trace.SdkCall;
import io.datadynamics.ranger.demo.trace.SdkCalls;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Connection status of the three clients, server version, feature detection, and seed status. */
@Controller
public class DashboardController {
    private final RangerAdminClient admin;
    private final Optional<RangerPdpClient> pdp;
    private final Optional<RangerKmsClient> kms;
    private final SdkCalls calls;
    private final DemoSeeder seeder;

    public DashboardController(RangerAdminClient admin, Optional<RangerPdpClient> pdp, Optional<RangerKmsClient> kms,
                               SdkCalls calls, DemoSeeder seeder) {
        this.admin = admin;
        this.pdp = pdp;
        this.kms = kms;
        this.calls = calls;
        this.seeder = seeder;
    }

    /** One row of the connection table. */
    public record ServerStatus(String name, String bean, List<URI> urls, String auth, Map<URI, Boolean> nodes, SdkCall probe) {
    }

    public record FeatureRow(Feature feature, String minVersion, SdkCall call) {
        public Boolean supported() {
            return call.ok() ? (Boolean) call.result() : null;
        }
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        List<ServerStatus> servers = new ArrayList<>();
        servers.add(status("Admin", "RangerAdminClient", admin,
                calls.run("admin.serviceDefs().find(\"hive\").isPresent()", () -> admin.serviceDefs().find("hive").isPresent())));
        pdp.ifPresent(p -> servers.add(status("PDP", "RangerPdpClient", p, calls.run("pdp.ready()", p::ready))));
        kms.ifPresent(k -> servers.add(status("KMS", "RangerKmsClient", k, calls.run("kms.status()", k::status))));
        model.addAttribute("servers", servers);

        model.addAttribute("version", calls.run("admin.capabilities().serverVersion()", () -> admin.capabilities().serverVersion()));
        List<FeatureRow> features = new ArrayList<>();
        for (Feature f : Feature.values()) {
            if (f.server() == ServerKind.ADMIN) {
                features.add(new FeatureRow(f, f.minVersion(),
                        calls.run("admin.capabilities().supports(Feature." + f.name() + ")", () -> admin.capabilities().supports(f))));
            }
        }
        model.addAttribute("features", features);
        model.addAttribute("seeder", seeder);
        return "dashboard";
    }

    @PostMapping("/seed")
    public String reseed() {
        seeder.runAsync();
        return "redirect:/";
    }

    private static ServerStatus status(String name, String bean, AbstractRangerClient<?> client, SdkCall probe) {
        return new ServerStatus(name, bean, client.config().urls(), client.config().auth().describe(),
                new LinkedHashMap<>(client.nodeAvailability()), probe);
    }
}
