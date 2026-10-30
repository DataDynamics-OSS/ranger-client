package io.datadynamics.ranger.client.contract;

import io.datadynamics.ranger.client.core.RangerException;
import io.datadynamics.ranger.client.core.RetryPolicy;
import io.datadynamics.ranger.client.otel.RangerTracing;
import io.datadynamics.ranger.client.testing.MockServer;
import io.datadynamics.ranger.client.testing.MockServer.Reply;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs spec/contract/tracing.yaml (shared with python/tests/test_tracing.py) against ranger-sdk-opentelemetry. */
class TracingContractTest {
    @RegisterExtension
    static final OpenTelemetryExtension OTEL = OpenTelemetryExtension.create();

    @TestFactory
    Stream<DynamicTest> scenarios() {
        return Spec.cases("tracing.yaml", "scenarios").stream()
                .map(sc -> DynamicTest.dynamicTest((String) sc.get("name"), () -> run(sc)));
    }

    @SuppressWarnings("unchecked")
    private static void run(Map<String, Object> sc) {
        OTEL.clearSpans();
        int attempts = sc.containsKey("max_attempts") ? ((Number) sc.get("max_attempts")).intValue() : 3;
        try (MockServer server = new MockServer()) {
            String url = server.url();
            for (Map<String, Object> r : (List<Map<String, Object>>) sc.get("responses")) {
                if ("connect".equals(r.get("error"))) {
                    url = "http://127.0.0.1:9"; // nothing listens on the discard port
                    continue;
                }
                int status = ((Number) r.get("status")).intValue();
                server.enqueue(r.containsKey("body") ? Reply.of(status, Spec.json(r.get("body"))) : Reply.of(status));
            }
            try {
                HttpContractTest.invoke((String) sc.get("op"), (Map<String, Object>) sc.get("args"), url,
                        b -> b.retry(RetryPolicy.exponential(attempts, Duration.ZERO)).sleeper(d -> { })
                                .interceptor(RangerTracing.create(OTEL.getOpenTelemetry())));
            } catch (RangerException expected) {
                // error scenarios: only the spans matter here
            }

            List<SpanData> spans = OTEL.getSpans();
            List<Map<String, Object>> expected = (List<Map<String, Object>>) sc.get("expect_spans");
            assertEquals(expected.size(), spans.size(), "span count");
            for (int i = 0; i < expected.size(); i++) {
                check(expected.get(i), spans.get(i), "span[" + i + "]");
                if (i < server.requests().size()) {
                    MockServer.Recorded req = server.requests().get(i);
                    assertTrue(req.header("traceparent").contains(spans.get(i).getSpanId()), "traceparent of attempt " + i);
                    assertEquals(req.header("X-Request-Id"), spans.get(i).getAttributes().get(AttributeKey.stringKey("ranger.request_id")));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void check(Map<String, Object> exp, SpanData span, String where) {
        assertEquals(SpanKind.CLIENT, span.getKind(), where);
        assertEquals(exp.get("name"), span.getName(), where + ".name");
        StatusCode status = span.getStatus().getStatusCode();
        assertEquals(exp.get("status"), status == StatusCode.ERROR ? "ERROR" : "UNSET", where + ".status");
        Map<String, Object> attrs = new LinkedHashMap<>();
        span.getAttributes().forEach((k, v) -> attrs.put(k.getKey(), v));
        Map<String, Object> want = (Map<String, Object>) exp.getOrDefault("attributes", Map.of());
        want.forEach((k, v) -> {
            assertTrue(attrs.containsKey(k), where + " missing " + k + " in " + attrs);
            if (!"*".equals(v)) {
                assertEquals(String.valueOf(v), String.valueOf(attrs.get(k)), where + "." + k);
            }
        });
        for (String k : (List<String>) exp.getOrDefault("absent", List.of())) {
            assertFalse(attrs.containsKey(k), where + " must not have " + k + ": " + attrs);
        }
        if (exp.containsKey("url_path")) {
            assertEquals(exp.get("url_path"), URI.create((String) attrs.get("url.full")).getRawPath(), where + ".url_path");
        }
    }
}
