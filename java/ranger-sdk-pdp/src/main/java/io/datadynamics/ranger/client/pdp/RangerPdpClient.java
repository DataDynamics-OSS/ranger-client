package io.datadynamics.ranger.client.pdp;

import io.datadynamics.ranger.client.core.AbstractClientBuilder;
import io.datadynamics.ranger.client.core.AbstractRangerClient;
import io.datadynamics.ranger.client.core.RetryPolicy;
import io.datadynamics.ranger.client.core.ServerKind;
import io.datadynamics.ranger.client.core.http.HttpCall;
import io.datadynamics.ranger.client.core.http.Transport;
import io.datadynamics.ranger.client.model.AccessContext;
import io.datadynamics.ranger.client.model.AccessInfo;
import io.datadynamics.ranger.client.model.AuthzRequest;
import io.datadynamics.ranger.client.model.AuthzResult;
import io.datadynamics.ranger.client.model.MultiAuthzRequest;
import io.datadynamics.ranger.client.model.MultiAuthzResult;
import io.datadynamics.ranger.client.model.RangerJson;
import io.datadynamics.ranger.client.model.RangerModel;
import io.datadynamics.ranger.client.model.ResourceInfo;
import io.datadynamics.ranger.client.model.ResourcePermissions;
import io.datadynamics.ranger.client.model.ResourcePermissionsRequest;
import io.datadynamics.ranger.client.model.UserInfo;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Client of the Ranger 2.9 Policy Decision Point ({@code /authz/v1}).
 *
 * <pre>{@code
 * RangerPdpClient pdp = RangerPdpClient.builder().url("https://ranger-pdp:6500")
 *     .auth(JwtAuth.supplier(tokenSource)).service("dev_hive", "hive").build();
 * boolean ok = pdp.isAllowed("alice", ResourceInfo.of("database", "sales", "table", "orders"), "select");
 * }</pre>
 *
 * Decisions are side-effect free, so they are retried like GETs. If the caller differs from the
 * request's user, the caller must be a delegation user of the service (otherwise
 * {@code RangerAccessDeniedException} carrying the server's reason).
 */
public final class RangerPdpClient extends AbstractRangerClient<RangerPdpClient> {
    static final String BASE = "/authz/v1";

    private final String defaultService;
    private final String defaultServiceType;
    private final DecisionCache cache;

    private RangerPdpClient(Builder b) {
        super(b.transportForClient());
        this.defaultService = b.service;
        this.defaultServiceType = b.serviceType;
        this.cache = b.cacheTtl == null ? null : new DecisionCache(b.cacheTtl, b.cacheSize);
    }

    public static Builder builder() {
        return new Builder();
    }

    public AuthzResult authorize(AuthzRequest request) {
        return post(BASE + "/authorize", request, AuthzResult.class);
    }

    /** Convenience form using the client's default service. */
    public AuthzResult authorize(String user, ResourceInfo resource, String... permissions) {
        return authorize(request(UserInfo.builder().name(user).build(), resource, permissions));
    }

    public CompletableFuture<AuthzResult> authorizeAsync(AuthzRequest request) {
        return async(c -> c.authorize(request));
    }

    public boolean isAllowed(String user, ResourceInfo resource, String... permissions) {
        return authorize(user, resource, permissions).isAllowed();
    }

    /** Several accesses for one user in one round trip; results come back in request order. */
    public MultiAuthzResult authorizeMulti(MultiAuthzRequest request) {
        return post(BASE + "/authorizeMulti", request, MultiAuthzResult.class);
    }

    public MultiAuthzResult authorizeMulti(UserInfo user, List<AccessInfo> accesses) {
        return authorizeMulti(MultiAuthzRequest.builder().requestId(newId()).user(user).accesses(accesses).context(context()).build());
    }

    /** Who (users, groups, roles) holds which permission on a resource. */
    public ResourcePermissions permissions(ResourcePermissionsRequest request) {
        return post(BASE + "/permissions", request, ResourcePermissions.class);
    }

    public ResourcePermissions permissions(ResourceInfo resource) {
        return permissions(ResourcePermissionsRequest.builder().requestId(newId()).resource(resource).context(context()).build());
    }

    /** Build a request against the default service. */
    public AuthzRequest request(UserInfo user, ResourceInfo resource, String... permissions) {
        return AuthzRequest.builder()
                .requestId(newId())
                .user(user)
                .access(AccessInfo.builder().resource(resource).permissions(List.of(permissions)).build())
                .context(context())
                .build();
    }

    /** A context for the client's default service; adjust with {@code toBuilder()}. */
    public AccessContext context() {
        return AccessContext.builder().serviceName(defaultService).serviceType(defaultServiceType)
                .accessTime(System.currentTimeMillis()).build();
    }

    public void clearCache() {
        if (cache != null) {
            cache.clear();
        }
    }

    /**
     * Readiness of the PDP server ({@code GET /health/ready}): {@code status} is {@code READY} when the
     * authorizer is initialised and accepting requests. A not-ready server answers 503, which surfaces as
     * {@code RangerServerException}. Never retried.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> ready() {
        return RangerJson.read(transport.send(HttpCall.get("/health/ready").retry(RetryPolicy.none())).body(), Map.class);
    }

    private <T> T post(String path, RangerModel body, Class<T> type) {
        JsonNode tree = RangerJson.tree(body);
        String key = cache == null ? null : DecisionCache.key(path, tree);
        if (key != null) {
            Object hit = cache.get(key);
            if (hit != null) {
                return type.cast(hit);
            }
        }
        HttpCall call = HttpCall.post(path).json(body).idempotent(true);
        String requestId = tree.path("requestId").asText(null);
        if (requestId != null) {
            call.header(Transport.REQUEST_ID_HEADER, requestId); // same id in the header, the body and the result
        }
        T result = transport.send(call).as(type);
        if (key != null && result != null) {
            cache.put(key, result);
        }
        return result;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Builder; see {@link AbstractClientBuilder} for the shared options. */
    public static final class Builder extends AbstractClientBuilder<RangerPdpClient, Builder> {
        private String service;
        private String serviceType;
        private Duration cacheTtl;
        private int cacheSize = 10_000;

        Builder() {
            super(ServerKind.PDP);
        }

        /** Default service name and type for convenience calls. */
        public Builder service(String serviceName, String serviceType) {
            this.service = serviceName;
            this.serviceType = serviceType;
            return this;
        }

        /** Enable a client-side decision cache (off by default: policy changes would show up late). */
        public Builder cache(Duration ttl, int maxEntries) {
            this.cacheTtl = ttl;
            this.cacheSize = maxEntries;
            return this;
        }

        Transport transportForClient() {
            return transport();
        }

        @Override
        public RangerPdpClient build() {
            return new RangerPdpClient(this);
        }
    }
}
