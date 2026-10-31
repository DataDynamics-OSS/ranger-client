package io.datadynamics.ranger.demo.seed;

import io.datadynamics.ranger.client.admin.RangerAdminClient;
import io.datadynamics.ranger.client.core.Feature;
import io.datadynamics.ranger.client.core.RangerConflictException;
import io.datadynamics.ranger.client.core.RangerNotFoundException;
import io.datadynamics.ranger.client.kms.RangerKmsClient;
import io.datadynamics.ranger.client.model.PolicyResource;
import io.datadynamics.ranger.client.model.RangerGroup;
import io.datadynamics.ranger.client.model.RangerPrincipal;
import io.datadynamics.ranger.client.model.RangerSecurityZone;
import io.datadynamics.ranger.client.model.RangerService;
import io.datadynamics.ranger.client.model.RangerServiceResource;
import io.datadynamics.ranger.client.model.RangerTag;
import io.datadynamics.ranger.client.model.RangerTagDef;
import io.datadynamics.ranger.client.model.RangerUser;
import io.datadynamics.ranger.client.model.TagAttributeDef;
import io.datadynamics.ranger.client.model.ZoneService;
import io.datadynamics.ranger.client.model.ZoneServiceResource;
import io.datadynamics.ranger.client.pac.Plan;
import io.datadynamics.ranger.client.pac.PolicyAsCode;
import io.datadynamics.ranger.client.pac.PolicySet;
import io.datadynamics.ranger.client.pac.SpecLoader;
import io.datadynamics.ranger.demo.trace.SdkCall;
import io.datadynamics.ranger.demo.trace.SdkCalls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Puts the demo data into Ranger with the SDK. Every step is an upsert, so re-running (or restarting the app)
 * leaves the same state. Runs in the background after startup and waits for Admin to answer.
 */
@Component
public class DemoSeeder {
    public static final String SERVICE = "demo_hive";
    public static final String TAG_SERVICE = "demo_tag";
    public static final String DEMO_KEY = "demo-key";
    /** Password of the demo users (Ranger requires 8+ chars with letters and digits). */
    static final String USER_PASSWORD = "Demo1234!";

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    public enum State { NOT_STARTED, WAITING_FOR_ADMIN, RUNNING, DONE, FAILED }

    public record Step(String title, SdkCall call) {
    }

    private final RangerAdminClient admin;
    private final Optional<RangerKmsClient> kms;
    private final SdkCalls calls;
    private final boolean enabled;
    private final Duration timeout;
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile State state = State.NOT_STARTED;
    private volatile List<Step> steps = List.of();
    private volatile Instant finishedAt;
    private volatile String message;

    public DemoSeeder(RangerAdminClient admin, Optional<RangerKmsClient> kms, SdkCalls calls,
                      @Value("${demo.seed.enabled:true}") boolean enabled,
                      @Value("${demo.seed.timeout:5m}") Duration timeout) {
        this.admin = admin;
        this.kms = kms;
        this.calls = calls;
        this.enabled = enabled;
        this.timeout = timeout;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (enabled) {
            runAsync();
        }
    }

    /** Starts a run in the background unless one is in progress. */
    public boolean runAsync() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        Thread t = new Thread(() -> {
            try {
                run();
            } finally {
                running.set(false);
            }
        }, "demo-seeder");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private void run() {
        List<Step> done = new CopyOnWriteArrayList<>();
        steps = done;
        message = null;
        try {
            state = State.WAITING_FOR_ADMIN;
            waitForAdmin();
            state = State.RUNNING;

            for (String[] u : new String[][] {{"alice", "Alice"}, {"bob", "Bob"}, {"mallory", "Mallory"}}) {
                step(done, "user " + u[0], "admin.users().create(RangerUser.builder().name(\"" + u[0] + "\")...)  // if absent",
                        () -> admin.users().find(u[0]).orElseGet(() -> admin.users().create(RangerUser.builder()
                                .name(u[0]).firstName(u[1]).password(USER_PASSWORD)
                                .userRoleList(List.of("ROLE_USER")).status(1).build())));
            }
            for (String g : List.of("analyst", "engineer")) {
                step(done, "group " + g, "admin.groups().create(RangerGroup.builder().name(\"" + g + "\").build())  // if absent",
                        () -> admin.groups().find(g).orElseGet(() -> admin.groups().create(
                                RangerGroup.builder().name(g).description("Demo group").build())));
            }
            for (String[] m : new String[][] {{"analyst", "alice"}, {"engineer", "bob"}}) {
                step(done, m[1] + " in " + m[0], "admin.groups().addUser(\"" + m[0] + "\", \"" + m[1] + "\")  // if not a member",
                        () -> {
                            long uid = admin.users().getByName(m[1]).getId();
                            boolean member = admin.users().groups(uid).stream().anyMatch(g -> m[0].equals(g.getName()));
                            return member ? "already a member" : admin.groups().addUser(m[0], m[1]);
                        });
            }

            step(done, "tag service", "admin.services().apply(RangerService.builder().name(\"" + TAG_SERVICE + "\").type(\"tag\")...)",
                    () -> admin.services().apply(RangerService.builder().name(TAG_SERVICE).type("tag")
                            .description("Tag-based policies for the SDK demo").build()));
            step(done, "hive service", "admin.services().apply(RangerService.builder().name(\"" + SERVICE + "\").type(\"hive\").tagService(\"" + TAG_SERVICE + "\")...)",
                    () -> admin.services().apply(RangerService.builder().name(SERVICE).type("hive").tagService(TAG_SERVICE)
                            .description("Hive service for the SDK demo (no real HiveServer2 behind it)")
                            .configs(Map.of("username", "hive", "password", "hive",
                                    "jdbc.driverClassName", "org.apache.hive.jdbc.HiveDriver",
                                    "jdbc.url", "jdbc:hive2://hive.example:10000"))
                            .build()));

            String yaml = new String(new ClassPathResource("seed/demo_hive.yaml").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            List<PolicySet> specs = List.of(SpecLoader.parseYaml(yaml, "seed/demo_hive.yaml"));
            PolicyAsCode pac = new PolicyAsCode(admin);
            SdkCall plan = step(done, "policy plan", "new PolicyAsCode(admin).plan(SpecLoader.parseYaml(yaml, \"seed/demo_hive.yaml\"))",
                    () -> pac.plan(specs));
            if (plan.ok() && !((Plan) plan.value()).isEmpty()) {
                step(done, "policy apply", "pac.apply(plan)", () -> pac.apply(plan.value()));
            }

            if (admin.capabilities().supports(Feature.ZONES_V2)) {
                step(done, "zone finance", "admin.zones().create(RangerSecurityZone.builder().name(\"finance\")...)  // if absent",
                        () -> findZone("finance").orElseGet(() -> admin.zones().create(RangerSecurityZone.builder()
                                .name("finance").description("Finance data, administered separately")
                                .services(Map.of(SERVICE, ZoneService.builder().resources(List.of(
                                        ZoneServiceResource.builder().resource(Map.of("database", List.of("finance"))).build())).build()))
                                .admins(List.of(RangerPrincipal.builder().type("USER").name("admin").build()))
                                .auditors(List.of(RangerPrincipal.builder().type("USER").name("admin").build()))
                                .build())));
            }

            seedTags(done);

            if (kms.isPresent()) {
                RangerKmsClient k = kms.get();
                // KMS starts after Admin and answers 403 until its plugin has downloaded the dev_kms policies.
                waitFor("kms.names()  // wait for KMS and its policies", k::names);
                step(done, "kms key", "kms.createKey(\"" + DEMO_KEY + "\")  // if absent",
                        () -> k.names().contains(DEMO_KEY) ? k.metadata(DEMO_KEY) : k.createKey(DEMO_KEY));
            }

            boolean failed = done.stream().anyMatch(s -> !s.call().ok());
            state = failed ? State.FAILED : State.DONE;
            message = failed ? "Some steps failed; see below." : null;
        } catch (Exception e) {
            state = State.FAILED;
            message = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("Demo seeding failed", e);
        } finally {
            finishedAt = Instant.now();
        }
    }

    private void seedTags(List<Step> done) {
        step(done, "tag def PII", "admin.tags().createDef(RangerTagDef.builder().name(\"PII\")..., true)",
                () -> admin.tags().createDef(RangerTagDef.builder().name("PII").source("demo")
                        .attributeDefs(List.of(TagAttributeDef.builder().name("level").type("string").build())).build(), true));
        SdkCall tag = step(done, "tag PII", "admin.tags().create(RangerTag.builder().type(\"PII\")..., true)",
                () -> admin.tags().byType("PII").stream().findFirst().orElseGet(() -> admin.tags().create(
                        RangerTag.builder().type("PII").attributes(Map.of("level", "high")).build(), true)));
        SdkCall resource = step(done, "tagged resource", "admin.tags().createResource(RangerServiceResource.builder().serviceName(\"" + SERVICE + "\")..., true)",
                () -> admin.tags().createResource(RangerServiceResource.builder().serviceName(SERVICE)
                        .resourceElements(Map.of("database", PolicyResource.of("sales"), "table", PolicyResource.of("customers"),
                                "column", PolicyResource.of("email")))
                        .build(), true));
        if (tag.ok() && resource.ok()) {
            RangerTag t = tag.value();
            RangerServiceResource r = resource.value();
            step(done, "tag mapping", "admin.tags().map(tag.getGuid(), resource.getGuid())  // if not mapped",
                    () -> {
                        try {
                            return admin.tags().map(t.getGuid(), r.getGuid());
                        } catch (RangerConflictException e) {
                            return "already mapped";
                        }
                    });
        }
    }

    private Optional<RangerSecurityZone> findZone(String name) {
        try {
            return Optional.of(admin.zones().getByName(name));
        } catch (RangerNotFoundException e) {
            return Optional.empty();
        }
    }

    private void waitForAdmin() throws InterruptedException {
        waitFor("admin.serviceDefs().getByName(\"hive\")  // wait for Admin", () -> admin.serviceDefs().getByName("hive"));
    }

    /** Repeats a probe with backoff until it succeeds or the seed timeout passes. */
    private void waitFor(String code, Callable<?> probeCall) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        long delay = 2_000;
        while (true) {
            SdkCall probe = calls.run(code, probeCall);
            if (probe.ok()) {
                return;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("No answer within " + timeout + " from " + code + ": " + probe.error().getMessage());
            }
            log.info("Waiting: {} -> {}", code, probe.error().toString());
            Thread.sleep(delay);
            delay = Math.min(delay * 2, 15_000);
        }
    }

    private SdkCall step(List<Step> done, String title, String code, Callable<?> call) {
        SdkCall c = calls.run(code, call);
        done.add(new Step(title, c));
        if (!c.ok()) {
            log.warn("Seed step '{}' failed: {}", title, c.error().toString());
        }
        return c;
    }

    public State state() {
        return state;
    }

    public List<Step> steps() {
        return List.copyOf(steps);
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public String message() {
        return message;
    }

    public boolean isRunning() {
        return running.get();
    }
}
