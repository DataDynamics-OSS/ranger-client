package io.datadynamics.ranger.client.core.auth;

/** No credentials (e.g. a PDP behind an authenticating proxy). */
public final class NoAuth implements AuthProvider {
    public static final NoAuth INSTANCE = new NoAuth();

    private NoAuth() {
    }
}
