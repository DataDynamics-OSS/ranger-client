package io.datadynamics.ranger.client.core.http;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EndpointsTest {
    private static final URI A = URI.create("http://a"), B = URI.create("http://b");

    @Test
    void roundRobinSkipsNodesInCooldown() {
        Endpoints e = new Endpoints(List.of(A, B), Duration.ofSeconds(30));
        assertEquals(A, e.pick(Set.of()));
        assertEquals(B, e.pick(Set.of()));
        e.markDown(A);
        assertEquals(B, e.pick(Set.of()));
        assertEquals(B, e.pick(Set.of()));
        e.markUp(A);
        assertEquals(A, e.pick(Set.of()));
    }

    @Test
    void availabilityReflectsCooldown() {
        long[] now = {0};
        Endpoints e = new Endpoints(List.of(A, B), Duration.ofSeconds(30), () -> now[0]);
        e.markDown(B);
        assertEquals(java.util.Map.of(A, true, B, false), e.availability());
        now[0] = Duration.ofSeconds(31).toNanos();
        assertEquals(java.util.Map.of(A, true, B, true), e.availability());
    }

    @Test
    void excludedNodesAreAvoidedUntilNoneRemain() {
        Endpoints e = new Endpoints(List.of(A, B), Duration.ofSeconds(30));
        assertEquals(B, e.pick(Set.of(A)));
        e.markDown(A);
        e.markDown(B);
        assertEquals(A, e.pick(Set.of(A, B))); // everything down: the one recovering first
    }
}
