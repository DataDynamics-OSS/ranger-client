package io.datadynamics.ranger.demo.web.pdp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Parses the "key=value,key=value" and "a,b,c" fields of the demo forms, and renders them back as Java literals. */
public final class Kv {
    private Kv() {
    }

    /** {@code "database=sales, table=orders"} to an ordered map. */
    public static Map<String, String> map(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null) {
            return out;
        }
        for (String part : text.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            int eq = p.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("Expected key=value but got '" + p + "'");
            }
            out.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
        }
        return out;
    }

    /** {@code "a, b"} to {@code [a, b]}; blank gives an empty list. */
    public static List<String> list(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }

    /** Flattens a map to {@code key1, value1, key2, value2} for {@code ResourceInfo.of(...)}. */
    public static String[] flat(Map<String, String> map) {
        List<String> kv = new ArrayList<>();
        map.forEach((k, v) -> {
            kv.add(k);
            kv.add(v);
        });
        return kv.toArray(new String[0]);
    }

    /** {@code "a", "b"} as Java string literals. */
    public static String args(List<String> values) {
        return values.stream().map(Kv::str).collect(Collectors.joining(", "));
    }

    public static String args(Map<String, String> map) {
        return args(Arrays.asList(flat(map)));
    }

    /** A Java string literal. */
    public static String str(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** {@code List.of("a", "b")}. */
    public static String listOf(List<String> values) {
        return "List.of(" + args(values) + ")";
    }

    /** {@code Map.of("k", "v")}. */
    public static String mapOf(Map<String, String> map) {
        return "Map.of(" + args(map) + ")";
    }
}
