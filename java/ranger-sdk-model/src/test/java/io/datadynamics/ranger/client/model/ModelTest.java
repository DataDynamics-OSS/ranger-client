package io.datadynamics.ranger.client.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelTest {
    @Test
    void builderProducesServerShape() {
        RangerPolicy p = RangerPolicy.builder()
                .service("cm_hive").name("sales_read")
                .resource("database", "sales").resource("table", "*")
                .allow(PolicyItem.groups("analyst").accesses("select"))
                .deny(PolicyItem.users("mallory").accesses("select").condition("ip-range", "10.0.0.0/8"))
                .build();
        String json = p.toJson();
        assertEquals("{\"service\":\"cm_hive\",\"name\":\"sales_read\",\"resources\":{\"database\":{\"values\":[\"sales\"]},"
                + "\"table\":{\"values\":[\"*\"]}},\"policyItems\":[{\"accesses\":[{\"type\":\"select\",\"isAllowed\":true}],"
                + "\"groups\":[\"analyst\"]}],\"denyPolicyItems\":[{\"accesses\":[{\"type\":\"select\",\"isAllowed\":true}],"
                + "\"users\":[\"mallory\"],\"conditions\":[{\"type\":\"ip-range\",\"values\":[\"10.0.0.0/8\"]}]}]}", json);
    }

    @Test
    void unknownFieldsSurviveARoundTrip() {
        String in = "{\"id\":1,\"name\":\"n\",\"brandNew\":{\"x\":1},\"policyItems\":[{\"users\":[\"u\"],\"itemFuture\":true}]}";
        RangerPolicy p = RangerJson.read(in, RangerPolicy.class);
        assertEquals(Map.of("x", 1), p.getExtra().get("brandNew"));
        RangerPolicy changed = p.toBuilder().description("d").build();
        String out = changed.toJson();
        assertTrue(out.contains("\"brandNew\":{\"x\":1}"), out);
        assertTrue(out.contains("\"itemFuture\":true"), out);
    }

    @Test
    void modelsAreImmutable() {
        RangerPolicy p = RangerPolicy.builder().policyLabels(List.of("a")).build();
        assertThrows(UnsupportedOperationException.class, () -> p.getPolicyLabels().add("b"));
        assertTrue(p.getPolicyItems().isEmpty());
        assertEquals(p, RangerJson.read(p.toJson(), RangerPolicy.class));
    }

    @Test
    void openEnumKeepsRawValue() {
        AuthzResult r = RangerJson.read("{\"decision\":\"SOMETHING_NEW\"}", AuthzResult.class);
        assertEquals(Decision.UNKNOWN, r.getDecision());
        assertEquals("SOMETHING_NEW", r.getDecisionRaw());
        assertFalse(r.isAllowed());
        assertTrue(RangerJson.read("{\"decision\":\"ALLOW\"}", AuthzResult.class).isAllowed());
    }

    @Test
    void secretsAreMaskedInToString() {
        RangerUser u = RangerUser.builder().name("u").password("pw").build();
        assertFalse(u.toString().contains("pw\""), u.toString());
        assertTrue(u.toJson().contains("\"password\":\"pw\""));
    }

    @Test
    void resourceNames() {
        assertEquals("table:sales/orders", ResourceNames.of("database", "sales", "table", "orders"));
        assertEquals("path:\\/a\\/b", ResourceNames.of("path", "/a/b"));
        assertEquals("table:sales/orders", ResourceInfo.of("database", "sales", "table", "orders").getName());
    }
}
