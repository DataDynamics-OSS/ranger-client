package io.datadynamics.ranger.client.pac;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.List;

/** One planned change. */
public final class Change {
    public enum Action { CREATE, UPDATE, DELETE, NOOP }

    public enum Kind { POLICY, ROLE }

    /** A changed field: before and after in normalized form. */
    public static final class FieldDiff {
        public final String field;
        public final JsonNode before;
        public final JsonNode after;

        FieldDiff(String field, JsonNode before, JsonNode after) {
            this.field = field;
            this.before = before;
            this.after = after;
        }
    }

    final Action action;
    final Kind kind;
    final String name;
    final String service;
    final String zone;
    final Object desired;
    final Object current;
    final List<FieldDiff> diff;

    Change(Action action, Kind kind, String name, String service, String zone, Object desired, Object current, List<FieldDiff> diff) {
        this.action = action;
        this.kind = kind;
        this.name = name;
        this.service = service;
        this.zone = zone;
        this.desired = desired;
        this.current = current;
        this.diff = diff == null ? Collections.emptyList() : Collections.unmodifiableList(diff);
    }

    public Action action() {
        return action;
    }

    public Kind kind() {
        return kind;
    }

    public String name() {
        return name;
    }

    public String service() {
        return service;
    }

    public String zone() {
        return zone;
    }

    public List<FieldDiff> diff() {
        return diff;
    }

    /** e.g. {@code policy cm_hive/sales_read}. */
    public String key() {
        StringBuilder scope = new StringBuilder();
        if (service != null) {
            scope.append(service);
        }
        if (zone != null) {
            scope.append(scope.length() > 0 ? "/" : "").append(zone);
        }
        return kind.name().toLowerCase() + " " + (scope.length() > 0 ? scope + "/" : "") + name;
    }
}
