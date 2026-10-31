package io.datadynamics.ranger.demo.web.lab;

import io.datadynamics.ranger.client.admin.RangerAdminClient;
import io.datadynamics.ranger.client.core.RetryPolicy;
import io.datadynamics.ranger.client.core.auth.BasicAuth;
import io.datadynamics.ranger.client.core.auth.HadoopSimpleAuth;
import io.datadynamics.ranger.client.core.auth.TrustedHeaderAuth;
import io.datadynamics.ranger.client.kms.RangerKmsClient;
import io.datadynamics.ranger.client.pdp.RangerPdpClient;
import io.datadynamics.ranger.demo.trace.SdkCalls;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Extra, deliberately misconfigured clients for the Resilience Lab. They are built with the SDK builders directly
 * (not as beans of the client types, which would make the starter's auto-configured clients back off) from the
 * URLs and auth of the auto-configured clients, and record their calls like every other demo client.
 */
@Component
public class LabClients implements AutoCloseable {
    /** Never resolves (.invalid is reserved), so the attempt fails before anything is sent. */
    public static final String DEAD_URL = "http://ranger-admin-down.invalid:6080";

    private final List<AutoCloseable> owned = new ArrayList<>();

    final RangerAdminClient failover;
    final RangerAdminClient slow;
    final RangerAdminClient wrongPassword;
    final RangerAdminClient unreachable;
    final RangerPdpClient pdpAsMallory;
    final RangerKmsClient kmsAsMallory;

    public LabClients(RangerAdminClient admin, Optional<RangerPdpClient> pdp, Optional<RangerKmsClient> kms, SdkCalls calls) {
        List<String> adminUrls = admin.config().urls().stream().map(URI::toString).toList();

        List<String> withDead = new ArrayList<>();
        withDead.add(DEAD_URL);
        withDead.addAll(adminUrls);
        failover = own(RangerAdminClient.builder().urls(withDead).auth(admin.config().auth())
                .interceptor(calls.interceptor("lab-failover")).build());

        slow = own(RangerAdminClient.builder().urls(adminUrls).auth(admin.config().auth())
                .readTimeout(Duration.ofMillis(1))
                .retry(RetryPolicy.exponential(3, Duration.ofMillis(100)))
                .interceptor(calls.interceptor("lab-timeout")).build());

        wrongPassword = own(RangerAdminClient.builder().urls(adminUrls).auth(BasicAuth.of("admin", "wrong-password".toCharArray()))
                .serverVersion("2.9.0").interceptor(calls.interceptor("lab-401")).build());

        unreachable = own(RangerAdminClient.builder().urls(DEAD_URL).auth(admin.config().auth())
                .retry(RetryPolicy.exponential(2, Duration.ofMillis(100)))
                .interceptor(calls.interceptor("lab-unreachable")).build());

        pdpAsMallory = pdp.map(p -> own(RangerPdpClient.builder()
                .urls(p.config().urls().stream().map(URI::toString).toList())
                .auth(TrustedHeaderAuth.of("X-Forwarded-User", "mallory"))
                .service("demo_hive", "hive")
                .interceptor(calls.interceptor("lab-403")).build())).orElse(null);

        kmsAsMallory = kms.map(k -> own(RangerKmsClient.builder()
                .urls(k.config().urls().stream().map(URI::toString).toList())
                .auth(HadoopSimpleAuth.of("mallory"))
                .interceptor(calls.interceptor("lab-403")).build())).orElse(null);
    }

    private <T extends AutoCloseable> T own(T client) {
        owned.add(client);
        return client;
    }

    @Override
    public void close() throws Exception {
        for (AutoCloseable c : owned) {
            c.close();
        }
    }
}
