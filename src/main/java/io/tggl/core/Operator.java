package io.tggl.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Operators used for flag rule evaluation.
 */
public enum Operator {
    EMPTY("EMPTY"),
    TRUE("TRUE"),
    STR_EQUAL("STR_EQUAL"),
    STR_EQUAL_SOFT("STR_EQUAL_SOFT"),
    STR_STARTS_WITH("STR_STARTS_WITH"),
    STR_ENDS_WITH("STR_ENDS_WITH"),
    STR_CONTAINS("STR_CONTAINS"),
    PERCENTAGE("PERCENTAGE"),
    ARR_OVERLAP("ARR_OVERLAP"),
    REGEXP("REGEXP"),
    STR_BEFORE("STR_BEFORE"),
    STR_AFTER("STR_AFTER"),
    EQ("EQ"),
    LT("LT"),
    GT("GT"),
    DATE_AFTER("DATE_AFTER"),
    DATE_BEFORE("DATE_BEFORE"),
    SEMVER_EQ("SEMVER_EQ"),
    SEMVER_GTE("SEMVER_GTE"),
    SEMVER_LTE("SEMVER_LTE");

    private static final Map<String, Operator> BY_VALUE = Collections.unmodifiableMap(
        Stream.of(values()).collect(Collectors.toMap(Operator::getValue, Function.identity())));

    private final String value;

    Operator(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * Returns the Operator for the given string value, or {@code null} if the value
     * is not a recognized operator. This allows the SDK to gracefully handle new
     * operators introduced by the API without crashing.
     *
     * @param value the operator string from the API
     * @return the matching Operator, or null if unknown
     */
    @JsonCreator
    @Nullable
    public static Operator fromValue(String value) {
        return BY_VALUE.get(value);
    }
}
