package io.datadynamics.ranger.client.core;

/** 404: the target object does not exist. */
public class RangerNotFoundException extends RangerException {
    private static final long serialVersionUID = 1L;

    public RangerNotFoundException(String message, ErrorContext ctx, Throwable cause) {
        super(message, ctx, cause);
    }

    public RangerNotFoundException(String message, ErrorContext ctx) {
        super(message, ctx, null);
    }
}
