package io.datadynamics.ranger.client.core;

import io.datadynamics.ranger.client.core.http.Transport;

/** Builds bare transports for core tests. */
final class TestClient extends AbstractClientBuilder<Transport, TestClient> {
    TestClient(ServerKind kind) {
        super(kind);
    }

    static TestClient admin(String... urls) {
        return new TestClient(ServerKind.ADMIN).urls(urls).sleeper(d -> { }).retry(RetryPolicy.exponential(3, java.time.Duration.ZERO));
    }

    @Override
    public Transport build() {
        return transport();
    }
}
