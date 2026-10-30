package io.datadynamics.ranger.client.pdp;

import io.datadynamics.ranger.client.core.auth.TrustedHeaderAuth;
import io.datadynamics.ranger.client.model.AccessInfo;
import io.datadynamics.ranger.client.model.MultiAuthzResult;
import io.datadynamics.ranger.client.model.ResourceInfo;
import io.datadynamics.ranger.client.model.UserInfo;
import io.datadynamics.ranger.client.testing.MockServer;
import io.datadynamics.ranger.client.testing.MockServer.Reply;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RangerPdpClientTest {
    private static final String ALLOW = "{\"decision\":\"ALLOW\"}";

    @Test
    void cacheIgnoresRequestIdAndAccessTime() {
        try (MockServer s = new MockServer()) {
            s.handler(r -> Reply.of(200, ALLOW));
            RangerPdpClient pdp = RangerPdpClient.builder().urls(s.url()).service("dev_hive", "hive")
                    .cache(Duration.ofMinutes(1), 100).build();
            ResourceInfo res = ResourceInfo.of("database", "sales");
            assertTrue(pdp.isAllowed("alice", res, "select"));
            assertTrue(pdp.isAllowed("alice", res, "select"));
            assertEquals(1, s.requests().size());
            pdp.isAllowed("bob", res, "select");
            assertEquals(2, s.requests().size());
        }
    }

    @Test
    void noCacheByDefaultAndPostsAreRetried() {
        try (MockServer s = new MockServer()) {
            s.enqueue(Reply.of(503)).enqueue(Reply.of(200, ALLOW)).enqueue(Reply.of(200, "{\"decision\":\"DENY\"}"));
            RangerPdpClient pdp = RangerPdpClient.builder().urls(s.url()).service("dev_hive", "hive")
                    .sleeper(d -> { }).build();
            ResourceInfo res = ResourceInfo.of("database", "sales");
            assertTrue(pdp.isAllowed("alice", res, "select"));
            assertFalse(pdp.isAllowed("alice", res, "select"));
            assertEquals(3, s.requests().size());
        }
    }

    @Test
    void trustedHeaderAndMulti() {
        try (MockServer s = new MockServer()) {
            s.enqueue(Reply.of(200, "{\"decision\":\"PARTIAL\",\"accesses\":[{\"decision\":\"ALLOW\"},{\"decision\":\"DENY\"}]}"));
            RangerPdpClient pdp = RangerPdpClient.builder().urls(s.url()).auth(TrustedHeaderAuth.of("trino"))
                    .service("dev_hive", "hive").build();
            MultiAuthzResult r = pdp.authorizeMulti(UserInfo.builder().name("alice").build(), List.of(
                    AccessInfo.builder().resource(ResourceInfo.of("database", "a")).permissions(List.of("select")).build(),
                    AccessInfo.builder().resource(ResourceInfo.of("database", "b")).permissions(List.of("select")).build()));
            assertEquals("PARTIAL", r.getDecisionRaw());
            assertTrue(r.getAccesses().get(0).isAllowed());
            assertEquals("/authz/v1/authorizeMulti", s.last().rawPath);
            assertEquals("trino", s.last().header("X-Forwarded-User"));
            assertTrue(s.last().bodyText().contains("\"serviceName\":\"dev_hive\""));
        }
    }
}
