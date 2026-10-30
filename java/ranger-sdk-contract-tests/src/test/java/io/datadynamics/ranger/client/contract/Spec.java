package io.datadynamics.ranger.client.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Loads spec/contract files and compares JSON-like values the way the Python runner does. */
final class Spec {
    static final ObjectMapper JSON = new ObjectMapper();

    private Spec() {
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> cases(String file, String key) {
        File f = new File(new File(System.getProperty("ranger.sdk.spec.dir", "../../spec"), "contract"), file);
        try {
            return (List<Map<String, Object>>) new ObjectMapper(new YAMLFactory()).readValue(f, Map.class).get(key);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every key in {@code expected} must be present in {@code actual} with a matching value; lists match element-wise. */
    static void subset(Object expected, Object actual, String path) {
        if (expected instanceof Map) {
            assertTrue(actual instanceof Map, path + ": expected object, got " + actual);
            Map<?, ?> a = (Map<?, ?>) actual;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) expected).entrySet()) {
                assertTrue(a.containsKey(e.getKey()), path + "." + e.getKey() + " missing in " + a);
                subset(e.getValue(), a.get(e.getKey()), path + "." + e.getKey());
            }
        } else if (expected instanceof List) {
            assertTrue(actual instanceof List, path + ": expected list, got " + actual);
            List<?> el = (List<?>) expected;
            List<?> al = (List<?>) actual;
            assertEquals(el.size(), al.size(), path + ": " + al);
            for (int i = 0; i < el.size(); i++) {
                subset(el.get(i), al.get(i), path + "[" + i + "]");
            }
        } else if (expected instanceof Number && actual instanceof Number) {
            assertEquals(((Number) expected).longValue(), ((Number) actual).longValue(), path);
        } else {
            assertEquals(expected, actual, path);
        }
    }
}
