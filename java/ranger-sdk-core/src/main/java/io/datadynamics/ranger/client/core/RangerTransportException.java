package io.datadynamics.ranger.client.core;

/** No server could be reached (connection refused, timeout, TLS failure). */
public class RangerTransportException extends RangerException {
    private static final long serialVersionUID = 1L;

    public RangerTransportException(String message, ErrorContext ctx, Throwable cause) {
        super(message, ctx, cause);
    }

    public RangerTransportException(String message, ErrorContext ctx) {
        super(message, ctx, null);
    }
}
