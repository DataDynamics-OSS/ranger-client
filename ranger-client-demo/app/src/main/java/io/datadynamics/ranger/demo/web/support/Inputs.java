package io.datadynamics.ranger.demo.web.support;

import jakarta.servlet.http.HttpServletResponse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Parses form inputs into SDK arguments and renders those arguments back as Java source, so the code shown
 * in a call panel is exactly what was executed.
 */
public final class Inputs {
    private Inputs() {
    }

    /** Blank to null. */
    public static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** "a, b,c" to [a, b, c]. */
    public static List<String> csv(String s) {
        if (blank(s) == null) {
            return List.of();
        }
        return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).collect(Collectors.toList());
    }

    /** "database=sales; table=a,b" to {database=[sales], table=[a, b]}. */
    public static Map<String, List<String>> resources(String s) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (blank(s) == null) {
            return out;
        }
        for (String part : s.split(";")) {
            if (part.isBlank()) {
                continue;
            }
            int eq = part.indexOf('=');
            if (eq < 1) {
                throw new IllegalArgumentException("Expected key=value[,value] but got '" + part.trim() + "'");
            }
            out.put(part.substring(0, eq).trim(), csv(part.substring(eq + 1)));
        }
        return out;
    }

    /** "k=v" lines (or ';'-separated) to an ordered map. */
    public static Map<String, String> pairs(String s) {
        Map<String, String> out = new LinkedHashMap<>();
        if (blank(s) == null) {
            return out;
        }
        for (String part : s.split("[\\n;]")) {
            if (part.isBlank()) {
                continue;
            }
            int eq = part.indexOf('=');
            if (eq < 1) {
                throw new IllegalArgumentException("Expected key=value but got '" + part.trim() + "'");
            }
            out.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        return out;
    }

    /** Java string literal, or {@code null}. */
    public static String q(String s) {
        if (s == null) {
            return "null";
        }
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }

    /** {@code "a", "b"} for varargs. */
    public static String args(List<String> values) {
        return values.stream().map(Inputs::q).collect(Collectors.joining(", "));
    }

    /** {@code List.of("a", "b")}. */
    public static String listOf(List<String> values) {
        return "List.of(" + args(values) + ")";
    }

    /** {@code Set.of("a", "b")}. */
    public static String setOf(List<String> values) {
        return "Set.of(" + args(values) + ")";
    }

    /** {@code Map.of("k", "v", ...)} (Map.of literal; fine for display even beyond 10 pairs). */
    public static String mapOf(Map<String, String> m) {
        List<String> parts = new ArrayList<>();
        m.forEach((k, v) -> {
            parts.add(q(k));
            parts.add(q(v));
        });
        return "Map.of(" + String.join(", ", parts) + ")";
    }

    /** Tells htmx to fire a "refresh" event so list sections reload after a change. */
    public static void refresh(HttpServletResponse response) {
        response.setHeader("HX-Trigger", "refresh");
    }
}
