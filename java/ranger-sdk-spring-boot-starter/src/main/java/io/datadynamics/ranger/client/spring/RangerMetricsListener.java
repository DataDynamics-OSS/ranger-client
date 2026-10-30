package io.datadynamics.ranger.client.spring;

import io.datadynamics.ranger.client.core.RequestListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.net.URI;
import java.time.Duration;

/**
 * Records every HTTP attempt of one client.
 *
 * <ul>
 *   <li>{@code ranger.client.requests} (timer): tags {@code server}, {@code node}, {@code method},
 *       {@code status} (HTTP code or {@code IO_ERROR}), {@code outcome}</li>
 *   <li>{@code ranger.client.retries} (counter): attempts after the first, tags {@code server}, {@code method}</li>
 * </ul>
 * Paths are not tagged (they contain ids and names, which would explode cardinality).
 */
public class RangerMetricsListener implements RequestListener {
    public static final String REQUESTS = "ranger.client.requests";
    public static final String RETRIES = "ranger.client.retries";

    private final MeterRegistry registry;
    private final String server;

    public RangerMetricsListener(MeterRegistry registry, String server) {
        this.registry = registry;
        this.server = server;
    }

    @Override
    public void onRequest(String method, URI uri, int attempt, String requestId) {
        if (attempt > 1) {
            Counter.builder(RETRIES).description("Ranger SDK retried attempts")
                    .tag("server", server).tag("method", method).register(registry).increment();
        }
    }

    @Override
    public void onResponse(String method, URI uri, int status, Duration elapsed, int attempt, String requestId) {
        timer(method, uri, String.valueOf(status), outcome(status)).record(elapsed);
    }

    @Override
    public void onError(String method, URI uri, Throwable error, int attempt, String requestId) {
        timer(method, uri, "IO_ERROR", "ERROR").record(Duration.ZERO);
    }

    private Timer timer(String method, URI uri, String status, String outcome) {
        return Timer.builder(REQUESTS).description("Ranger SDK HTTP attempts")
                .tag("server", server).tag("node", node(uri)).tag("method", method)
                .tag("status", status).tag("outcome", outcome)
                .register(registry);
    }

    private static String node(URI uri) {
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
    }

    private static String outcome(int status) {
        if (status < 400) {
            return "SUCCESS";
        }
        return status < 500 ? "CLIENT_ERROR" : "SERVER_ERROR";
    }
}
