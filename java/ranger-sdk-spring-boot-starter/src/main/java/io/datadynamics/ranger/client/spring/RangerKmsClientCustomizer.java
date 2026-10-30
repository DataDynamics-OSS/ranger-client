package io.datadynamics.ranger.client.spring;

import io.datadynamics.ranger.client.kms.RangerKmsClient;

/** Adjusts the auto-configured {@link RangerKmsClient} builder after the {@code ranger.*} properties are applied. */
@FunctionalInterface
public interface RangerKmsClientCustomizer {
    void customize(RangerKmsClient.Builder builder);
}
