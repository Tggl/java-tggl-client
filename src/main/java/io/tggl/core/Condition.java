package io.tggl.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Represents a condition consisting of rules and a resulting variation.
 */
public record Condition(
    @NotNull List<Rule> rules,
    @NotNull Variation variation
) {
    @JsonCreator
    public Condition(
        @JsonProperty("rules") @NotNull List<Rule> rules,
        @JsonProperty("variation") @NotNull Variation variation
    ) {
        this.rules = List.copyOf(rules);
        this.variation = variation;
    }
}
