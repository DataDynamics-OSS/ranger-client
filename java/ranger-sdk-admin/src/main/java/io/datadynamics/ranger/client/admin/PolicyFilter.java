package io.datadynamics.ranger.client.admin;

import java.util.LinkedHashMap;
import java.util.Map;

/** Search filter: {@code PolicyFilter.service("cm_hive").user("alice").limit(200)}. */
public final class PolicyFilter {
    final Map<String, Object> params = new LinkedHashMap<>();
    int startIndex;
    int pageSize = Resource.DEFAULT_PAGE;

    private PolicyFilter() {
    }

    public static PolicyFilter all() {
        return new PolicyFilter();
    }

    public static PolicyFilter service(String service) {
        return new PolicyFilter().set("serviceName", service);
    }

    private PolicyFilter set(String k, Object v) {
        if (v != null) {
            params.put(k, v);
        }
        return this;
    }

    public PolicyFilter name(String policyName) {
        return set("policyName", policyName);
    }

    public PolicyFilter zone(String zone) {
        return set("zoneName", zone);
    }

    public PolicyFilter policyType(int type) {
        return set("policyType", type);
    }

    /** Resource filter, e.g. {@code resource("database", "sales")}. */
    public PolicyFilter resource(String type, String value) {
        return set("resource:" + type, value);
    }

    public PolicyFilter user(String user) {
        return set("user", user);
    }

    public PolicyFilter group(String group) {
        return set("group", group);
    }

    public PolicyFilter role(String role) {
        return set("role", role);
    }

    public PolicyFilter label(String label) {
        return set("policyLabelsPartial", label);
    }

    public PolicyFilter enabled(boolean enabled) {
        return set("isEnabled", enabled);
    }

    /** Any other Ranger SearchFilter parameter. */
    public PolicyFilter param(String name, Object value) {
        return set(name, value);
    }

    public PolicyFilter start(int startIndex) {
        this.startIndex = startIndex;
        return this;
    }

    public PolicyFilter limit(int pageSize) {
        this.pageSize = pageSize;
        return this;
    }

    PolicyFilter copyAt(int start) {
        PolicyFilter f = new PolicyFilter();
        f.params.putAll(params);
        f.startIndex = start;
        f.pageSize = pageSize;
        return f;
    }
}
