package io.datadynamics.ranger.client.core.http;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Round-robin over server URLs; a failed node is skipped for a cooldown period. */
public final class Endpoints {
    private final List<URI> urls;
    private final long cooldownNanos;
    private final LongSupplier clock;
    private final Map<URI, Long> downUntil = new HashMap<>();
    private int next;

    public Endpoints(List<URI> urls, java.time.Duration cooldown) {
        this(urls, cooldown, System::nanoTime);
    }

    Endpoints(List<URI> urls, java.time.Duration cooldown, LongSupplier clock) {
        if (urls.isEmpty()) {
            throw new IllegalArgumentException("at least one server URL is required");
        }
        this.urls = List.copyOf(urls);
        this.cooldownNanos = cooldown.toNanos();
        this.clock = clock;
    }

    public synchronized URI pick(Collection<URI> exclude) {
        long now = clock.getAsLong();
        int n = urls.size();
        List<URI> candidates = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            URI u = urls.get((next + i) % n);
            if (!exclude.contains(u)) {
                candidates.add(u);
            }
        }
        if (candidates.isEmpty()) {
            candidates.addAll(urls);
        }
        URI choice = null;
        for (URI u : candidates) {
            Long until = downUntil.get(u);
            if (until == null || until - now <= 0) {
                choice = u;
                break;
            }
        }
        if (choice == null) { // all cooling down: take the one that recovers first
            choice = candidates.get(0);
            for (URI u : candidates) {
                if (downUntil.get(u) - downUntil.get(choice) < 0) {
                    choice = u;
                }
            }
        }
        next = (urls.indexOf(choice) + 1) % n;
        return choice;
    }

    public synchronized void markDown(URI url) {
        downUntil.put(url, clock.getAsLong() + cooldownNanos);
    }

    public synchronized void markUp(URI url) {
        downUntil.remove(url);
    }

    /** Each URL with {@code true} when it is usable, {@code false} while it cools down after a failure. */
    public synchronized Map<URI, Boolean> availability() {
        long now = clock.getAsLong();
        Map<URI, Boolean> out = new LinkedHashMap<>();
        for (URI u : urls) {
            Long until = downUntil.get(u);
            out.put(u, until == null || until - now <= 0);
        }
        return out;
    }

    public int size() {
        return urls.size();
    }
}
