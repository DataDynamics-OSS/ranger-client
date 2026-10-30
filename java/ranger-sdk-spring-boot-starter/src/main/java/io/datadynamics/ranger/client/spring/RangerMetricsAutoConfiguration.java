package io.datadynamics.ranger.client.spring;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Adds a {@link RangerMetricsListener} to each auto-configured client when a {@link MeterRegistry} bean
 * exists. Disable with {@code ranger.metrics.enabled=false}.
 */
@AutoConfiguration(before = RangerAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(prefix = "ranger.metrics", name = "enabled", matchIfMissing = true)
public class RangerMetricsAutoConfiguration {
    @Bean
    public RangerAdminClientCustomizer rangerAdminMetricsCustomizer(ObjectProvider<MeterRegistry> registry) {
        return b -> registry.ifAvailable(r -> b.listener(new RangerMetricsListener(r, "admin")));
    }

    @Bean
    public RangerPdpClientCustomizer rangerPdpMetricsCustomizer(ObjectProvider<MeterRegistry> registry) {
        return b -> registry.ifAvailable(r -> b.listener(new RangerMetricsListener(r, "pdp")));
    }

    @Bean
    public RangerKmsClientCustomizer rangerKmsMetricsCustomizer(ObjectProvider<MeterRegistry> registry) {
        return b -> registry.ifAvailable(r -> b.listener(new RangerMetricsListener(r, "kms")));
    }
}
