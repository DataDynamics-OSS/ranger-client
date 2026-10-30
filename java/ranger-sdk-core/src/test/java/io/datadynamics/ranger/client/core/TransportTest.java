package io.datadynamics.ranger.client.core;

import io.datadynamics.ranger.client.core.auth.AuthProvider;
import io.datadynamics.ranger.client.core.auth.BasicAuth;
import io.datadynamics.ranger.client.core.auth.JwtAuth;
import io.datadynamics.ranger.client.core.auth.TrustedHeaderAuth;
import io.datadynamics.ranger.client.core.http.HttpCall;
import io.datadynamics.ranger.client.core.http.Masking;
import io.datadynamics.ranger.client.core.http.Transport;
import io.datadynamics.ranger.client.testing.MockServer;
import io.datadynamics.ranger.client.testing.MockServer.Reply;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportTest {
    private static final String DEAD = "http://127.0.0.1:9"; // discard port: connection refused

    @Test
    void failsOverToTheNextNodeAndRemembersTheDeadOne() {
        try (MockServer s = new MockServer()) {
            s.enqueue(Reply.of(200, "{\"id\":1}")).enqueue(Reply.of(200, "{\"id\":2}"));
            Transport t = TestClient.admin(DEAD, s.url()).build();
            assertEquals(1, t.send(HttpCall.get("/x")).as(Map.class).get("id"));
            assertEquals(2, t.send(HttpCall.get("/x")).as(Map.class).get("id"));
            assertEquals(2, s.requests().size());
        }
    }

    @Test
    void allNodesDownGivesTransportException() {
        Transport t = TestClient.admin(DEAD).build();
        RangerTransportException e = assertThrows(RangerTransportException.class, () -> t.send(HttpCall.get("/x")));
        assertNotNull(e.getRequestId());
        assertNull(e.getStatus());
    }

    @Test
    void retriesIdempotentCallsOn5xxButNotPosts() {
        try (MockServer s = new MockServer()) {
            s.enqueue(Reply.of(503)).enqueue(Reply.of(200, "{}"));
            Transport t = TestClient.admin(s.url()).build();
            t.send(HttpCall.get("/x"));
            assertEquals(2, s.requests().size());

            s.enqueue(Reply.of(503)).enqueue(Reply.of(200, "{}"));
            assertThrows(RangerServerException.class, () -> t.send(HttpCall.post("/x").json(Map.of())));
            assertEquals(3, s.requests().size());
        }
    }

    @Test
    void requestIdIsStableAcrossRetries() {
        try (MockServer s = new MockServer()) {
            s.enqueue(Reply.of(500)).enqueue(Reply.of(200, "{}"));
            TestClient.admin(s.url()).build().send(HttpCall.get("/x"));
            assertEquals(s.requests().get(0).header("X-Request-Id"), s.requests().get(1).header("X-Request-Id"));
        }
    }

    @Test
    void honoursRetryAfter() {
        List<Duration> slept = new ArrayList<>();
        try (MockServer s = new MockServer()) {
            s.enqueue(Reply.of(429).header("Retry-After", "2")).enqueue(Reply.of(200, "{}"));
            new TestClient(ServerKind.ADMIN).urls(s.url()).sleeper(slept::add).build().send(HttpCall.get("/x"));
            assertEquals(List.of(Duration.ofSeconds(2)), slept);
        }
    }

    @Test
    void jwtIsRefreshedOnceOn401() {
        Iterator<String> tokens = List.of("t1", "t2").iterator();
        try (MockServer s = new MockServer()) {
            s.enqueue(Reply.of(401)).enqueue(Reply.of(200, "{}"));
            TestClient.admin(s.url()).auth(JwtAuth.supplier(tokens::next)).build().send(HttpCall.get("/x"));
            assertEquals("Bearer t1", s.requests().get(0).header("Authorization"));
            assertEquals("Bearer t2", s.requests().get(1).header("Authorization"));
        }
    }

    @Test
    void secondUnauthorizedIsAnAuthenticationError() {
        try (MockServer s = new MockServer()) {
            s.handler(r -> Reply.of(401));
            Transport t = TestClient.admin(s.url()).auth(JwtAuth.supplier(() -> "t")).build();
            assertThrows(RangerAuthenticationException.class, () -> t.send(HttpCall.get("/x")));
            assertEquals(2, s.requests().size());
        }
    }

    @Test
    void sessionCookieSkipsNegotiation() {
        AtomicInteger negotiations = new AtomicInteger();
        AuthProvider negotiate = new AuthProvider() {
            @Override
            public Map<String, String> headers(URI target) {
                negotiations.incrementAndGet();
                return Map.of("Authorization", "Negotiate abc");
            }

            @Override
            public boolean supportsSessionReuse() {
                return true;
            }
        };
        try (MockServer s = new MockServer()) {
            s.enqueue(Reply.of(200, "{}").header("Set-Cookie", "RANGERADMINSESSIONID=s1; Path=/")).enqueue(Reply.of(200, "{}"));
            Transport t = TestClient.admin(s.url()).auth(negotiate).build();
            t.send(HttpCall.get("/x"));
            t.send(HttpCall.get("/x"));
            assertEquals(1, negotiations.get());
            assertNull(s.last().header("Authorization"));
            assertTrue(s.last().header("Cookie").contains("RANGERADMINSESSIONID=s1"));
        }
    }

    @Test
    void basicAuthHeaderAndNoSecretInToString() {
        try (MockServer s = new MockServer()) {
            s.enqueue(Reply.of(200, "{}"));
            BasicAuth auth = BasicAuth.of("admin", "s3cret".toCharArray());
            TestClient.admin(s.url()).auth(auth).build().send(HttpCall.get("/x"));
            assertEquals("Basic YWRtaW46czNjcmV0", s.last().header("Authorization"));
            assertFalse(auth.toString().contains("s3cret"));
        }
    }

    @Test
    void trustedHeaderIsPdpOnly() {
        assertThrows(IllegalArgumentException.class, () -> TestClient.admin("http://x").auth(TrustedHeaderAuth.of("svc")).build());
    }

    @Test
    void errorMappingFollowsTheContract() {
        try (MockServer s = new MockServer()) {
            Transport t = TestClient.admin(s.url()).build();
            s.enqueue(Reply.of(400, "{\"msgDesc\":\"policy-name already exists\"}"));
            assertThrows(RangerConflictException.class, () -> t.send(HttpCall.post("/x")));
            s.enqueue(Reply.of(400, "{\"msgDesc\":\"bad\",\"messageList\":[{\"name\":\"INVALID\",\"fieldName\":\"resources\"}]}"));
            RangerValidationException v = assertThrows(RangerValidationException.class, () -> t.send(HttpCall.post("/x")));
            assertEquals("resources", v.getFields().get(0).get("fieldName"));
            assertEquals("bad", v.getServerMessage());
            s.enqueue(Reply.of(403, "{\"code\":403,\"message\":\"svc is not authorized\"}"));
            RangerAccessDeniedException d = assertThrows(RangerAccessDeniedException.class, () -> t.send(HttpCall.get("/x")));
            assertTrue(d.getMessage().contains("svc is not authorized"));
            s.enqueue(Reply.of(404, "{\"RemoteException\":{\"message\":\"Key k does not exist\"}}"));
            assertEquals("Key k does not exist",
                    assertThrows(RangerNotFoundException.class, () -> t.send(HttpCall.get("/x"))).getServerMessage());
        }
    }

    @Test
    void listenersSeeEveryAttempt() {
        List<String> events = new ArrayList<>();
        try (MockServer s = new MockServer()) {
            s.enqueue(Reply.of(503)).enqueue(Reply.of(200, "{}"));
            TestClient.admin(s.url()).listener(new RequestListener() {
                @Override
                public void onResponse(String method, URI uri, int status, Duration elapsed, int attempt, String requestId) {
                    events.add(attempt + ":" + status);
                }
            }).build().send(HttpCall.get("/x"));
        }
        assertEquals(List.of("1:503", "2:200"), events);
    }

    @Test
    void interceptorsAddHeadersPerAttemptAndSeeEachOutcome() {
        List<String> events = new ArrayList<>();
        RequestInterceptor tracing = (server, method, uri, attempt, id, header) -> {
            header.accept("traceparent", "tp-" + attempt);
            events.add("begin " + server + " " + method + " " + uri.getPath() + " #" + attempt);
            return new RequestInterceptor.Attempt() {
                @Override
                public void onResponse(int status) {
                    events.add("status " + status);
                }

                @Override
                public void onError(Throwable error) {
                    events.add("error " + error.getClass().getSimpleName());
                }
            };
        };
        RequestInterceptor broken = (server, method, uri, attempt, id, header) -> {
            throw new IllegalStateException("must not break the call");
        };
        try (MockServer s = new MockServer()) {
            s.enqueue(Reply.of(503)).enqueue(Reply.of(200, "{}"));
            TestClient.admin(s.url()).interceptor(broken).interceptor(tracing).build().send(HttpCall.get("/x"));
            assertEquals("tp-1", s.requests().get(0).header("traceparent"));
            assertEquals("tp-2", s.last().header("traceparent"));
        }
        assertEquals(List.of("begin ADMIN GET /x #1", "status 503", "begin ADMIN GET /x #2", "status 200"), events);

        events.clear();
        Transport dead = TestClient.admin(DEAD).retry(RetryPolicy.none()).interceptor(tracing).build();
        assertThrows(RangerTransportException.class, () -> dead.send(HttpCall.get("/y")));
        assertEquals(List.of("begin ADMIN GET /y #1", "error ConnectException"), events);
    }

    @Test
    void masking() {
        assertEquals("{\"password\":\"***\",\"name\":\"n\"}", Masking.body("{\"password\":\"p\",\"name\":\"n\"}"));
        assertEquals("***", Masking.headers(Map.of("Authorization", "Basic x")).get("Authorization"));
    }

    @Test
    void versionsCompare() {
        assertTrue(Versions.compare("2.9.0", "2.10.0") < 0);
        assertTrue(Versions.compare("2.9.0.7.3.1", "2.9.0") > 0);
        assertEquals(0, Versions.compare("3.0.0-SNAPSHOT", "3.0.0"));
    }

    @Test
    void capabilitiesFromDeclaredVersion() {
        Transport t = TestClient.admin("http://unused").serverVersion("2.9.0").build();
        Capabilities c = new Capabilities(t);
        assertTrue(c.supports(Feature.ZONES_V2));
        assertFalse(c.supports(Feature.GDS));
        assertFalse(c.supports(Feature.PDP)); // another server
        assertThrows(UnsupportedFeatureException.class, () -> c.require(Feature.GDS));
    }

    @Test
    void capabilitiesProbeOnceAndTreat403AsPresent() {
        try (MockServer s = new MockServer()) {
            s.handler(r -> r.rawPath.startsWith("/service/gds") ? Reply.of(404) : Reply.of(403));
            Capabilities c = new Capabilities(TestClient.admin(s.url()).build());
            assertFalse(c.supports(Feature.GDS));
            assertFalse(c.supports(Feature.GDS));
            assertTrue(c.supports(Feature.ZONES_V2));
            assertEquals(2, s.requests().size());
        }
    }
}
