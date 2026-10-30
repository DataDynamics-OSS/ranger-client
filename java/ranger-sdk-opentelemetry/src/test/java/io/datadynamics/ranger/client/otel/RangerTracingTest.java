package io.datadynamics.ranger.client.otel;

import io.datadynamics.ranger.client.core.AbstractClientBuilder;
import io.datadynamics.ranger.client.core.RetryPolicy;
import io.datadynamics.ranger.client.core.ServerKind;
import io.datadynamics.ranger.client.core.http.HttpCall;
import io.datadynamics.ranger.client.core.http.Transport;
import io.datadynamics.ranger.client.testing.MockServer;
import io.datadynamics.ranger.client.testing.MockServer.Reply;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RangerTracingTest {
    @RegisterExtension
    static final OpenTelemetryExtension OTEL = OpenTelemetryExtension.create();

    static final class Client extends AbstractClientBuilder<Transport, Client> {
        Client() {
            super(ServerKind.ADMIN);
        }

        @Override
        public Transport build() {
            return transport();
        }
    }

    @Test
    void attemptSpansAreChildrenOfTheCurrentSpanAndArePropagated() {
        try (MockServer s = new MockServer()) {
            s.enqueue(Reply.of(503)).enqueue(Reply.of(200, "{}"));
            Transport t = new Client().urls(s.url()).sleeper(d -> { }).retry(RetryPolicy.exponential(3, Duration.ZERO))
                    .interceptor(RangerTracing.create(OTEL.getOpenTelemetry())).build();
            Span parent = OTEL.getOpenTelemetry().getTracer("app").spanBuilder("sync-policies").startSpan();
            try (Scope ignored = parent.makeCurrent()) {
                t.send(HttpCall.get("/service/public/v2/api/policy/5"));
            } finally {
                parent.end();
            }

            List<SpanData> spans = OTEL.getSpans();
            List<SpanData> client = spans.stream().filter(sp -> sp.getKind() == SpanKind.CLIENT).collect(Collectors.toList());
            assertEquals(2, client.size());
            for (int i = 0; i < 2; i++) {
                SpanData sp = client.get(i);
                assertEquals(parent.getSpanContext().getTraceId(), sp.getTraceId());
                assertEquals(parent.getSpanContext().getSpanId(), sp.getParentSpanId());
                String tp = s.requests().get(i).header("traceparent");
                assertTrue(tp.startsWith("00-" + sp.getTraceId() + "-" + sp.getSpanId() + "-"), tp); // flags: sampled (+ random)
                assertEquals(s.requests().get(i).header("X-Request-Id"), sp.getAttributes().get(RangerTracing.REQUEST_ID));
                assertEquals(RangerTracing.INSTRUMENTATION_NAME, sp.getInstrumentationScopeInfo().getName());
            }
        }
    }

    @Test
    void withoutACurrentSpanEachAttemptStartsATrace() {
        try (MockServer s = new MockServer()) {
            s.enqueue(Reply.of(200, "{}"));
            Transport t = new Client().urls(s.url()).interceptor(RangerTracing.create(OTEL.getOpenTelemetry())).build();
            t.send(HttpCall.get("/x"));
            SpanData sp = OTEL.getSpans().get(0);
            assertFalse(sp.getParentSpanContext().isValid());
            assertTrue(s.last().header("traceparent").contains(sp.getTraceId()));
        }
    }
}
