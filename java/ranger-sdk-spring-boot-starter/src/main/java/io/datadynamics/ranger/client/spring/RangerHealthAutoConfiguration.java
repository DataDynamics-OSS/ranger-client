package io.datadynamics.ranger.client.spring;

import io.datadynamics.ranger.client.admin.RangerAdminClient;
import io.datadynamics.ranger.client.kms.RangerKmsClient;
import io.datadynamics.ranger.client.pdp.RangerPdpClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Actuator health contributors {@code rangerAdmin}, {@code rangerPdp} and {@code rangerKms}.
 * Disable with {@code management.health.ranger.enabled=false}.
 */
@AutoConfiguration(after = RangerAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnEnabledHealthIndicator("ranger")
public class RangerHealthAutoConfiguration {
    @Bean
    @ConditionalOnBean(RangerAdminClient.class)
    @ConditionalOnMissingBean(name = "rangerAdminHealthIndicator")
    public RangerHealthIndicator<RangerAdminClient> rangerAdminHealthIndicator(RangerAdminClient admin) {
        return new RangerHealthIndicator<>(admin, a -> {
            a.serviceDefs().search(Map.of(), 0, 1); // authenticated, tiny
            return Map.of();
        });
    }

    @Bean
    @ConditionalOnBean(RangerPdpClient.class)
    @ConditionalOnMissingBean(name = "rangerPdpHealthIndicator")
    public RangerHealthIndicator<RangerPdpClient> rangerPdpHealthIndicator(RangerPdpClient pdp) {
        return new RangerHealthIndicator<>(pdp, p -> {
            Map<String, Object> ready = p.ready();
            Map<String, Object> details = new LinkedHashMap<>();
            for (String k : new String[] {"status", "authorizerInitialized", "acceptingRequests", "loadedServicesCount"}) {
                if (ready.containsKey(k)) {
                    details.put(k, ready.get(k));
                }
            }
            return details;
        });
    }

    @Bean
    @ConditionalOnBean(RangerKmsClient.class)
    @ConditionalOnMissingBean(name = "rangerKmsHealthIndicator")
    public RangerHealthIndicator<RangerKmsClient> rangerKmsHealthIndicator(RangerKmsClient kms) {
        return new RangerHealthIndicator<>(kms, k -> {
            k.status();
            return Map.of();
        });
    }
}
