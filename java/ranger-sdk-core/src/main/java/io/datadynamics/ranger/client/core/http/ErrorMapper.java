package io.datadynamics.ranger.client.core.http;

import io.datadynamics.ranger.client.core.RangerAccessDeniedException;
import io.datadynamics.ranger.client.core.RangerAuthenticationException;
import io.datadynamics.ranger.client.core.RangerConflictException;
import io.datadynamics.ranger.client.core.RangerException;
import io.datadynamics.ranger.client.core.RangerException.ErrorContext;
import io.datadynamics.ranger.client.core.RangerNotFoundException;
import io.datadynamics.ranger.client.core.RangerServerException;
import io.datadynamics.ranger.client.core.RangerValidationException;
import io.datadynamics.ranger.client.model.RangerJson;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Maps an HTTP error response to the SDK exception hierarchy (same rules as the Python SDK). */
public final class ErrorMapper {
    private static final Pattern DUPLICATE = Pattern.compile("already exists|duplicate|same name", Pattern.CASE_INSENSITIVE);

    private ErrorMapper() {
    }

    @SuppressWarnings("unchecked")
    public static RangerException map(int status, byte[] rawBody, String method, String path, String server, String requestId) {
        String text = Masking.body(rawBody == null ? "" : new String(rawBody, StandardCharsets.UTF_8));
        String msg = null;
        List<Map<String, Object>> fields = null;
        try {
            Object parsed = text.isEmpty() ? null : RangerJson.read(text, Object.class);
            if (parsed instanceof Map) {
                Map<String, Object> body = (Map<String, Object>) parsed;
                if (body.get("RemoteException") instanceof Map) { // Hadoop KMS
                    body = (Map<String, Object>) body.get("RemoteException");
                }
                for (String k : new String[] {"msgDesc", "message", "error"}) {
                    if (body.get(k) != null) {
                        msg = String.valueOf(body.get(k));
                        break;
                    }
                }
                if (body.get("messageList") instanceof List) {
                    fields = (List<Map<String, Object>>) body.get("messageList");
                }
            }
        } catch (RuntimeException notJson) {
            // keep the raw text
        }
        ErrorContext ctx = new ErrorContext(status, method, path, server, requestId, text, msg);
        String summary = msg != null ? msg : text.isEmpty() ? "HTTP " + status : text.substring(0, Math.min(200, text.length()));
        if (status == 401) {
            return new RangerAuthenticationException("Authentication failed: " + summary, ctx);
        }
        if (status == 403) {
            return new RangerAccessDeniedException("Access denied: " + summary, ctx);
        }
        if (status == 404) {
            return new RangerNotFoundException("Not found: " + summary, ctx);
        }
        if (status == 409 || (status == 400 && msg != null && DUPLICATE.matcher(msg).find())) {
            return new RangerConflictException("Conflict: " + summary, ctx);
        }
        if (status == 400) {
            return new RangerValidationException("Validation failed: " + summary, ctx, fields);
        }
        if (status >= 500) {
            return new RangerServerException("Server error: " + summary, ctx);
        }
        return new RangerException("Unexpected status " + status + ": " + summary, ctx, null);
    }
}
