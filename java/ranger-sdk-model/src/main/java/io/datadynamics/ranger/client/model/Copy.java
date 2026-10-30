package io.datadynamics.ranger.client.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Defensive-copy helpers used by the generated models. */
final class Copy {
    private Copy() {
    }

    static <T> List<T> list(List<T> l) {
        return l == null ? null : Collections.unmodifiableList(new ArrayList<>(l));
    }

    static <K, V> Map<K, V> map(Map<K, V> m) {
        return m == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(m));
    }

    static <T> List<T> view(List<T> l) {
        return l == null ? Collections.emptyList() : Collections.unmodifiableList(l);
    }

    static <K, V> Map<K, V> view(Map<K, V> m) {
        return m == null ? Collections.emptyMap() : Collections.unmodifiableMap(m);
    }

    static <T> List<T> mutableList(List<T> l) {
        return l == null ? null : new ArrayList<>(l);
    }

    static <K, V> Map<K, V> mutableMap(Map<K, V> m) {
        return m == null ? null : new LinkedHashMap<>(m);
    }
}
