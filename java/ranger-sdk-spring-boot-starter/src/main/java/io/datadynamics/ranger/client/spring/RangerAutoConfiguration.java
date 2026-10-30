package io.datadynamics.ranger.client.spring;

import io.datadynamics.ranger.client.admin.RangerAdminClient;
import io.datadynamics.ranger.client.kms.RangerKmsClient;
import io.datadynamics.ranger.client.pdp.RangerPdpClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Creates {@link RangerAdminClient}, {@link RangerPdpClient} and {@link RangerKmsClient} beans from
 * {@code ranger.admin.*}, {@code ranger.pdp.*} and {@code ranger.kms.*}. Each bean exists only when its
 * {@code urls} are set and no bean of that type is defined by the application. Builders can be adjusted
 * with {@code Ranger*ClientCustomizer} beans. Clients are thread-safe singletons, closed with the context.
 */
@AutoConfiguration
@EnableConfigurationProperties(RangerProperties.class)
public class RangerAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnRangerServer("admin")
    public RangerAdminClient rangerAdminClient(RangerProperties props, Environment env,
                                               ObjectProvider<RangerAdminClientCustomizer> customizers) {
        RangerAdminClient.Builder b = ClientSettings.apply(RangerAdminClient.builder(), "admin", props.getAdmin(), env);
        customizers.orderedStream().forEach(c -> c.customize(b));
        return b.build();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnRangerServer("pdp")
    public RangerPdpClient rangerPdpClient(RangerProperties props, Environment env,
                                           ObjectProvider<RangerPdpClientCustomizer> customizers) {
        RangerProperties.Pdp p = props.getPdp();
        RangerPdpClient.Builder b = ClientSettings.apply(RangerPdpClient.builder(), "pdp", p, env);
        if (p.getService() != null || p.getServiceType() != null) {
            b.service(p.getService(), p.getServiceType());
        }
        if (p.getCache().getTtl() != null) {
            b.cache(p.getCache().getTtl(), p.getCache().getMaxEntries());
        }
        customizers.orderedStream().forEach(c -> c.customize(b));
        return b.build();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnRangerServer("kms")
    public RangerKmsClient rangerKmsClient(RangerProperties props, Environment env,
                                           ObjectProvider<RangerKmsClientCustomizer> customizers) {
        RangerKmsClient.Builder b = ClientSettings.apply(RangerKmsClient.builder(), "kms", props.getKms(), env);
        customizers.orderedStream().forEach(c -> c.customize(b));
        return b.build();
    }
}
