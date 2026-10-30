package io.datadynamics.ranger.client.spring;

import io.datadynamics.ranger.client.otel.RangerTracing;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Adds {@link RangerTracing} to each auto-configured client when an {@link OpenTelemetry} bean exists: one
 * CLIENT span per HTTP attempt, child of the current span (Micrometer Tracing's OpenTelemetry bridge keeps
 * it in the OpenTelemetry context), with the trace context sent to Ranger. Disable with
 * {@code ranger.tracing.enabled=false}, and when the OpenTelemetry Java agent already instruments the JDK
 * HttpClient.
 */
@AutoConfiguration(before = RangerAutoConfiguration.class)
@ConditionalOnClass({OpenTelemetry.class, RangerTracing.class})
@ConditionalOnProperty(prefix = "ranger.tracing", name = "enabled", matchIfMissing = true)
public class RangerTracingAutoConfiguration {
    @Bean
    public RangerAdminClientCustomizer rangerAdminTracingCustomizer(ObjectProvider<OpenTelemetry> otel) {
        return b -> otel.ifAvailable(o -> b.interceptor(RangerTracing.create(o)));
    }

    @Bean
    public RangerPdpClientCustomizer rangerPdpTracingCustomizer(ObjectProvider<OpenTelemetry> otel) {
        return b -> otel.ifAvailable(o -> b.interceptor(RangerTracing.create(o)));
    }

    @Bean
    public RangerKmsClientCustomizer rangerKmsTracingCustomizer(ObjectProvider<OpenTelemetry> otel) {
        return b -> otel.ifAvailable(o -> b.interceptor(RangerTracing.create(o)));
    }
}
