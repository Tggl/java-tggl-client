package io.tggl.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Represents a flag variation with an active state and optional value.
 */
public final class Variation {
    private final boolean active;

    @Nullable
    private final Object value;

    @JsonCreator
    public Variation(
        @JsonProperty("active") boolean active,
        @JsonProperty("value") @Nullable Object value
    ) {
        this.active = active;
        this.value = value;
    }

    @JsonProperty("active")
    public boolean active() {
        return active;
    }

    @JsonProperty("value")
    @Nullable
    public Object value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Variation)) return false;
        Variation variation = (Variation) o;
        return active == variation.active && Objects.equals(value, variation.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(active, value);
    }

    @Override
    public String toString() {
        return "Variation[active=" + active + ", value=" + value + "]";
    }
}
