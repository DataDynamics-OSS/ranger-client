package io.datadynamics.ranger.client.spring;

import io.datadynamics.ranger.client.core.AbstractClientBuilder;
import io.datadynamics.ranger.client.core.RetryPolicy;
import io.datadynamics.ranger.client.core.Tls;
import io.datadynamics.ranger.client.core.auth.AuthProvider;
import io.datadynamics.ranger.client.core.auth.BasicAuth;
import io.datadynamics.ranger.client.core.auth.HadoopSimpleAuth;
import io.datadynamics.ranger.client.core.auth.JwtAuth;
import io.datadynamics.ranger.client.core.auth.NoAuth;
import io.datadynamics.ranger.client.core.auth.TrustedHeaderAuth;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.util.ClassUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Applies {@link RangerProperties.Server} settings to an SDK client builder. */
final class ClientSettings {
    private static final Log LOG = LogFactory.getLog(ClientSettings.class);
    private static final String KERBEROS_CLASS = "io.datadynamics.ranger.client.auth.kerberos.KerberosAuth";

    private ClientSettings() {
    }

    static <B extends AbstractClientBuilder<?, B>> B apply(B b, String name, RangerProperties.Server s, Environment env) {
        String prefix = "ranger." + name;
        b.urls(s.getUrls());
        if (s.getServerVersion() != null) {
            b.serverVersion(s.getServerVersion());
        }
        b.auth(auth(prefix + ".auth", s.getAuth(), env));
        Tls tls = tls(prefix + ".tls", s.getTls());
        if (tls != null) {
            b.tls(tls);
        }
        RangerProperties.Retry r = s.getRetry();
        if (r.getMaxAttempts() != null || r.getBaseDelay() != null || r.getMaxDelay() != null) {
            RetryPolicy defaults = RetryPolicy.defaults();
            RetryPolicy policy = RetryPolicy.exponential(r.getMaxAttempts() != null ? r.getMaxAttempts() : defaults.maxAttempts(),
                    r.getBaseDelay() != null ? r.getBaseDelay() : defaults.delay(1));
            b.retry(r.getMaxDelay() != null ? policy.withMaxDelay(r.getMaxDelay()) : policy);
        }
        b.retryNonIdempotent(r.isNonIdempotent());
        if (s.getConnectTimeout() != null) {
            b.connectTimeout(s.getConnectTimeout());
        }
        if (s.getReadTimeout() != null) {
            b.readTimeout(s.getReadTimeout());
        }
        if (s.getCooldown() != null) {
            b.cooldown(s.getCooldown());
        }
        s.getHeaders().forEach(b::header);
        return b;
    }

    static AuthProvider auth(String prefix, RangerProperties.Auth a, Environment env) {
        switch (a.getType()) {
            case NONE:
                return NoAuth.INSTANCE;
            case BASIC:
                require(a.getUsername(), prefix + ".username");
                if (a.getPasswordFile() != null) {
                    Path file = a.getPasswordFile();
                    return BasicAuth.of(a.getUsername(), () -> read(file).toCharArray());
                }
                require(a.getPassword(), prefix + ".password or " + prefix + ".password-file");
                warnIfLiteral(env, prefix + ".password");
                return BasicAuth.of(a.getUsername(), a.getPassword().toCharArray());
            case JWT:
                if (a.getTokenFile() != null) {
                    Path file = a.getTokenFile();
                    return JwtAuth.supplier(() -> read(file));
                }
                require(a.getToken(), prefix + ".token or " + prefix + ".token-file");
                warnIfLiteral(env, prefix + ".token");
                return JwtAuth.token(a.getToken());
            case KERBEROS:
                if (!ClassUtils.isPresent(KERBEROS_CLASS, ClientSettings.class.getClassLoader())) {
                    throw new IllegalStateException(prefix + ".type=kerberos needs io.datadynamics.ranger.client:ranger-sdk-auth-kerberos on the classpath");
                }
                return KerberosSupport.create(prefix, a);
            case HEADER:
                require(a.getUser(), prefix + ".user");
                return a.getHeader() == null ? TrustedHeaderAuth.of(a.getUser()) : TrustedHeaderAuth.of(a.getHeader(), a.getUser());
            case HADOOP_SIMPLE:
                require(a.getUser(), prefix + ".user");
                return HadoopSimpleAuth.of(a.getUser());
            default:
                throw new IllegalArgumentException("unsupported " + prefix + ".type " + a.getType());
        }
    }

    static Tls tls(String prefix, RangerProperties.Tls t) {
        Tls tls = null;
        if (t.isInsecure()) {
            LOG.warn(prefix + ".insecure=true: server certificates are NOT verified");
            tls = Tls.insecure();
        } else if (t.getCa() != null) {
            tls = Tls.pem(t.getCa());
        } else if (t.getTruststore() != null) {
            tls = Tls.truststore(t.getTruststore(), chars(t.getTruststorePassword()));
        }
        if (t.getKeystore() != null) {
            tls = (tls != null ? tls : Tls.system()).withKeystore(t.getKeystore(), chars(t.getKeystorePassword()));
        }
        return tls;
    }

    static void require(Object value, String property) {
        if (value == null || (value instanceof String && ((String) value).isBlank())) {
            throw new IllegalArgumentException(property + " is required");
        }
    }

    private static char[] chars(String s) {
        return s == null ? null : s.toCharArray();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /** Warn when a secret is written literally in a config file rather than taken from a placeholder or the environment. */
    static void warnIfLiteral(Environment env, String key) {
        if (!(env instanceof ConfigurableEnvironment)) {
            return;
        }
        for (PropertySource<?> source : ((ConfigurableEnvironment) env).getPropertySources()) {
            String n = source.getName();
            if (n.equals("configurationProperties") || n.startsWith("system")) {
                continue; // the attached view repeats every source; env vars and -D flags are fine
            }
            Object raw = source.getProperty(key);
            if (raw != null) {
                if (!raw.toString().contains("${")) {
                    LOG.warn(key + " is set literally in '" + n + "'; use a ${...} placeholder or the *-file property");
                }
                return;
            }
        }
    }
}
