package io.datadynamics.ranger.client.pac;

import io.datadynamics.ranger.client.model.RangerPolicy;
import io.datadynamics.ranger.client.model.RangerRole;

import java.util.Collections;
import java.util.List;

/** One spec document: policies of a service (and zone) plus roles. See {@link SpecLoader} for the format. */
public final class PolicySet {
    final String service;
    final String zone;
    final boolean prune;
    final String managedLabel;
    final List<RangerPolicy> policies;
    final List<RangerRole> roles;
    final String source;

    PolicySet(String service, String zone, boolean prune, String managedLabel, List<RangerPolicy> policies,
              List<RangerRole> roles, String source) {
        this.service = service;
        this.zone = zone;
        this.prune = prune;
        this.managedLabel = managedLabel;
        this.policies = Collections.unmodifiableList(policies);
        this.roles = Collections.unmodifiableList(roles);
        this.source = source;
    }

    public String service() {
        return service;
    }

    public String zone() {
        return zone;
    }

    public boolean prune() {
        return prune;
    }

    public String managedLabel() {
        return managedLabel;
    }

    public List<RangerPolicy> policies() {
        return policies;
    }

    public List<RangerRole> roles() {
        return roles;
    }

    public String source() {
        return source;
    }
}
