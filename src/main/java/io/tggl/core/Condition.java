package io.tggl.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a condition consisting of rules and a resulting variation.
 */
public final class Condition {
    @NotNull
    private final List<Rule> rules;

    @NotNull
    private final Variation variation;

    @JsonCreator
    public Condition(
        @JsonProperty("rules") @NotNull List<Rule> rules,
        @JsonProperty("variation") @NotNull Variation variation
    ) {
        this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
        this.variation = variation;
    }

    @JsonProperty("rules")
    @NotNull
    public List<Rule> rules() {
        return rules;
    }

    @JsonProperty("variation")
    @NotNull
    public Variation variation() {
        return variation;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Condition)) return false;
        Condition condition = (Condition) o;
        return rules.equals(condition.rules) && variation.equals(condition.variation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rules, variation);
    }

    @Override
    public String toString() {
        return "Condition[rules=" + rules + ", variation=" + variation + "]";
    }
}
