package io.datadynamics.ranger.client.core;

/** 409, a duplicate-name 400, or a client-side version mismatch. */
public class RangerConflictException extends RangerException {
    private static final long serialVersionUID = 1L;

    public RangerConflictException(String message, ErrorContext ctx, Throwable cause) {
        super(message, ctx, cause);
    }

    public RangerConflictException(String message, ErrorContext ctx) {
        super(message, ctx, null);
    }
}
