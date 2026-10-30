package io.datadynamics.ranger.client.core;

/** 401: authentication failed, even after one credential refresh. */
public class RangerAuthenticationException extends RangerException {
    private static final long serialVersionUID = 1L;

    public RangerAuthenticationException(String message, ErrorContext ctx, Throwable cause) {
        super(message, ctx, cause);
    }

    public RangerAuthenticationException(String message, ErrorContext ctx) {
        super(message, ctx, null);
    }
}
