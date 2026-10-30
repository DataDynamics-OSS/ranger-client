package io.datadynamics.ranger.client.pdp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small LRU + TTL cache keyed by the request body without requestId / accessTime. */
final class DecisionCache {
    private final long ttlNanos;
    private final int maxEntries;
    private final LinkedHashMap<String, Object[]> map;

    DecisionCache(Duration ttl, int maxEntries) {
        this.ttlNanos = ttl.toNanos();
        this.maxEntries = maxEntries;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Object[]> eldest) {
                return size() > DecisionCache.this.maxEntries;
            }
        };
    }

    static String key(String path, JsonNode body) {
        if (body instanceof ObjectNode) {
            ObjectNode copy = ((ObjectNode) body).deepCopy();
            copy.remove("requestId");
            if (copy.get("context") instanceof ObjectNode) {
                ((ObjectNode) copy.get("context")).remove("accessTime");
            }
            return path + copy;
        }
        return path + body;
    }

    synchronized Object get(String key) {
        Object[] e = map.get(key);
        if (e == null) {
            return null;
        }
        if (System.nanoTime() - (long) e[0] > 0) {
            map.remove(key);
            return null;
        }
        return e[1];
    }

    synchronized void put(String key, Object value) {
        map.put(key, new Object[] {System.nanoTime() + ttlNanos, value});
    }

    synchronized void clear() {
        map.clear();
    }
}
