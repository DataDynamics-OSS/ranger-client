package io.datadynamics.ranger.client.core.auth;

import io.datadynamics.ranger.client.core.ServerKind;

import java.net.URI;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Caller identity in a header set by a trusted proxy (PDP only; use only inside the trusted zone). */
public final class TrustedHeaderAuth implements AuthProvider {
    private final String header;
    private final String user;

    private TrustedHeaderAuth(String header, String user) {
        this.header = header;
        this.user = user;
    }

    public static TrustedHeaderAuth of(String user) {
        return new TrustedHeaderAuth("X-Forwarded-User", user);
    }

    public static TrustedHeaderAuth of(String header, String user) {
        return new TrustedHeaderAuth(header, user);
    }

    @Override
    public Map<String, String> headers(URI target) {
        return Collections.singletonMap(header, user);
    }

    @Override
    public Set<ServerKind> servers() {
        return EnumSet.of(ServerKind.PDP);
    }

    @Override
    public String describe() {
        return "TrustedHeaderAuth(" + header + "=" + user + ")";
    }
}
