package io.datadynamics.ranger.client.otel;

import io.datadynamics.ranger.client.core.RequestInterceptor;
import io.datadynamics.ranger.client.core.ServerKind;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;

import java.net.URI;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * OpenTelemetry tracing for SDK clients: one CLIENT span per HTTP attempt (child of the caller's current
 * span), named after the HTTP method and carrying the stable HTTP semantic-convention attributes plus
 * {@code ranger.server} and {@code ranger.request_id}. The span's context is sent as W3C {@code traceparent}
 * (or whatever propagators the {@link OpenTelemetry} instance is configured with).
 *
 * <pre>
 * RangerAdminClient admin = RangerAdminClient.builder()
 *     .urls("https://ranger:6182")
 *     .interceptor(RangerTracing.create(openTelemetry))
 *     .build();
 * </pre>
 *
 * Do not combine with the OpenTelemetry Java agent's JDK HttpClient instrumentation, which would trace
 * the same requests a second time.
 */
public final class RangerTracing implements RequestInterceptor {
    public static final String INSTRUMENTATION_NAME = "io.datadynamics.ranger.client";
    public static final String INSTRUMENTATION_VERSION = "0.2.0";

    static final AttributeKey<String> METHOD = AttributeKey.stringKey("http.request.method");
    static final AttributeKey<String> URL = AttributeKey.stringKey("url.full");
    static final AttributeKey<String> ADDRESS = AttributeKey.stringKey("server.address");
    static final AttributeKey<Long> PORT = AttributeKey.longKey("server.port");
    static final AttributeKey<Long> RESEND = AttributeKey.longKey("http.request.resend_count");
    static final AttributeKey<Long> STATUS = AttributeKey.longKey("http.response.status_code");
    static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");
    static final AttributeKey<String> SERVER = AttributeKey.stringKey("ranger.server");
    static final AttributeKey<String> REQUEST_ID = AttributeKey.stringKey("ranger.request_id");

    private static final TextMapSetter<BiConsumer<String, String>> SETTER = (carrier, key, value) -> carrier.accept(key, value);

    private final Tracer tracer;
    private final TextMapPropagator propagator;

    private RangerTracing(Tracer tracer, TextMapPropagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    public static RangerTracing create(OpenTelemetry openTelemetry) {
        return new RangerTracing(openTelemetry.getTracer(INSTRUMENTATION_NAME, INSTRUMENTATION_VERSION),
                openTelemetry.getPropagators().getTextMapPropagator());
    }

    @Override
    public Attempt begin(ServerKind server, String method, URI uri, int attempt, String requestId,
                         BiConsumer<String, String> header) {
        Context parent = Context.current();
        var span = tracer.spanBuilder(method).setSpanKind(SpanKind.CLIENT).setParent(parent)
                .setAttribute(METHOD, method)
                .setAttribute(URL, uri.toString())
                .setAttribute(ADDRESS, uri.getHost())
                .setAttribute(PORT, (long) port(uri))
                .setAttribute(SERVER, server.name().toLowerCase(Locale.ROOT))
                .setAttribute(REQUEST_ID, requestId);
        if (attempt > 1) {
            span.setAttribute(RESEND, (long) attempt - 1);
        }
        Span started = span.startSpan();
        propagator.inject(parent.with(started), header, SETTER);
        return new Attempt() {
            @Override
            public void onResponse(int status) {
                started.setAttribute(STATUS, (long) status);
                if (status >= 400) {
                    started.setAttribute(ERROR_TYPE, String.valueOf(status));
                    started.setStatus(StatusCode.ERROR);
                }
                started.end();
            }

            @Override
            public void onError(Throwable error) {
                started.setAttribute(ERROR_TYPE, error.getClass().getName());
                started.recordException(error);
                started.setStatus(StatusCode.ERROR);
                started.end();
            }
        };
    }

    private static int port(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
