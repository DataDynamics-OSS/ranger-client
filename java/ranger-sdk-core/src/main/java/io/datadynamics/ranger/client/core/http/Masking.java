package io.datadynamics.ranger.client.core.http;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Removes credentials from anything that may end up in a log or an exception message. */
public final class Masking {
    private static final Set<String> HEADERS = Set.of("authorization", "cookie", "set-cookie", "proxy-authorization");
    private static final Pattern SECRET_FIELDS =
            Pattern.compile("(\"(?:password|passwd|secret|token|keytab|material)\"\\s*:\\s*)\"[^\"]*\"", Pattern.CASE_INSENSITIVE);

    private Masking() {
    }

    public static Map<String, String> headers(Map<String, String> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        headers.forEach((k, v) -> out.put(k, HEADERS.contains(k.toLowerCase()) ? "***" : v));
        return out;
    }

    public static String body(String body) {
        return body == null ? null : SECRET_FIELDS.matcher(body).replaceAll("$1\"***\"");
    }
}
