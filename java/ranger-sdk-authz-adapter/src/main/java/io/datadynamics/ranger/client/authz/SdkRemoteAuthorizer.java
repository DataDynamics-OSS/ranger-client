package io.datadynamics.ranger.client.authz;

import io.datadynamics.ranger.client.core.RangerException;
import io.datadynamics.ranger.client.core.RetryPolicy;
import io.datadynamics.ranger.client.core.Tls;
import io.datadynamics.ranger.client.core.auth.AuthProvider;
import io.datadynamics.ranger.client.core.auth.BasicAuth;
import io.datadynamics.ranger.client.core.auth.JwtAuth;
import io.datadynamics.ranger.client.core.auth.NoAuth;
import io.datadynamics.ranger.client.core.auth.TrustedHeaderAuth;
import io.datadynamics.ranger.client.model.AuthzRequest;
import io.datadynamics.ranger.client.model.AuthzResult;
import io.datadynamics.ranger.client.model.MultiAuthzRequest;
import io.datadynamics.ranger.client.model.MultiAuthzResult;
import io.datadynamics.ranger.client.model.RangerJson;
import io.datadynamics.ranger.client.model.ResourcePermissions;
import io.datadynamics.ranger.client.model.ResourcePermissionsRequest;
import io.datadynamics.ranger.client.pdp.RangerPdpClient;
import org.apache.ranger.authz.api.RangerAuthorizer;
import org.apache.ranger.authz.api.RangerAuthzErrorCode;
import org.apache.ranger.authz.api.RangerAuthzException;
import org.apache.ranger.authz.model.RangerAuthzRequest;
import org.apache.ranger.authz.model.RangerAuthzResult;
import org.apache.ranger.authz.model.RangerMultiAuthzRequest;
import org.apache.ranger.authz.model.RangerMultiAuthzResult;
import org.apache.ranger.authz.model.RangerResourcePermissions;
import org.apache.ranger.authz.model.RangerResourcePermissionsRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

/**
 * A drop-in {@link RangerAuthorizer} (Ranger 2.9 {@code ranger-authz-api}) that calls a remote PDP
 * through the SDK, adding failover, retry, private-CA TLS and the SDK's auth providers.
 *
 * <p>Select it with {@code ranger.authorizer.impl.class=io.datadynamics.ranger.client.authz.SdkRemoteAuthorizer} and:
 * <pre>
 * ranger.sdk.pdp.urls            = https://pdp1:6500,https://pdp2:6500
 * ranger.sdk.pdp.auth            = jwt | basic | header | none
 * ranger.sdk.pdp.auth.token.file = /run/secrets/pdp.jwt          (jwt)
 * ranger.sdk.pdp.auth.username / ranger.sdk.pdp.auth.password.file (basic)
 * ranger.sdk.pdp.auth.user / ranger.sdk.pdp.auth.header          (header, default X-Forwarded-User)
 * ranger.sdk.pdp.tls.ca          = /etc/pki/ranger-ca.pem        (optional PEM bundle)
 * ranger.sdk.pdp.retry.attempts  = 3
 * ranger.sdk.pdp.cache.ttl.ms    = 0                             (decision cache, off by default)
 * </pre>
 */
public class SdkRemoteAuthorizer extends RangerAuthorizer {
    public static final String PREFIX = "ranger.sdk.pdp.";

    private volatile RangerPdpClient client;

    public SdkRemoteAuthorizer(Properties properties) {
        super(properties);
    }

    /** Use an already configured client (properties are then ignored). */
    public SdkRemoteAuthorizer(RangerPdpClient client) {
        super(new Properties());
        this.client = client;
    }

    @Override
    public void init() throws RangerAuthzException {
        if (client != null) {
            return;
        }
        try {
            client = fromProperties(properties);
        } catch (RuntimeException e) {
            throw new RangerAuthzException(Codes.INIT_FAILED, e, e.getMessage());
        }
    }

    @Override
    public void close() {
        if (client != null) {
            client.close();
        }
    }

    @Override
    public RangerAuthzResult authorize(RangerAuthzRequest request) throws RangerAuthzException {
        validateRequest(request);
        AuthzResult r = call(() -> client.authorize(RangerJson.convert(request, AuthzRequest.class)));
        return RangerJson.convert(r, RangerAuthzResult.class);
    }

    @Override
    public RangerMultiAuthzResult authorize(RangerMultiAuthzRequest request) throws RangerAuthzException {
        validateRequest(request);
        MultiAuthzResult r = call(() -> client.authorizeMulti(RangerJson.convert(request, MultiAuthzRequest.class)));
        return RangerJson.convert(r, RangerMultiAuthzResult.class);
    }

    @Override
    public RangerResourcePermissions getResourcePermissions(RangerResourcePermissionsRequest request) throws RangerAuthzException {
        validateRequest(request);
        ResourcePermissions r = call(() -> client.permissions(RangerJson.convert(request, ResourcePermissionsRequest.class)));
        return RangerJson.convert(r, RangerResourcePermissions.class);
    }

    private interface Call<T> {
        T run();
    }

    private <T> T call(Call<T> c) throws RangerAuthzException {
        if (client == null) {
            throw new RangerAuthzException(Codes.NOT_INITIALIZED);
        }
        try {
            return c.run();
        } catch (RangerException e) {
            throw new RangerAuthzException(Codes.PDP_CALL_FAILED, e, e.getMessage());
        }
    }

    static RangerPdpClient fromProperties(Properties p) {
        String urls = p.getProperty(PREFIX + "urls");
        if (urls == null || urls.isBlank()) {
            throw new IllegalArgumentException(PREFIX + "urls is required");
        }
        RangerPdpClient.Builder b = RangerPdpClient.builder().urls(urls.split("\\s*,\\s*")).auth(auth(p));
        String ca = p.getProperty(PREFIX + "tls.ca");
        if (ca != null) {
            b.tls(Tls.pem(Path.of(ca)));
        }
        int attempts = Integer.parseInt(p.getProperty(PREFIX + "retry.attempts", "3"));
        b.retry(RetryPolicy.exponential(attempts, Duration.ofMillis(100)));
        long ttl = Long.parseLong(p.getProperty(PREFIX + "cache.ttl.ms", "0"));
        if (ttl > 0) {
            b.cache(Duration.ofMillis(ttl), Integer.parseInt(p.getProperty(PREFIX + "cache.size", "10000")));
        }
        return b.build();
    }

    static AuthProvider auth(Properties p) {
        String type = p.getProperty(PREFIX + "auth", "none").trim().toLowerCase();
        switch (type) {
            case "jwt":
                String file = required(p, "auth.token.file");
                return JwtAuth.supplier(() -> read(file));
            case "basic":
                String pwFile = required(p, "auth.password.file");
                return BasicAuth.of(required(p, "auth.username"), () -> read(pwFile).toCharArray());
            case "header":
                return TrustedHeaderAuth.of(p.getProperty(PREFIX + "auth.header", "X-Forwarded-User"), required(p, "auth.user"));
            case "none":
                return NoAuth.INSTANCE;
            default:
                throw new IllegalArgumentException("unknown " + PREFIX + "auth: " + type);
        }
    }

    private static String required(Properties p, String key) {
        String v = p.getProperty(PREFIX + key);
        if (v == null) {
            throw new IllegalArgumentException(PREFIX + key + " is required");
        }
        return v;
    }

    private static String read(String file) {
        try {
            return Files.readString(Path.of(file), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    enum Codes implements RangerAuthzErrorCode {
        INIT_FAILED(500, "SDK-001", "SDK remote authorizer initialization failed: {0}"),
        NOT_INITIALIZED(500, "SDK-002", "SDK remote authorizer used before init()"),
        PDP_CALL_FAILED(503, "SDK-003", "PDP call failed: {0}");

        private final int status;
        private final String code;
        private final String message;

        Codes(int status, String code, String message) {
            this.status = status;
            this.code = code;
            this.message = message;
        }

        @Override
        public int getHttpStatusCode() {
            return status;
        }

        @Override
        public String getCode() {
            return code;
        }

        @Override
        public String getMessage() {
            return message;
        }
    }
}
