package io.datadynamics.ranger.client.admin;

import io.datadynamics.ranger.client.core.Capabilities;
import io.datadynamics.ranger.client.core.Paging;
import io.datadynamics.ranger.client.core.http.HttpCall;
import io.datadynamics.ranger.client.core.http.Transport;
import io.datadynamics.ranger.client.model.RangerGroup;
import io.datadynamics.ranger.client.model.RangerGroupUser;
import io.datadynamics.ranger.client.model.RangerUser;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/** Groups and memberships ({@code /service/xusers}). */
public final class GroupsClient extends Resource {
    private final UsersClient users;

    GroupsClient(Transport t, Capabilities c, UsersClient users) {
        super(t, c);
        this.users = users;
    }

    public RangerGroup get(long id) {
        return one(HttpCall.get(XUSERS + "/secure/groups/" + id), RangerGroup.class);
    }

    public RangerGroup getByName(String name) {
        return one(HttpCall.get(XUSERS + "/groups/groupName/" + seg(name)), RangerGroup.class);
    }

    public Optional<RangerGroup> find(String name) {
        return optional(() -> getByName(name));
    }

    public List<RangerGroup> search(String name, Integer startIndex, Integer pageSize) {
        return many(paged(HttpCall.get(XUSERS + "/groups").query("name", name), startIndex, pageSize), RangerGroup.class, "vXGroups");
    }

    public Stream<RangerGroup> stream() {
        return Paging.stream((s, n) -> search(null, s, n), DEFAULT_PAGE);
    }

    public List<RangerUser> users(long groupId) {
        return many(HttpCall.get(XUSERS + "/" + groupId + "/users"), RangerUser.class, "vXUsers");
    }

    public RangerGroup create(RangerGroup group) {
        return one(HttpCall.post(XUSERS + "/secure/groups").json(group), RangerGroup.class);
    }

    public RangerGroup update(RangerGroup group) {
        Long id = Objects.requireNonNull(group.getId(), "group id is required for update()");
        return one(HttpCall.put(XUSERS + "/secure/groups/" + id).json(group), RangerGroup.class);
    }

    public void delete(long id, boolean force) {
        exec(HttpCall.delete(XUSERS + "/groups/" + id).query("forceDelete", force));
    }

    public void deleteByName(String name, boolean force) {
        exec(HttpCall.delete(XUSERS + "/groups/groupName/" + seg(name)).query("forceDelete", force));
    }

    /** Add a user to a group (both by name). */
    public RangerGroupUser addUser(String group, String user) {
        RangerGroup g = getByName(group);
        RangerUser u = users.getByName(user);
        RangerGroupUser body = RangerGroupUser.builder().name(g.getName()).parentGroupId(g.getId()).userId(u.getId()).build();
        return one(HttpCall.post(XUSERS + "/groupusers").json(body), RangerGroupUser.class);
    }

    public void removeUser(String group, String user) {
        exec(HttpCall.delete(XUSERS + "/group/" + seg(group) + "/user/" + seg(user)));
    }
}
