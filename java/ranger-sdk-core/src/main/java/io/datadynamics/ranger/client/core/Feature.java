package io.datadynamics.ranger.client.core;

/**
 * Feature matrix. Must match {@code spec/features.yaml} (verified by FeatureMatrixTest).
 */
public enum Feature {
    SERVICE_DEFS(ServerKind.ADMIN, "2.5.0", "/service/public/v2/api/servicedef?pageSize=1"),
    SERVICES(ServerKind.ADMIN, "2.5.0", "/service/public/v2/api/service?pageSize=1"),
    POLICIES(ServerKind.ADMIN, "2.5.0", "/service/public/v2/api/policy?pageSize=1"),
    ROLES(ServerKind.ADMIN, "2.5.0", "/service/public/v2/api/roles/names"),
    USERS(ServerKind.ADMIN, "2.5.0", "/service/xusers/users?pageSize=1"),
    TAGS(ServerKind.ADMIN, "2.5.0", "/service/tags/types/"),
    ZONES(ServerKind.ADMIN, "2.5.0", "/service/public/v2/api/zone-headers"),
    ZONES_V2(ServerKind.ADMIN, "2.5.0", "/service/public/v2/api/zones-v2?pageSize=1"),
    /** Governed Data Sharing: not in the 2.x release line. */
    GDS(ServerKind.ADMIN, "3.0.0", "/service/gds/dataset?pageSize=1"),
    KMS(ServerKind.KMS, "2.5.0", "/kms/api/status"),
    PDP(ServerKind.PDP, "2.9.0", null);

    private final ServerKind server;
    private final String minVersion;
    private final String probe;

    Feature(ServerKind server, String minVersion, String probe) {
        this.server = server;
        this.minVersion = minVersion;
        this.probe = probe;
    }

    public ServerKind server() {
        return server;
    }

    public String minVersion() {
        return minVersion;
    }

    /** Path probed with GET when the server version is unknown; null when no probe is needed. */
    public String probe() {
        return probe;
    }
}
