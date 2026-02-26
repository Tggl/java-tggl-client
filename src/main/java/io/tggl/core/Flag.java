package io.tggl.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a feature flag configuration.
 */
public final class Flag {
    @Nullable
    private final String slug;

    @NotNull
    private final Variation defaultVariation;

    @NotNull
    private final List<Condition> conditions;

    @JsonCreator
    public Flag(
        @JsonProperty("slug") @Nullable String slug,
        @JsonProperty("defaultVariation") @NotNull Variation defaultVariation,
        @JsonProperty("conditions") @NotNull List<Condition> conditions
    ) {
        this.slug = slug;
        this.defaultVariation = defaultVariation;
        this.conditions = Collections.unmodifiableList(new ArrayList<>(conditions));
    }

    @Nullable
    public String getSlug() {
        return slug;
    }

    @NotNull
    public Variation getDefaultVariation() {
        return defaultVariation;
    }

    @NotNull
    public List<Condition> getConditions() {
        return conditions;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Flag)) return false;
        Flag flag = (Flag) o;
        return Objects.equals(slug, flag.slug)
            && defaultVariation.equals(flag.defaultVariation)
            && conditions.equals(flag.conditions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(slug, defaultVariation, conditions);
    }
}
