package io.datadynamics.ranger.client.model;

import java.util.Map;

/**
 * Ranger Resource Names (RRN) as used by the PDP: {@code <leaf-type>:<v1>/<v2>/...}.
 *
 * <p>{@code of("database", "sales", "table", "orders")} gives {@code table:sales/orders}.
 */
public final class ResourceNames {
    private ResourceNames() {
    }

    /** @param keyValues resource type / value pairs, top of the hierarchy first */
    public static String of(String... keyValues) {
        if (keyValues.length == 0 || keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("expected resource type/value pairs, got " + keyValues.length + " strings");
        }
        StringBuilder values = new StringBuilder();
        for (int i = 1; i < keyValues.length; i += 2) {
            if (values.length() > 0) {
                values.append('/');
            }
            values.append(escape(keyValues[i]));
        }
        return keyValues[keyValues.length - 2] + ":" + values;
    }

    /** @param resource ordered map (e.g. LinkedHashMap) from resource type to value, top first */
    public static String of(Map<String, String> resource) {
        String[] kv = new String[resource.size() * 2];
        int i = 0;
        for (Map.Entry<String, String> e : resource.entrySet()) {
            kv[i++] = e.getKey();
            kv[i++] = e.getValue();
        }
        return of(kv);
    }

    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("/", "\\/");
    }
}
