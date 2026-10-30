package io.datadynamics.ranger.client.spring;

import io.datadynamics.ranger.client.admin.RangerAdminClient;

/** Adjusts the auto-configured {@link RangerAdminClient} builder after the {@code ranger.*} properties are applied. */
@FunctionalInterface
public interface RangerAdminClientCustomizer {
    void customize(RangerAdminClient.Builder builder);
}
