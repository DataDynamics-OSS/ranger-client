package io.datadynamics.ranger.client.contract;

import io.datadynamics.ranger.client.admin.RangerAdminClient;
import io.datadynamics.ranger.client.pac.Change;
import io.datadynamics.ranger.client.pac.Plan;
import io.datadynamics.ranger.client.pac.PolicyAsCode;
import io.datadynamics.ranger.client.pac.SpecLoader;
import io.datadynamics.ranger.client.testing.MockServer;
import io.datadynamics.ranger.client.testing.MockServer.Reply;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Runs spec/contract/pac.yaml (shared with python/tests/test_pac_contract.py): same input, same plan. */
class PacContractTest {
    @TestFactory
    Stream<DynamicTest> cases() {
        return Spec.cases("pac.yaml", "cases").stream()
                .map(c -> DynamicTest.dynamicTest((String) c.get("name"), () -> run(c)));
    }

    @SuppressWarnings("unchecked")
    private static void run(Map<String, Object> c) {
        List<Map<String, Object>> policies = (List<Map<String, Object>>) c.get("server");
        try (MockServer server = new MockServer()) {
            server.handler(req -> {
                if (req.method.equals("GET") && req.rawPath.equals("/service/public/v2/api/policy")) {
                    int start = Integer.parseInt(req.query.getOrDefault("startIndex", "0"));
                    List<Map<String, Object>> hits = policies.stream()
                            .filter(p -> p.get("service").equals(req.query.get("serviceName")))
                            .skip(start).collect(Collectors.toList());
                    return Reply.of(200, Spec.json(hits));
                }
                return Reply.of(404, "{\"msgDesc\":\"not in fake\"}");
            });
            RangerAdminClient admin = RangerAdminClient.builder().urls(server.url()).build();
            Plan plan = new PolicyAsCode(admin).plan(List.of(SpecLoader.parse(c.get("spec"), "<contract>")));

            Map<String, Object> expect = (Map<String, Object>) c.get("expect");
            Map<String, Object> summary = new LinkedHashMap<>();
            plan.summary().forEach((k, v) -> summary.put(k.name().toLowerCase(), v));
            Spec.subset(expect.get("summary"), summary, "summary");

            List<List<Object>> changes = new ArrayList<>();
            for (Change ch : plan.pending()) {
                List<Object> row = new ArrayList<>(List.of(ch.action().name().toLowerCase(), ch.name()));
                if (ch.action() == Change.Action.UPDATE) {
                    row.add(ch.diff().stream().map(d -> d.field).collect(Collectors.toList()));
                }
                changes.add(row);
            }
            assertEquals(expect.get("changes"), changes);
        }
    }
}
