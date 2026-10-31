package io.datadynamics.ranger.client.pac;

import io.datadynamics.ranger.client.admin.RangerAdminClient;
import io.datadynamics.ranger.client.core.RangerConflictException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyAsCodeTest {
    private static final String SPEC = String.join("\n",
            "service: cm_hive",
            "prune: true",
            "managed_label: gitops",
            "roles:",
            "  - name: analysts",
            "    users: [alice]",
            "policies:",
            "  - name: sales_read",
            "    labels: [gitops]",
            "    resources: {database: sales, table: '*', column: '*'}",
            "    allow:",
            "      - {groups: [analyst], roles: [analysts], accesses: [select]}",
            "    deny:",
            "      - {users: [mallory], accesses: [select]}",
            "  - name: tmp",
            "    labels: [gitops]",
            "    resources: {database: tmp}",
            "    allow: [{users: [bob], accesses: [all]}]",
            "");

    @Test
    void applyThenPlanIsEmpty() {
        try (FakePolicyServer fake = new FakePolicyServer()) {
            PolicyAsCode pac = new PolicyAsCode(RangerAdminClient.builder().urls(fake.url()).build());
            List<PolicySet> specs = List.of(SpecLoader.parseYaml(SPEC, "spec.yaml"));
            Plan plan = pac.plan(specs);
            assertEquals(Map.of(Change.Action.CREATE, 3, Change.Action.UPDATE, 0, Change.Action.DELETE, 0, Change.Action.NOOP, 0),
                    plan.summary());
            assertTrue(plan.render().contains("+ policy cm_hive/sales_read"), plan.render());
            List<Change> applied = pac.apply(plan);
            assertEquals(3, applied.size());
            // roles first: Ranger rejects a policy naming a role that does not exist yet
            assertEquals(Change.Kind.ROLE, applied.get(0).kind());
            assertEquals(List.of("sales_read", "tmp"), fake.names());
            assertTrue(pac.plan(specs).isEmpty());

            // drop "tmp" from the spec: prune deletes it, the label guard keeps hand-made policies
            String smaller = SPEC.substring(0, SPEC.indexOf("  - name: tmp"));
            Plan prune = pac.plan(List.of(SpecLoader.parseYaml(smaller, "spec.yaml")));
            assertEquals(1, prune.pending().size());
            assertEquals(Change.Action.DELETE, prune.pending().get(0).action());
            pac.apply(prune);
            assertEquals(List.of("sales_read"), fake.names());
        }
    }

    @Test
    void applyFailsWhenThePolicyChangedAfterPlanning() {
        try (FakePolicyServer fake = new FakePolicyServer()) {
            PolicyAsCode pac = new PolicyAsCode(RangerAdminClient.builder().urls(fake.url()).build());
            pac.apply(pac.plan(List.of(SpecLoader.parseYaml(SPEC, "spec.yaml"))));
            Plan plan = pac.plan(List.of(SpecLoader.parseYaml(SPEC.replace("[mallory]", "[eve]"), "spec.yaml")));
            fake.policies.values().forEach(p -> p.put("version", 99)); // someone else edits in the UI
            assertThrows(RangerConflictException.class, () -> pac.apply(plan));
        }
    }

    @Test
    void exportRoundTripsToANoopPlan() {
        try (FakePolicyServer fake = new FakePolicyServer()) {
            PolicyAsCode pac = new PolicyAsCode(RangerAdminClient.builder().urls(fake.url()).build());
            pac.apply(pac.plan(List.of(SpecLoader.parseYaml(SPEC, "spec.yaml"))));
            String yaml = PolicyAsCode.toYaml(pac.export("cm_hive", null));
            assertTrue(yaml.contains("name: sales_read"), yaml);
            assertTrue(pac.plan(List.of(SpecLoader.parseYaml(yaml, "export.yaml"))).isEmpty(), yaml);
        }
    }

    @Test
    void specErrorsNameTheSource() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SpecLoader.parseYaml("service: s\npolicies:\n  - resources: {database: x}\n", "bad.yaml"));
        assertTrue(e.getMessage().contains("bad.yaml"), e.getMessage());
    }
}
