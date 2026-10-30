package io.datadynamics.ranger.client.pac;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** The changes {@link PolicyAsCode#apply(Plan)} would make. */
public final class Plan {
    private final List<Change> changes;

    Plan(List<Change> changes) {
        this.changes = Collections.unmodifiableList(new ArrayList<>(changes));
    }

    public List<Change> changes() {
        return changes;
    }

    public List<Change> pending() {
        return changes.stream().filter(c -> c.action != Change.Action.NOOP).collect(Collectors.toList());
    }

    public boolean isEmpty() {
        return pending().isEmpty();
    }

    public Map<Change.Action, Integer> summary() {
        Map<Change.Action, Integer> out = new EnumMap<>(Change.Action.class);
        for (Change.Action a : Change.Action.values()) {
            out.put(a, 0);
        }
        changes.forEach(c -> out.merge(c.action, 1, Integer::sum));
        return out;
    }

    /** Terraform-like text: {@code + policy svc/name}, {@code ~ ...} with field diffs, {@code - ...}. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        for (Change c : pending()) {
            char sym = c.action == Change.Action.CREATE ? '+' : c.action == Change.Action.UPDATE ? '~' : '-';
            sb.append(sym).append(' ').append(c.key()).append('\n');
            for (Change.FieldDiff d : c.diff) {
                sb.append("    ").append(d.field).append(": ").append(shorten(String.valueOf(d.before)))
                  .append(" -> ").append(shorten(String.valueOf(d.after))).append('\n');
            }
        }
        Map<Change.Action, Integer> s = summary();
        sb.append("Plan: ").append(s.get(Change.Action.CREATE)).append(" to create, ")
          .append(s.get(Change.Action.UPDATE)).append(" to update, ")
          .append(s.get(Change.Action.DELETE)).append(" to delete, ")
          .append(s.get(Change.Action.NOOP)).append(" unchanged.");
        return sb.toString();
    }

    private static String shorten(String s) {
        return s.length() <= 120 ? s : s.substring(0, 117) + "...";
    }

    @Override
    public String toString() {
        return render();
    }
}
