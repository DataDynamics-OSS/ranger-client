package io.datadynamics.ranger.client.model;

/** How a PDP resource name is matched ({@code RangerResourceInfo.ResourceMatchScope}). */
public enum MatchScope {
    SELF, SELF_OR_ANY_CHILD, SELF_OR_ANY_DESCENDANT, UNKNOWN;

    public static MatchScope of(String value) {
        if (value != null) {
            for (MatchScope s : values()) {
                if (s.name().equalsIgnoreCase(value)) {
                    return s;
                }
            }
        }
        return UNKNOWN;
    }
}
