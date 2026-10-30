package io.datadynamics.ranger.client.admin;

import io.datadynamics.ranger.client.core.Capabilities;
import io.datadynamics.ranger.client.core.Paging;
import io.datadynamics.ranger.client.core.http.HttpCall;
import io.datadynamics.ranger.client.core.http.Transport;
import io.datadynamics.ranger.client.model.RangerGroup;
import io.datadynamics.ranger.client.model.RangerUser;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/** Users ({@code /service/xusers}). */
public final class UsersClient extends Resource {
    UsersClient(Transport t, Capabilities c) {
        super(t, c);
    }

    public RangerUser get(long id) {
        return one(HttpCall.get(XUSERS + "/secure/users/" + id), RangerUser.class);
    }

    public RangerUser getByName(String name) {
        return one(HttpCall.get(XUSERS + "/users/userName/" + seg(name)), RangerUser.class);
    }

    public Optional<RangerUser> find(String name) {
        return optional(() -> getByName(name));
    }

    public List<RangerUser> search(String name, Integer startIndex, Integer pageSize) {
        return many(paged(HttpCall.get(XUSERS + "/users").query("name", name), startIndex, pageSize), RangerUser.class, "vXUsers");
    }

    public Stream<RangerUser> stream() {
        return Paging.stream((s, n) -> search(null, s, n), DEFAULT_PAGE);
    }

    public List<RangerGroup> groups(long userId) {
        return many(HttpCall.get(XUSERS + "/" + userId + "/groups"), RangerGroup.class, "vXGroups");
    }

    public RangerUser create(RangerUser user) {
        return one(HttpCall.post(XUSERS + "/secure/users").json(user), RangerUser.class);
    }

    public RangerUser update(RangerUser user) {
        Long id = Objects.requireNonNull(user.getId(), "user id is required for update()");
        return one(HttpCall.put(XUSERS + "/secure/users/" + id).json(user), RangerUser.class);
    }

    public void delete(long id, boolean force) {
        exec(HttpCall.delete(XUSERS + "/users/" + id).query("forceDelete", force));
    }

    public void deleteByName(String name, boolean force) {
        exec(HttpCall.delete(XUSERS + "/users/userName/" + seg(name)).query("forceDelete", force));
    }
}
