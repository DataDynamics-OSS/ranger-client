package io.datadynamics.ranger.client.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Base of every wire model.
 *
 * <p>Models are immutable: create them with {@code builder()}, change them with {@code toBuilder()}.
 * Fields the server sends but this SDK version does not know are kept in {@link #getExtra()} and are
 * written back unchanged, so an update through an older SDK never drops data of a newer server.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
                getterVisibility = JsonAutoDetect.Visibility.NONE,
                isGetterVisibility = JsonAutoDetect.Visibility.NONE,
                setterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class RangerModel {
    @JsonIgnore
    private Map<String, Object> extra;

    protected RangerModel() {
    }

    protected RangerModel(Builder<?, ?> b) {
        this.extra = b.extra == null ? null : new LinkedHashMap<>(b.extra);
    }

    @JsonAnySetter
    void putExtra(String name, Object value) {
        if (extra == null) {
            extra = new LinkedHashMap<>();
        }
        extra.put(name, value);
    }

    /** Server fields not modelled by this SDK version. */
    @JsonAnyGetter
    public Map<String, Object> getExtra() {
        return extra == null ? Collections.emptyMap() : Collections.unmodifiableMap(extra);
    }

    /** Field names whose values are masked in {@link #toString()}. */
    protected Set<String> secretFields() {
        return Collections.emptySet();
    }

    /** The JSON the server would receive. */
    public String toJson() {
        return RangerJson.write(this);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o != null && o.getClass() == getClass() && RangerJson.tree(this).equals(RangerJson.tree(o)));
    }

    @Override
    public int hashCode() {
        return RangerJson.tree(this).hashCode();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + RangerJson.masked(this, secretFields());
    }

    /** Common builder behaviour (unknown-field passthrough). */
    public abstract static class Builder<T extends RangerModel, B extends Builder<T, B>> {
        private Map<String, Object> extra;

        /** Set a field this SDK version does not model; it is sent as-is. */
        @SuppressWarnings("unchecked")
        public B extra(String name, Object value) {
            if (extra == null) {
                extra = new LinkedHashMap<>();
            }
            extra.put(name, value);
            return (B) this;
        }

        void copyExtra(RangerModel m) {
            if (m.extra != null) {
                extra = new LinkedHashMap<>(m.extra);
            }
        }

        public abstract T build();
    }
}
