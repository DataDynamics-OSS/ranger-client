package io.datadynamics.ranger.client.admin;

import io.datadynamics.ranger.client.core.AbstractClientBuilder;
import io.datadynamics.ranger.client.core.AbstractRangerClient;
import io.datadynamics.ranger.client.core.Capabilities;
import io.datadynamics.ranger.client.core.ServerKind;
import io.datadynamics.ranger.client.core.http.Transport;

/**
 * Ranger Admin client. Thread-safe; build one per application.
 *
 * <pre>{@code
 * RangerAdminClient admin = RangerAdminClient.builder()
 *     .urls("https://ranger1:6182", "https://ranger2:6182")
 *     .auth(BasicAuth.of("admin", vault::rangerPassword))
 *     .tls(Tls.pem(Path.of("/etc/pki/ranger-ca.pem")))
 *     .build();
 * RangerPolicy saved = admin.policies().apply(policy);
 * }</pre>
 */
public final class RangerAdminClient extends AbstractRangerClient<RangerAdminClient> {
    private final Capabilities capabilities;
    private final ServiceDefsClient serviceDefs;
    private final ServicesClient services;
    private final PoliciesClient policies;
    private final RolesClient roles;
    private final ZonesClient zones;
    private final UsersClient users;
    private final GroupsClient groups;
    private final TagsClient tags;
    private final GdsClient gds;

    private RangerAdminClient(Transport transport) {
        super(transport);
        this.capabilities = new Capabilities(transport);
        this.serviceDefs = new ServiceDefsClient(transport, capabilities);
        this.services = new ServicesClient(transport, capabilities);
        this.policies = new PoliciesClient(transport, capabilities);
        this.roles = new RolesClient(transport, capabilities);
        this.zones = new ZonesClient(transport, capabilities);
        this.users = new UsersClient(transport, capabilities);
        this.groups = new GroupsClient(transport, capabilities, users);
        this.tags = new TagsClient(transport, capabilities);
        this.gds = new GdsClient(transport, capabilities);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Capabilities capabilities() {
        return capabilities;
    }

    public ServiceDefsClient serviceDefs() {
        return serviceDefs;
    }

    public ServicesClient services() {
        return services;
    }

    public PoliciesClient policies() {
        return policies;
    }

    public RolesClient roles() {
        return roles;
    }

    public ZonesClient zones() {
        return zones;
    }

    public UsersClient users() {
        return users;
    }

    public GroupsClient groups() {
        return groups;
    }

    public TagsClient tags() {
        return tags;
    }

    public GdsClient gds() {
        return gds;
    }

    /** Builder; see {@link AbstractClientBuilder} for the shared options. */
    public static final class Builder extends AbstractClientBuilder<RangerAdminClient, Builder> {
        Builder() {
            super(ServerKind.ADMIN);
        }

        @Override
        public RangerAdminClient build() {
            return new RangerAdminClient(transport());
        }
    }
}
