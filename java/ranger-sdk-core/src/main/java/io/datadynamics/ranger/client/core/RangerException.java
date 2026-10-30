package io.datadynamics.ranger.client.core;

/**
 * Base of every SDK error. Carries the request method, path, server, request id and a masked excerpt of
 * the response body so that a failure can be diagnosed from one log line.
 */
public class RangerException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private static final int BODY_EXCERPT = 2000;

    private final Integer status;
    private final String method;
    private final String path;
    private final String server;
    private final String requestId;
    private final String body;
    private final String serverMessage;

    public RangerException(String message) {
        this(message, null, null);
    }

    public RangerException(String message, ErrorContext ctx, Throwable cause) {
        super(render(message, ctx), cause);
        this.status = ctx == null ? null : ctx.status;
        this.method = ctx == null ? null : ctx.method;
        this.path = ctx == null ? null : ctx.path;
        this.server = ctx == null ? null : ctx.server;
        this.requestId = ctx == null ? null : ctx.requestId;
        this.body = ctx == null || ctx.body == null ? null
                : ctx.body.length() > BODY_EXCERPT ? ctx.body.substring(0, BODY_EXCERPT) : ctx.body;
        this.serverMessage = ctx == null ? null : ctx.serverMessage;
    }

    private static String render(String message, ErrorContext ctx) {
        if (ctx == null) {
            return message;
        }
        StringBuilder sb = new StringBuilder(message);
        if (ctx.method != null && ctx.path != null) {
            sb.append(" [").append(ctx.method).append(' ').append(ctx.path).append(']');
        }
        if (ctx.status != null) {
            sb.append(" status=").append(ctx.status);
        }
        if (ctx.server != null) {
            sb.append(" server=").append(ctx.server);
        }
        if (ctx.requestId != null) {
            sb.append(" requestId=").append(ctx.requestId);
        }
        return sb.toString();
    }

    /** HTTP status, or null when no response was received. */
    public Integer getStatus() {
        return status;
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public String getServer() {
        return server;
    }

    public String getRequestId() {
        return requestId;
    }

    /** Response body excerpt (secrets masked). */
    public String getBody() {
        return body;
    }

    /** The server's own error message (msgDesc / message), if any. */
    public String getServerMessage() {
        return serverMessage;
    }

    /** Request/response details attached to an exception. */
    public static final class ErrorContext {
        final Integer status;
        final String method;
        final String path;
        final String server;
        final String requestId;
        final String body;
        final String serverMessage;

        public ErrorContext(Integer status, String method, String path, String server, String requestId,
                            String body, String serverMessage) {
            this.status = status;
            this.method = method;
            this.path = path;
            this.server = server;
            this.requestId = requestId;
            this.body = body;
            this.serverMessage = serverMessage;
        }
    }
}
