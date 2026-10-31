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
    /** Hadoop KMS reports an unknown key as a 500 RemoteException with one of these messages. */
    private static final Pattern KMS_NO_KEY = Pattern.compile("does not exist|can't find metadata|key not found", Pattern.CASE_INSENSITIVE);
    private static final Pattern DUPLICATE = Pattern.compile("already exists|duplicate|same name", Pattern.CASE_INSENSITIVE);

    private ErrorMapper() {
    }

    @SuppressWarnings("unchecked")
    public static RangerException map(int status, byte[] rawBody, String method, String path, String server, String requestId) {
        String text = Masking.body(rawBody == null ? "" : new String(rawBody, StandardCharsets.UTF_8));
        String msg = null;
        boolean remoteException = false;
        List<Map<String, Object>> fields = null;
        try {
            Object parsed = text.isEmpty() ? null : RangerJson.read(text, Object.class);
            if (parsed instanceof Map) {
                Map<String, Object> body = (Map<String, Object>) parsed;
                if (body.get("RemoteException") instanceof Map) { // Hadoop KMS
                    body = (Map<String, Object>) body.get("RemoteException");
                    remoteException = true;
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
        if (status == 404 || (status == 400 && hasMessageName(fields, "DATA_NOT_FOUND"))
                || (status == 500 && remoteException && msg != null && KMS_NO_KEY.matcher(msg).find())) {
            // Ranger's xusers API answers an unknown user/group name with 400 + messageList[DATA_NOT_FOUND];
            // KMS answers an unknown key with 500 RemoteException "Key x does not exist".
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

    private static boolean hasMessageName(List<Map<String, Object>> fields, String name) {
        if (fields == null) {
            return false;
        }
        for (Object f : fields) {
            if (f instanceof Map && name.equals(((Map<?, ?>) f).get("name"))) {
                return true;
            }
        }
        return false;
    }
}
