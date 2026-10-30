package io.datadynamics.ranger.client.spring;

import io.datadynamics.ranger.client.admin.RangerAdminClient;
import io.datadynamics.ranger.client.kms.RangerKmsClient;
import io.datadynamics.ranger.client.model.ResourceInfo;
import io.datadynamics.ranger.client.pdp.RangerPdpClient;
import io.datadynamics.ranger.client.testing.MockServer;
import io.datadynamics.ranger.client.testing.MockServer.Reply;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class RangerAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
            RangerAutoConfiguration.class, RangerHealthAutoConfiguration.class, RangerMetricsAutoConfiguration.class,
            RangerTracingAutoConfiguration.class));

    @Test
    void nothingWithoutUrls() {
        runner.run(ctx -> assertThat(ctx).hasNotFailed()
                .doesNotHaveBean(RangerAdminClient.class).doesNotHaveBean(RangerPdpClient.class)
                .doesNotHaveBean(RangerKmsClient.class).doesNotHaveBean(RangerHealthIndicator.class));
    }

    @Test
    void clientsFromProperties() {
        runner.withPropertyValues(
                        "ranger.admin.urls=https://r1:6182,https://r2:6182",
                        "ranger.admin.server-version=2.9.0",
                        "ranger.admin.auth.type=basic", "ranger.admin.auth.username=admin", "ranger.admin.auth.password=${RANGER_PW:x}",
                        "ranger.pdp.urls[0]=https://pdp:6500", "ranger.pdp.service=dev_hive", "ranger.pdp.service-type=hive",
                        "ranger.pdp.auth.type=header", "ranger.pdp.auth.user=trino")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RangerAdminClient.class).hasSingleBean(RangerPdpClient.class)
                            .doesNotHaveBean(RangerKmsClient.class);
                    RangerAdminClient admin = ctx.getBean(RangerAdminClient.class);
                    assertThat(admin.config().urls()).hasSize(2);
                    assertThat(admin.config().auth().describe()).contains("admin");
                    assertThat(admin.nodeAvailability()).containsValues(true, true);
                    assertThat(ctx).hasBean("rangerAdminHealthIndicator").hasBean("rangerPdpHealthIndicator")
                            .doesNotHaveBean("rangerKmsHealthIndicator");
                });
    }

    @Test
    void literalSecretsAreFlagged() {
        // no Logback on the test classpath: spring-jcl logs through java.util.logging
        Logger log = Logger.getLogger(ClientSettings.class.getName());
        List<String> messages = new ArrayList<>();
        Handler h = new Handler() {
            @Override
            public void publish(LogRecord r) {
                messages.add(r.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        log.addHandler(h);
        try {
            runner.withPropertyValues("ranger.admin.urls=https://r1:6182", "ranger.admin.auth.type=basic",
                    "ranger.admin.auth.username=admin", "ranger.admin.auth.password=hunter2").run(ctx -> assertThat(ctx).hasNotFailed());
            runner.withPropertyValues("ranger.pdp.urls=https://p:6500", "ranger.pdp.auth.type=jwt",
                    "ranger.pdp.auth.token=${PDP_TOKEN:t}").run(ctx -> assertThat(ctx).hasNotFailed());
        } finally {
            log.removeHandler(h);
        }
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).contains("ranger.admin.auth.password is set literally").doesNotContain("hunter2");
    }

    @Test
    void missingSettingFailsWithThePropertyName() {
        runner.withPropertyValues("ranger.kms.urls=https://kms:9393", "ranger.kms.auth.type=hadoop-simple")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause().hasMessageContaining("ranger.kms.auth.user"));
    }

    @Test
    void passwordFileCustomizerAndRequests(@TempDir Path dir) throws Exception {
        Path pw = dir.resolve("pw");
        Files.writeString(pw, "s3cret\n");
        try (MockServer s = new MockServer()) {
            s.handler(r -> Reply.of(200, "{\"id\":1,\"name\":\"hive\"}"));
            runner.withPropertyValues("ranger.admin.urls=" + s.url(), "ranger.admin.auth.type=basic",
                            "ranger.admin.auth.username=admin", "ranger.admin.auth.password-file=" + pw,
                            "ranger.admin.headers.X-Team=data")
                    .withBean(RangerAdminClientCustomizer.class, () -> b -> b.header("X-App", "catalog"))
                    .run(ctx -> {
                        ctx.getBean(RangerAdminClient.class).serviceDefs().get(1);
                        assertThat(s.last().header("Authorization")).isEqualTo("Basic YWRtaW46czNjcmV0");
                        assertThat(s.last().header("X-Team")).isEqualTo("data");
                        assertThat(s.last().header("X-App")).isEqualTo("catalog");
                    });
        }
    }

    @Test
    void applicationBeanWins() {
        RangerAdminClient own = RangerAdminClient.builder().urls("https://mine:6182").build();
        runner.withPropertyValues("ranger.admin.urls=https://r1:6182").withBean(RangerAdminClient.class, () -> own)
                .run(ctx -> assertThat(ctx.getBean(RangerAdminClient.class)).isSameAs(own));
    }

    @Test
    void pdpDefaultsAndCache() {
        try (MockServer s = new MockServer()) {
            s.handler(r -> Reply.of(200, "{\"decision\":\"ALLOW\"}"));
            runner.withPropertyValues("ranger.pdp.urls=" + s.url(), "ranger.pdp.service=dev_hive", "ranger.pdp.service-type=hive",
                            "ranger.pdp.cache.ttl=1m")
                    .run(ctx -> {
                        RangerPdpClient pdp = ctx.getBean(RangerPdpClient.class);
                        ResourceInfo res = ResourceInfo.of("database", "sales");
                        assertThat(pdp.isAllowed("alice", res, "select")).isTrue();
                        assertThat(pdp.isAllowed("alice", res, "select")).isTrue();
                        assertThat(s.requests()).hasSize(1);
                        assertThat(s.last().bodyText()).contains("\"serviceName\":\"dev_hive\"", "\"serviceType\":\"hive\"");
                    });
        }
    }

    @Test
    void adminHealthUpAndAuthFailureIsDistinguished() {
        try (MockServer s = new MockServer()) {
            s.enqueue(Reply.of(200, "[]")).enqueue(Reply.of(401)).enqueue(Reply.of(401));
            runner.withPropertyValues("ranger.admin.urls=" + s.url()).run(ctx -> {
                RangerHealthIndicator<?> h = ctx.getBean("rangerAdminHealthIndicator", RangerHealthIndicator.class);
                Health up = h.health();
                assertThat(up.getStatus()).isEqualTo(Status.UP);
                assertThat(s.last().rawPath).isEqualTo("/service/public/v2/api/servicedef");
                assertThat(s.last().query).containsEntry("pageSize", "1");
                assertThat(up.getDetails().get("nodes")).isEqualTo(Map.of(s.url(), "UP"));

                Health down = h.health();
                assertThat(down.getStatus()).isEqualTo(Status.DOWN);
                assertThat(down.getDetails()).containsEntry("reachable", true).containsEntry("status", 401)
                        .containsEntry("error", "RangerAuthenticationException").containsKey("hint");
            });
        }
    }

    @Test
    void pdpHealthUsesReadinessEndpoint() {
        try (MockServer s = new MockServer()) {
            s.enqueue(Reply.of(200, "{\"status\":\"READY\",\"service\":\"ranger-pdp\",\"ready\":true,"
                            + "\"authorizerInitialized\":true,\"acceptingRequests\":true,\"loadedServicesCount\":3}"))
             .enqueue(Reply.of(503, "{\"status\":\"NOT_READY\",\"ready\":false,\"authorizerInitialized\":false}"));
            runner.withPropertyValues("ranger.pdp.urls=" + s.url()).run(ctx -> {
                RangerHealthIndicator<?> h = ctx.getBean("rangerPdpHealthIndicator", RangerHealthIndicator.class);
                Health up = h.health();
                assertThat(up.getStatus()).isEqualTo(Status.UP);
                assertThat(up.getDetails()).containsEntry("status", "READY").containsEntry("loadedServicesCount", 3);
                assertThat(s.last().rawPath).isEqualTo("/health/ready");

                Health down = h.health();
                assertThat(down.getStatus()).isEqualTo(Status.DOWN);
                assertThat(down.getDetails()).containsEntry("status", 503).containsEntry("reachable", true);
                assertThat(s.requests()).hasSize(2); // readiness is never retried
            });
        }
    }

    @Test
    void unreachableServerIsDownAndNotReachable() {
        runner.withPropertyValues("ranger.kms.urls=http://127.0.0.1:9", "ranger.kms.retry.max-attempts=1").run(ctx -> {
            Health h = ctx.getBean("rangerKmsHealthIndicator", RangerHealthIndicator.class).health();
            assertThat(h.getStatus()).isEqualTo(Status.DOWN);
            assertThat(h.getDetails()).containsEntry("reachable", false)
                    .containsEntry("nodes", Map.of("http://127.0.0.1:9", "COOLDOWN"));
        });
    }

    @Test
    void healthCanBeDisabled() {
        runner.withPropertyValues("ranger.admin.urls=https://r1:6182", "management.health.ranger.enabled=false")
                .run(ctx -> assertThat(ctx).hasSingleBean(RangerAdminClient.class).doesNotHaveBean(RangerHealthIndicator.class));
    }

    @Test
    void requestMetrics() {
        try (MockServer s = new MockServer()) {
            s.enqueue(Reply.of(503)).enqueue(Reply.of(200, "{\"id\":1}"));
            runner.withPropertyValues("ranger.admin.urls=" + s.url(), "ranger.admin.retry.base-delay=1ms")
                    .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                    .run(ctx -> {
                        ctx.getBean(RangerAdminClient.class).policies().get(1);
                        MeterRegistry reg = ctx.getBean(MeterRegistry.class);
                        String node = s.url();
                        Timer failed = reg.find(RangerMetricsListener.REQUESTS)
                                .tags("server", "admin", "node", node, "method", "GET", "status", "503", "outcome", "SERVER_ERROR").timer();
                        Timer ok = reg.find(RangerMetricsListener.REQUESTS).tags("status", "200", "outcome", "SUCCESS").timer();
                        assertThat(failed).isNotNull();
                        assertThat(failed.count()).isEqualTo(1);
                        assertThat(ok.count()).isEqualTo(1);
                        assertThat(reg.find(RangerMetricsListener.RETRIES).counter().count()).isEqualTo(1);
                    });
        }
    }

    @Test
    void metricsCanBeDisabled() {
        try (MockServer s = new MockServer()) {
            s.enqueue(Reply.of(200, "{\"id\":1}"));
            runner.withPropertyValues("ranger.admin.urls=" + s.url(), "ranger.metrics.enabled=false")
                    .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                    .run(ctx -> {
                        ctx.getBean(RangerAdminClient.class).policies().get(1);
                        assertThat(ctx.getBean(MeterRegistry.class).find(RangerMetricsListener.REQUESTS).timer()).isNull();
                    });
        }
    }

    @Test
    void tracingWithAnOpenTelemetryBean() {
        InMemorySpanExporter spans = InMemorySpanExporter.create();
        OpenTelemetrySdk otel = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        try (MockServer s = new MockServer()) {
            s.enqueue(Reply.of(200, "{\"id\":1}")).enqueue(Reply.of(200, "{\"id\":1}"));
            runner.withPropertyValues("ranger.admin.urls=" + s.url())
                    .withBean(OpenTelemetry.class, () -> otel)
                    .run(ctx -> {
                        ctx.getBean(RangerAdminClient.class).policies().get(1);
                        assertThat(spans.getFinishedSpanItems()).hasSize(1);
                        assertThat(spans.getFinishedSpanItems().get(0).getAttributes().asMap().toString()).contains("ranger.server=admin");
                        assertThat(s.last().header("traceparent")).contains(spans.getFinishedSpanItems().get(0).getSpanId());
                    });
            spans.reset();
            runner.withPropertyValues("ranger.admin.urls=" + s.url(), "ranger.tracing.enabled=false")
                    .withBean(OpenTelemetry.class, () -> otel)
                    .run(ctx -> {
                        ctx.getBean(RangerAdminClient.class).policies().get(1);
                        assertThat(spans.getFinishedSpanItems()).isEmpty();
                        assertThat(s.last().header("traceparent")).isNull();
                    });
        }
    }
}
