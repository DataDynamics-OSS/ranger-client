package io.datadynamics.ranger.client.admin;

import io.datadynamics.ranger.client.core.Capabilities;
import io.datadynamics.ranger.client.core.Paging;
import io.datadynamics.ranger.client.core.RangerException.ErrorContext;
import io.datadynamics.ranger.client.core.RangerNotFoundException;
import io.datadynamics.ranger.client.core.RangerValidationException;
import io.datadynamics.ranger.client.core.http.HttpCall;
import io.datadynamics.ranger.client.core.http.Transport;
import io.datadynamics.ranger.client.model.GrantRevokeRoleRequest;
import io.datadynamics.ranger.client.model.RangerRole;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/** {@code /service/public/v2/api/roles} */
public final class RolesClient extends Resource {
    RolesClient(Transport t, Capabilities c) {
        super(t, c);
    }

    public RangerRole get(long id) {
        return one(HttpCall.get(API + "/roles/" + id), RangerRole.class);
    }

    public RangerRole getByName(String name) {
        try {
            return one(HttpCall.get(API + "/roles/name/" + seg(name)), RangerRole.class);
        } catch (RangerValidationException e) {
            // Ranger 2.x answers an unknown role name with 400 "Role with name: x does not exist".
            String msg = e.getServerMessage();
            if (msg == null || !msg.contains("does not exist")) {
                throw e;
            }
            throw new RangerNotFoundException("Not found: " + msg, new ErrorContext(e.getStatus(), e.getMethod(), e.getPath(),
                    e.getServer(), e.getRequestId(), e.getBody(), msg), e);
        }
    }

    public Optional<RangerRole> find(String name) {
        return optional(() -> getByName(name));
    }

    public List<RangerRole> search(String name, Integer startIndex, Integer pageSize) {
        return many(paged(HttpCall.get(API + "/roles").query("roleName", name), startIndex, pageSize), RangerRole.class);
    }

    public Stream<RangerRole> stream() {
        return Paging.stream((s, n) -> search(null, s, n), DEFAULT_PAGE);
    }

    public List<String> names() {
        return many(HttpCall.get(API + "/roles/names"), String.class);
    }

    /** Role names granted to {@code user}. */
    public List<String> forUser(String user) {
        return many(HttpCall.get(API + "/roles/user/" + seg(user)), String.class);
    }

    public RangerRole create(RangerRole role) {
        return create(role, null);
    }

    public RangerRole create(RangerRole role, String service) {
        return one(HttpCall.post(API + "/roles").query("serviceName", service).json(role), RangerRole.class);
    }

    public RangerRole update(RangerRole role) {
        Long id = Objects.requireNonNull(role.getId(), "role id is required for update(); use apply()");
        return one(HttpCall.put(API + "/roles/" + id).json(role), RangerRole.class);
    }

    /** Create or replace the role with the same name. */
    public RangerRole apply(RangerRole role) {
        Optional<RangerRole> current = find(role.getName());
        if (current.isEmpty()) {
            return create(role);
        }
        return update(role.toBuilder().id(current.get().getId()).guid(current.get().getGuid()).build());
    }

    public void delete(long id) {
        exec(HttpCall.delete(API + "/roles/" + id));
    }

    public void deleteByName(String name, String execUser, String service) {
        exec(HttpCall.delete(API + "/roles/name/" + seg(name)).query("execUser", execUser).query("serviceName", service));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> grant(String service, GrantRevokeRoleRequest request) {
        return one(HttpCall.put(API + "/roles/grant/" + seg(service)).json(request), Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> revoke(String service, GrantRevokeRoleRequest request) {
        return one(HttpCall.put(API + "/roles/revoke/" + seg(service)).json(request), Map.class);
    }
}
