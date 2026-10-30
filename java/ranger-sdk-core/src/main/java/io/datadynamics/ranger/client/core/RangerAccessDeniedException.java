package io.datadynamics.ranger.client.core;

/** 403: the caller lacks permission (or PDP delegation is not allowed). */
public class RangerAccessDeniedException extends RangerException {
    private static final long serialVersionUID = 1L;

    public RangerAccessDeniedException(String message, ErrorContext ctx, Throwable cause) {
        super(message, ctx, cause);
    }

    public RangerAccessDeniedException(String message, ErrorContext ctx) {
        super(message, ctx, null);
    }
}
