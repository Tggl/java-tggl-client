package io.tggl.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a flag variation with an active state and optional value.
 */
public record Variation(
    boolean active,
    @Nullable Object value
) {
    @JsonCreator
    public Variation(
        @JsonProperty("active") boolean active,
        @JsonProperty("value") @Nullable Object value
    ) {
        this.active = active;
        this.value = value;
    }
}
