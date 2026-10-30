package io.datadynamics.ranger.client.core;

/** The connected server does not provide the requested API (see {@link Feature}). */
public class UnsupportedFeatureException extends RangerException {
    private static final long serialVersionUID = 1L;
    private final Feature feature;

    public UnsupportedFeatureException(Feature feature, String detail) {
        super("Feature " + feature + " is not supported by the server" + (detail == null ? "" : ": " + detail));
        this.feature = feature;
    }

    public Feature getFeature() {
        return feature;
    }
}
