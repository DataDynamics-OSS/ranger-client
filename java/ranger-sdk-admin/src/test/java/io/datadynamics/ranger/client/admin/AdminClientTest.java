package io.datadynamics.ranger.client.admin;

import io.datadynamics.ranger.client.core.Feature;
import io.datadynamics.ranger.client.core.RangerConflictException;
import io.datadynamics.ranger.client.core.RetryPolicy;
import io.datadynamics.ranger.client.core.UnsupportedFeatureException;
import io.datadynamics.ranger.client.model.PolicyItem;
import io.datadynamics.ranger.client.model.RangerGroupUser;
import io.datadynamics.ranger.client.model.RangerPolicy;
import io.datadynamics.ranger.client.model.RangerUser;
import io.datadynamics.ranger.client.testing.MockServer;
import io.datadynamics.ranger.client.testing.MockServer.Reply;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminClientTest {
    private static final String P = "/service/public/v2/api";

    private static RangerAdminClient client(MockServer s) {
        return RangerAdminClient.builder().urls(s.url()).retry(RetryPolicy.exponential(2, Duration.ZERO)).build();
    }

    @Test
    void streamWalksAllPages() {
        try (MockServer s = new MockServer()) {
            s.handler(r -> {
                int start = Integer.parseInt(r.query.get("startIndex"));
                int size = Integer.parseInt(r.query.get("pageSize"));
                int total = 5;
                StringBuilder sb = new StringBuilder("[");
                for (int i = start; i < Math.min(total, start + size); i++) {
                    sb.append(i > start ? "," : "").append("{\"id\":").append(i).append('}');
                }
                return Reply.of(200, sb.append(']').toString());
            });
            List<Long> ids = client(s).policies().stream(PolicyFilter.service("svc").limit(2))
                    .map(RangerPolicy::getId).collect(Collectors.toList());
            assertEquals(List.of(0L, 1L, 2L, 3L, 4L), ids);
            assertEquals(3, s.requests().size());
        }
    }

    @Test
    void applyCreatesWhenAbsentAndUpdatesWhenPresent() {
        try (MockServer s = new MockServer()) {
            RangerPolicy p = RangerPolicy.builder().service("svc").name("p").build();

            s.enqueue(Reply.of(404)).enqueue(Reply.of(200, "{\"id\":1,\"service\":\"svc\",\"name\":\"p\"}"));
            assertEquals(1L, client(s).policies().apply(p).getId());
            assertEquals("POST", s.last().method);

            s.enqueue(Reply.of(200, "{\"id\":9,\"guid\":\"g\",\"version\":4,\"service\":\"svc\",\"name\":\"p\"}"))
             .enqueue(Reply.of(200, "{\"id\":9}"));
            client(s).policies().apply(p.toBuilder().description("d").build());
            assertEquals("PUT", s.last().method);
            assertEquals(P + "/policy/9", s.last().rawPath);
            assertTrue(s.last().bodyText().contains("\"version\":4"), s.last().bodyText());
        }
    }

    @Test
    void updateDetectsConcurrentChange() {
        try (MockServer s = new MockServer()) {
            s.enqueue(Reply.of(200, "{\"id\":3,\"version\":5}"));
            RangerPolicy stale = RangerPolicy.builder().id(3L).version(4L).name("p").build();
            assertThrows(RangerConflictException.class, () -> client(s).policies().update(stale));
            assertEquals(1, s.requests().size()); // nothing written
        }
    }

    @Test
    void removePrincipalsDropsEmptyItems() {
        try (MockServer s = new MockServer()) {
            s.enqueue(Reply.of(200, "{\"id\":3,\"version\":1,\"service\":\"svc\",\"name\":\"p\",\"policyItems\":["
                    + "{\"users\":[\"a\"],\"accesses\":[{\"type\":\"select\"}]},"
                    + "{\"users\":[\"a\",\"b\"],\"accesses\":[{\"type\":\"update\"}]}]}"))
             .enqueue(Reply.of(200, "{\"id\":3,\"version\":1}"))
             .enqueue(Reply.of(200, "{\"id\":3,\"version\":2}"));
            client(s).policies().removePrincipals("svc", "p", PoliciesClient.ItemKind.ALLOW, Set.of("a"), Set.of(), Set.of());
            String body = s.last().bodyText();
            assertTrue(body.contains("\"policyItems\":[{\"accesses\":[{\"type\":\"update\"}],\"users\":[\"b\"]"), body);
        }
    }

    @Test
    void addItemsAppends() {
        try (MockServer s = new MockServer()) {
            s.enqueue(Reply.of(200, "{\"id\":3,\"service\":\"svc\",\"name\":\"p\",\"policyItems\":[{\"users\":[\"a\"]}]}"))
             .enqueue(Reply.of(200, "{\"id\":3}"));
            client(s).policies().addItems("svc", "p", PoliciesClient.ItemKind.DENY,
                    List.of(PolicyItem.users("m").accesses("select").build()));
            assertTrue(s.last().bodyText().contains("\"denyPolicyItems\":[{\"accesses\":[{\"type\":\"select\",\"isAllowed\":true}],\"users\":[\"m\"]}]"));
        }
    }

    @Test
    void xusersUnwrapsListEnvelopes() {
        try (MockServer s = new MockServer()) {
            s.enqueue(Reply.of(200, "{\"startIndex\":0,\"pageSize\":2,\"vXUsers\":[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"}]}"));
            List<RangerUser> users = client(s).users().search("a", 0, 2);
            assertEquals(List.of("a", "b"), users.stream().map(RangerUser::getName).collect(Collectors.toList()));
            assertEquals("/service/xusers/users", s.last().rawPath);
            assertEquals("a", s.last().query.get("name"));
        }
    }

    @Test
    void groupMembership() {
        try (MockServer s = new MockServer()) {
            s.enqueue(Reply.of(200, "{\"id\":2,\"name\":\"g\"}")).enqueue(Reply.of(200, "{\"id\":3,\"name\":\"u\"}"))
             .enqueue(Reply.of(200, "{\"id\":7,\"name\":\"g\",\"parentGroupId\":2,\"userId\":3}")).enqueue(Reply.of(204));
            RangerAdminClient admin = client(s);
            RangerGroupUser gu = admin.groups().addUser("g", "u");
            assertEquals(7L, gu.getId());
            assertEquals("/service/xusers/groups/groupName/g", s.requests().get(0).rawPath);
            assertEquals("/service/xusers/users/userName/u", s.requests().get(1).rawPath);
            assertEquals("{\"name\":\"g\",\"parentGroupId\":2,\"userId\":3}", s.last().bodyText());
            admin.groups().removeUser("dev ops", "u");
            assertEquals("/service/xusers/group/dev%20ops/user/u", s.last().rawPath);
        }
    }

    @Test
    void tagResourceMapUsesGuidParameters() {
        try (MockServer s = new MockServer()) {
            s.enqueue(Reply.of(200, "{\"id\":1,\"tagId\":2,\"resourceId\":3}"));
            assertEquals(2L, client(s).tags().map("tg", "rg").getTagId());
            assertEquals("/service/tags/tagresourcemaps/", s.last().rawPath);
            assertEquals(Map.of("tag-guid", "tg", "resource-guid", "rg"), s.last().query);
        }
    }

    @Test
    void importJsonIsMultipart() {
        try (MockServer s = new MockServer()) {
            s.enqueue(Reply.of(200));
            client(s).policies().importJson(Map.of("policies", List.of()), true, Map.of("a", "b"), null);
            assertTrue(s.last().header("Content-Type").startsWith("multipart/form-data; boundary="));
            String body = s.last().bodyText();
            assertTrue(body.contains("name=\"file\"") && body.contains("name=\"servicesMapJson\""), body);
            assertFalse(body.contains("zoneMapJson"));
            assertEquals("true", s.last().query.get("isOverride"));
        }
    }

    @Test
    void gdsNeedsRanger3() {
        try (MockServer s = new MockServer()) {
            RangerAdminClient admin = RangerAdminClient.builder().urls(s.url()).serverVersion("2.9.0").build();
            assertFalse(admin.capabilities().supports(Feature.GDS));
            assertThrows(UnsupportedFeatureException.class, () -> admin.gds().get("dataset", 1));
            assertTrue(s.requests().isEmpty());
        }
    }
}
