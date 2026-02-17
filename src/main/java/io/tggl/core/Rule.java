package io.tggl.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Represents a rule for flag evaluation.
 * Rules can have different properties depending on the operator type.
 */
public class Rule {
    @NotNull
    private final String key;
    
    @Nullable
    private final Operator operator;
    
    private final boolean negate;
    
    // For percentage-based rules
    @Nullable
    private final Double rangeStart;
    
    @Nullable
    private final Double rangeEnd;
    
    @Nullable
    private final Integer seed;
    
    // For string/array comparison rules
    @Nullable
    private final List<String> values;
    
    // For single value rules (regexp, str_before, str_after)
    @Nullable
    private final String value;
    
    // For numeric comparison rules
    @Nullable
    private final Double numericValue;
    
    // For date rules
    @Nullable
    private final Long timestamp;
    
    @Nullable
    private final String iso;
    
    // For semver rules
    @Nullable
    private final List<Integer> version;

    @JsonCreator
    public Rule(
        @JsonProperty("key") @NotNull String key,
        @JsonProperty("operator") @NotNull String operator,
        @JsonProperty("negate") @Nullable Boolean negate,
        @JsonProperty("rangeStart") @Nullable Double rangeStart,
        @JsonProperty("rangeEnd") @Nullable Double rangeEnd,
        @JsonProperty("seed") @Nullable Integer seed,
        @JsonProperty("values") @Nullable List<String> values,
        @JsonProperty("value") @Nullable Object value,
        @JsonProperty("timestamp") @Nullable Long timestamp,
        @JsonProperty("iso") @Nullable String iso,
        @JsonProperty("version") @Nullable List<Integer> version
    ) {
        this.key = key;
        this.operator = Operator.fromValue(operator);
        this.negate = negate != null && negate;
        this.rangeStart = rangeStart;
        this.rangeEnd = rangeEnd;
        this.seed = seed;
        this.values = values != null ? List.copyOf(values) : null;
        this.timestamp = timestamp;
        this.iso = iso;
        this.version = version != null ? List.copyOf(version) : null;
        
        // Handle the 'value' field which can be string or number
        if (value instanceof String) {
            this.value = (String) value;
            this.numericValue = null;
        } else if (value instanceof Number) {
            this.numericValue = ((Number) value).doubleValue();
            this.value = null;
        } else {
            this.value = null;
            this.numericValue = null;
        }
    }

    @NotNull
    public String getKey() {
        return key;
    }

    @Nullable
    public Operator getOperator() {
        return operator;
    }

    public boolean isNegate() {
        return negate;
    }

    @Nullable
    public Double getRangeStart() {
        return rangeStart;
    }

    @Nullable
    public Double getRangeEnd() {
        return rangeEnd;
    }

    @Nullable
    public Integer getSeed() {
        return seed;
    }

    @Nullable
    public List<String> getValues() {
        return values;
    }

    @Nullable
    public String getValue() {
        return value;
    }

    @Nullable
    public Double getNumericValue() {
        return numericValue;
    }

    @Nullable
    public Long getTimestamp() {
        return timestamp;
    }

    @Nullable
    public String getIso() {
        return iso;
    }

    @Nullable
    public List<Integer> getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Rule rule)) return false;
        return negate == rule.negate
            && key.equals(rule.key)
            && operator == rule.operator
            && Objects.equals(rangeStart, rule.rangeStart)
            && Objects.equals(rangeEnd, rule.rangeEnd)
            && Objects.equals(seed, rule.seed)
            && Objects.equals(values, rule.values)
            && Objects.equals(value, rule.value)
            && Objects.equals(numericValue, rule.numericValue)
            && Objects.equals(timestamp, rule.timestamp)
            && Objects.equals(iso, rule.iso)
            && Objects.equals(version, rule.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, operator, negate, rangeStart, rangeEnd, seed,
            values, value, numericValue, timestamp, iso, version);
    }
}
