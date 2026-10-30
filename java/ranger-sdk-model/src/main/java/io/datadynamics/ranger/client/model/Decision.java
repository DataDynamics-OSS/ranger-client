package io.datadynamics.ranger.client.model;

/** PDP access decision. Values unknown to this SDK map to {@link #UNKNOWN}; the raw value stays available. */
public enum Decision {
    ALLOW, DENY, NOT_DETERMINED, PARTIAL, UNKNOWN;

    public static Decision of(String value) {
        if (value != null) {
            for (Decision d : values()) {
                if (d.name().equalsIgnoreCase(value)) {
                    return d;
                }
            }
        }
        return UNKNOWN;
    }
}
