package io.datadynamics.ranger.client.spring;

import io.datadynamics.ranger.client.core.AbstractRangerClient;
import io.datadynamics.ranger.client.core.RangerAccessDeniedException;
import io.datadynamics.ranger.client.core.RangerAuthenticationException;
import io.datadynamics.ranger.client.core.RangerException;
import io.datadynamics.ranger.client.core.RangerTransportException;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Health of one Ranger server: runs a cheap authenticated call and reports the outcome together with
 * which nodes are in rotation. Authentication or authorization failures are reported as DOWN with
 * {@code reachable=true}, so they can be told apart from an outage.
 */
public class RangerHealthIndicator<C extends AbstractRangerClient<C>> extends AbstractHealthIndicator {
    private final C client;
    private final Function<C, Map<String, Object>> probe;

    /** @param probe the check; returns extra details, throws {@link RangerException} when unhealthy */
    public RangerHealthIndicator(C client, Function<C, Map<String, Object>> probe) {
        super("Ranger health check failed");
        this.client = client;
        this.probe = probe;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        try {
            Map<String, Object> details = probe.apply(client);
            builder.up().withDetails(details);
        } catch (RangerException e) {
            builder.down()
                    .withDetail("reachable", !(e instanceof RangerTransportException))
                    .withDetail("error", e.getClass().getSimpleName())
                    .withDetail("message", e.getServerMessage() != null ? e.getServerMessage() : e.getMessage());
            if (e.getStatus() != null) {
                builder.withDetail("status", e.getStatus());
            }
            if (e instanceof RangerAuthenticationException || e instanceof RangerAccessDeniedException) {
                builder.withDetail("hint", "server reachable; check credentials / permissions of " + client.config().auth().describe());
            }
        }
        builder.withDetail("auth", client.config().auth().describe()).withDetail("nodes", nodes());
    }

    private Map<String, String> nodes() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<URI, Boolean> e : client.nodeAvailability().entrySet()) {
            out.put(e.getKey().toString(), e.getValue() ? "UP" : "COOLDOWN");
        }
        return out;
    }
}
