package io.datadynamics.ranger.demo.config;

import io.datadynamics.ranger.client.spring.RangerAdminClientCustomizer;
import io.datadynamics.ranger.client.spring.RangerKmsClientCustomizer;
import io.datadynamics.ranger.client.spring.RangerPdpClientCustomizer;
import io.datadynamics.ranger.demo.trace.SdkCalls;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Adds the recording interceptor to the clients the starter builds from {@code ranger.*}.
 * This is the starter's customizer hook: the beans stay auto-configured, only the builder is adjusted.
 */
@Configuration(proxyBeanMethods = false)
public class RecordingConfig {
    @Bean
    RangerAdminClientCustomizer recordAdminCalls(SdkCalls calls) {
        return b -> b.interceptor(calls.interceptor("admin"));
    }

    @Bean
    RangerPdpClientCustomizer recordPdpCalls(SdkCalls calls) {
        return b -> b.interceptor(calls.interceptor("pdp"));
    }

    @Bean
    RangerKmsClientCustomizer recordKmsCalls(SdkCalls calls) {
        return b -> b.interceptor(calls.interceptor("kms"));
    }
}
