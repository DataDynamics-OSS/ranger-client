package io.datadynamics.ranger.client.core;

/** 5xx after retries were exhausted. */
public class RangerServerException extends RangerException {
    private static final long serialVersionUID = 1L;

    public RangerServerException(String message, ErrorContext ctx, Throwable cause) {
        super(message, ctx, cause);
    }

    public RangerServerException(String message, ErrorContext ctx) {
        super(message, ctx, null);
    }
}
