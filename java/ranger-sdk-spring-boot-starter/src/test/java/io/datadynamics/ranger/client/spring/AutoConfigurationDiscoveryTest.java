package io.datadynamics.ranger.client.spring;

import io.datadynamics.ranger.client.admin.RangerAdminClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** The starter is picked up through META-INF/spring/...AutoConfiguration.imports, as in a real application. */
class AutoConfigurationDiscoveryTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class App {
    }

    @Test
    void discoveredByEnableAutoConfiguration() {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(App.class).web(WebApplicationType.NONE)
                .properties("ranger.admin.urls=https://r1:6182", "spring.main.banner-mode=off").run()) {
            assertThat(ctx.getBean(RangerAdminClient.class).config().urls()).hasSize(1);
            assertThat(ctx.containsBean("rangerAdminHealthIndicator")).isTrue();
        }
    }
}
