package io.datadynamics.ranger.client.core;

import io.datadynamics.ranger.client.core.http.Transport;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** Shared client plumbing: transport ownership, {@code async()} view, close. */
public abstract class AbstractRangerClient<C extends AbstractRangerClient<C>> implements AutoCloseable {
    private static final ExecutorService SHARED = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ranger-sdk-async");
        t.setDaemon(true);
        return t;
    });

    protected final Transport transport;

    protected AbstractRangerClient(Transport transport) {
        this.transport = transport;
    }

    public ClientConfig config() {
        return transport.config();
    }

    /** Server URLs and whether each is in rotation ({@code false} = cooling down after a failure). */
    public Map<URI, Boolean> nodeAvailability() {
        return transport.nodeAvailability();
    }

    /**
     * Run any call of this client asynchronously:
     * {@code admin.async(a -> a.policies().get(7)).thenAccept(...)}.
     */
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> async(Function<C, T> call) {
        Executor ex = config().executor() != null ? config().executor() : SHARED;
        return CompletableFuture.supplyAsync(() -> call.apply((C) this), ex);
    }

    @Override
    public void close() {
        transport.close();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{urls=" + config().urls() + ", auth=" + config().auth().describe() + "}";
    }
}
