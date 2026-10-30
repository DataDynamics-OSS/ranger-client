package io.datadynamics.ranger.client.spring;

import io.datadynamics.ranger.client.pdp.RangerPdpClient;

/** Adjusts the auto-configured {@link RangerPdpClient} builder after the {@code ranger.*} properties are applied. */
@FunctionalInterface
public interface RangerPdpClientCustomizer {
    void customize(RangerPdpClient.Builder builder);
}
