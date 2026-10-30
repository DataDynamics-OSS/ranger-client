package io.datadynamics.ranger.client.core;

import io.datadynamics.ranger.client.core.http.HttpCall;
import io.datadynamics.ranger.client.core.http.Transport;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code admin.capabilities().supports(Feature.GDS)}. With a declared server version the answer comes
 * from {@link Feature}'s matrix; otherwise the feature's probe endpoint is requested once and cached
 * (404 = absent, any other answer = present).
 */
public final class Capabilities {
    private final Transport transport;
    private final ServerKind server;
    private final String serverVersion;
    private final Map<Feature, Boolean> cache = new ConcurrentHashMap<>();

    public Capabilities(Transport transport) {
        this.transport = transport;
        this.server = transport.config().server();
        this.serverVersion = transport.config().serverVersion();
    }

    public boolean supports(Feature feature) {
        return cache.computeIfAbsent(feature, this::detect);
    }

    public void require(Feature feature) {
        if (!supports(feature)) {
            throw new UnsupportedFeatureException(feature, "needs Ranger " + feature.server() + " >= " + feature.minVersion()
                    + (serverVersion != null ? ", server is " + serverVersion : ""));
        }
    }

    public String serverVersion() {
        return serverVersion;
    }

    private boolean detect(Feature f) {
        if (f.server() != server) {
            return false;
        }
        if (serverVersion != null) {
            return Versions.compare(serverVersion, f.minVersion()) >= 0;
        }
        if (f.probe() == null) {
            return true;
        }
        try {
            transport.send(HttpCall.get(f.probe()).retry(RetryPolicy.none()));
            return true;
        } catch (RangerNotFoundException e) {
            return false;
        } catch (RangerTransportException e) {
            throw e;
        } catch (RangerException e) {
            return true; // 401/403/400: the endpoint exists
        }
    }
}
