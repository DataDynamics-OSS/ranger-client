package io.datadynamics.ranger.demo.web.pdp;

import io.datadynamics.ranger.client.model.AccessContext;
import io.datadynamics.ranger.client.pdp.RangerPdpClient;
import io.datadynamics.ranger.demo.trace.SdkCalls;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;

/**
 * A second PDP client with the SDK's decision cache turned on, for the cache comparison on the PDP page.
 *
 * It is a plain holder, not a {@code RangerPdpClient} bean: defining a bean of that type would make the
 * starter's auto-configured client back off ({@code @ConditionalOnMissingBean}).
 */
@Component
public class CachedPdpClient implements DisposableBean {
    public static final Duration TTL = Duration.ofSeconds(30);

    private final RangerPdpClient client;

    public CachedPdpClient(RangerPdpClient pdp, SdkCalls calls) {
        AccessContext ctx = pdp.context();
        this.client = RangerPdpClient.builder()
                .urls(pdp.config().urls().stream().map(URI::toString).toList())
                .auth(pdp.config().auth())
                .service(ctx.getServiceName(), ctx.getServiceType())
                .cache(TTL, 1_000)
                .interceptor(calls.interceptor("pdp-cached"))
                .build();
    }

    public RangerPdpClient get() {
        return client;
    }

    @Override
    public void destroy() {
        client.close();
    }
}
