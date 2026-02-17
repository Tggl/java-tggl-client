package io.tggl.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FlagEvaluatorTest {

    private Flag createFlag(Variation defaultVariation, List<Condition> conditions) {
        return new Flag(null, defaultVariation, conditions);
    }

    private Condition createCondition(Variation variation, List<Rule> rules) {
        return new Condition(rules, variation);
    }

    private Variation activeVariation(Object value) {
        return new Variation(true, value);
    }

    private Variation inactiveVariation() {
        return new Variation(false, null);
    }

    @Nested
    @DisplayName("EMPTY operator")
    class EmptyOperator {
        @Test
        void shouldReturnTrueForNullValue() {
            Rule rule = new Rule("key", "EMPTY", false, null, null, null, null, null, null, null, null);
            assertTrue(FlagEvaluator.evalRule(rule, null));
        }

        @Test
        void shouldReturnTrueForEmptyString() {
            Rule rule = new Rule("key", "EMPTY", false, null, null, null, null, null, null, null, null);
            assertTrue(FlagEvaluator.evalRule(rule, ""));
        }

        @Test
        void shouldReturnFalseForNonEmptyString() {
            Rule rule = new Rule("key", "EMPTY", false, null, null, null, null, null, null, null, null);
            assertFalse(FlagEvaluator.evalRule(rule, "hello"));
        }

        @Test
        void shouldHandleNegation() {
            Rule rule = new Rule("key", "EMPTY", true, null, null, null, null, null, null, null, null);
            assertFalse(FlagEvaluator.evalRule(rule, null));
            assertTrue(FlagEvaluator.evalRule(rule, "hello"));
        }
    }

    @Nested
    @DisplayName("TRUE operator")
    class TrueOperator {
        @Test
        void shouldReturnTrueForTrueValue() {
            Rule rule = new Rule("key", "TRUE", false, null, null, null, null, null, null, null, null);
            assertTrue(FlagEvaluator.evalRule(rule, true));
        }

        @Test
        void shouldReturnFalseForFalseValue() {
            Rule rule = new Rule("key", "TRUE", false, null, null, null, null, null, null, null, null);
            assertFalse(FlagEvaluator.evalRule(rule, false));
        }

        @Test
        void shouldHandleNegation() {
            Rule rule = new Rule("key", "TRUE", true, null, null, null, null, null, null, null, null);
            assertFalse(FlagEvaluator.evalRule(rule, true));
            assertTrue(FlagEvaluator.evalRule(rule, false));
        }
    }

    @Nested
    @DisplayName("STR_EQUAL operator")
    class StrEqualOperator {
        @Test
        void shouldMatchExactString() {
            Rule rule = new Rule("key", "STR_EQUAL", false, null, null, null, List.of("hello", "world"), null, null, null, null);
            assertTrue(FlagEvaluator.evalRule(rule, "hello"));
            assertTrue(FlagEvaluator.evalRule(rule, "world"));
            assertFalse(FlagEvaluator.evalRule(rule, "HELLO"));
            assertFalse(FlagEvaluator.evalRule(rule, "other"));
        }

        @Test
        void shouldReturnFalseForNonString() {
            Rule rule = new Rule("key", "STR_EQUAL", false, null, null, null, List.of("123"), null, null, null, null);
            assertFalse(FlagEvaluator.evalRule(rule, 123));
        }
    }

    @Nested
    @DisplayName("STR_EQUAL_SOFT operator")
    class StrEqualSoftOperator {
        @Test
        void shouldMatchCaseInsensitive() {
            Rule rule = new Rule("key", "STR_EQUAL_SOFT", false, null, null, null, List.of("hello"), null, null, null, null);
            assertTrue(FlagEvaluator.evalRule(rule, "hello"));
            assertTrue(FlagEvaluator.evalRule(rule, "HELLO"));
            assertTrue(FlagEvaluator.evalRule(rule, "HeLLo"));
        }

        @Test
        void shouldMatchNumbersAsStrings() {
            Rule rule = new Rule("key", "STR_EQUAL_SOFT", false, null, null, null, List.of("123"), null, null, null, null);
            assertTrue(FlagEvaluator.evalRule(rule, 123));
            assertTrue(FlagEvaluator.evalRule(rule, "123"));
        }
    }

    @Nested
    @DisplayName("STR_CONTAINS operator")
    class StrContainsOperator {
        @Test
        void shouldMatchSubstring() {
            Rule rule = new Rule("key", "STR_CONTAINS", false, null, null, null, List.of("ell"), null, null, null, null);
            assertTrue(FlagEvaluator.evalRule(rule, "hello"));
            assertFalse(FlagEvaluator.evalRule(rule, "world"));
        }
    }

    @Nested
    @DisplayName("STR_STARTS_WITH operator")
    class StrStartsWithOperator {
        @Test
        void shouldMatchPrefix() {
            Rule rule = new Rule("key", "STR_STARTS_WITH", false, null, null, null, List.of("hel"), null, null, null, null);
            assertTrue(FlagEvaluator.evalRule(rule, "hello"));
            assertFalse(FlagEvaluator.evalRule(rule, "world"));
        }
    }

    @Nested
    @DisplayName("STR_ENDS_WITH operator")
    class StrEndsWithOperator {
        @Test
        void shouldMatchSuffix() {
            Rule rule = new Rule("key", "STR_ENDS_WITH", false, null, null, null, List.of("lo"), null, null, null, null);
            assertTrue(FlagEvaluator.evalRule(rule, "hello"));
            assertFalse(FlagEvaluator.evalRule(rule, "world"));
        }
    }

    @Nested
    @DisplayName("REGEXP operator")
    class RegexpOperator {
        @Test
        void shouldMatchRegularExpression() {
            Rule rule = new Rule("key", "REGEXP", false, null, null, null, null, "^h.*o$", null, null, null);
            assertTrue(FlagEvaluator.evalRule(rule, "hello"));
            assertFalse(FlagEvaluator.evalRule(rule, "world"));
        }

        @Test
        void shouldHandleInvalidRegex() {
            Rule rule = new Rule("key", "REGEXP", false, null, null, null, null, "[invalid", null, null, null);
            assertFalse(FlagEvaluator.evalRule(rule, "test"));
        }
    }

    @Nested
    @DisplayName("Numeric operators")
    class NumericOperators {
        @Test
        void shouldEvaluateEquals() {
            Rule rule = new Rule("key", "EQ", false, null, null, null, null, 42, null, null, null);
            assertTrue(FlagEvaluator.evalRule(rule, 42));
            assertTrue(FlagEvaluator.evalRule(rule, 42.0));
            assertFalse(FlagEvaluator.evalRule(rule, 41));
        }

        @Test
        void shouldEvaluateLessThan() {
            Rule rule = new Rule("key", "LT", false, null, null, null, null, 50, null, null, null);
            assertTrue(FlagEvaluator.evalRule(rule, 40));
            assertFalse(FlagEvaluator.evalRule(rule, 50));
            assertFalse(FlagEvaluator.evalRule(rule, 60));
        }

        @Test
        void shouldEvaluateGreaterThan() {
            Rule rule = new Rule("key", "GT", false, null, null, null, null, 50, null, null, null);
            assertTrue(FlagEvaluator.evalRule(rule, 60));
            assertFalse(FlagEvaluator.evalRule(rule, 50));
            assertFalse(FlagEvaluator.evalRule(rule, 40));
        }
    }

    @Nested
    @DisplayName("ARR_OVERLAP operator")
    class ArrOverlapOperator {
        @Test
        void shouldMatchOverlappingArrays() {
            Rule rule = new Rule("key", "ARR_OVERLAP", false, null, null, null, List.of("a", "b", "c"), null, null, null, null);
            assertTrue(FlagEvaluator.evalRule(rule, List.of("b", "d")));
            assertTrue(FlagEvaluator.evalRule(rule, List.of("a")));
            assertFalse(FlagEvaluator.evalRule(rule, List.of("x", "y")));
        }
    }

    @Nested
    @DisplayName("Semver operators")
    class SemverOperators {
        @Test
        void shouldMatchSemverEquals() {
            Rule rule = new Rule("key", "SEMVER_EQ", false, null, null, null, null, null, null, null, List.of(1, 2, 3));
            assertTrue(FlagEvaluator.evalRule(rule, "1.2.3"));
            assertFalse(FlagEvaluator.evalRule(rule, "1.2.4"));
            assertFalse(FlagEvaluator.evalRule(rule, "1.2"));
        }

        @Test
        void shouldMatchSemverGte() {
            Rule rule = new Rule("key", "SEMVER_GTE", false, null, null, null, null, null, null, null, List.of(1, 2, 0));
            assertTrue(FlagEvaluator.evalRule(rule, "1.2.0"));
            assertTrue(FlagEvaluator.evalRule(rule, "1.2.5"));
            assertTrue(FlagEvaluator.evalRule(rule, "1.3.0"));
            assertTrue(FlagEvaluator.evalRule(rule, "2.0.0"));
            assertFalse(FlagEvaluator.evalRule(rule, "1.1.9"));
        }

        @Test
        void shouldMatchSemverLte() {
            Rule rule = new Rule("key", "SEMVER_LTE", false, null, null, null, null, null, null, null, List.of(1, 2, 0));
            assertTrue(FlagEvaluator.evalRule(rule, "1.2.0"));
            assertTrue(FlagEvaluator.evalRule(rule, "1.1.9"));
            assertTrue(FlagEvaluator.evalRule(rule, "0.9.9"));
            assertFalse(FlagEvaluator.evalRule(rule, "1.2.1"));
        }
    }

    @Nested
    @DisplayName("Date operators")
    class DateOperators {
        @Test
        void shouldMatchDateAfterWithTimestamp() {
            // Timestamp for 2024-01-01 00:00:00 UTC
            Rule rule = new Rule("key", "DATE_AFTER", false, null, null, null, null, null, 1704067200000L, "2024-01-01T00:00:00", null);
            assertTrue(FlagEvaluator.evalRule(rule, 1704067200000L)); // Equal
            assertTrue(FlagEvaluator.evalRule(rule, 1704153600000L)); // After
            assertFalse(FlagEvaluator.evalRule(rule, 1703980800000L)); // Before
        }

        @Test
        void shouldMatchDateAfterWithIsoString() {
            Rule rule = new Rule("key", "DATE_AFTER", false, null, null, null, null, null, 1704067200000L, "2024-01-01T00:00:00", null);
            assertTrue(FlagEvaluator.evalRule(rule, "2024-01-01T00:00:00"));
            assertTrue(FlagEvaluator.evalRule(rule, "2024-01-02"));
            assertFalse(FlagEvaluator.evalRule(rule, "2023-12-31"));
        }

        @Test
        void shouldHandleSecondsTimestamp() {
            Rule rule = new Rule("key", "DATE_AFTER", false, null, null, null, null, null, 1704067200000L, "2024-01-01T00:00:00", null);
            // Seconds instead of milliseconds (before year 1990 threshold)
            assertTrue(FlagEvaluator.evalRule(rule, 1704153600L)); // After (in seconds)
        }
    }

    @Nested
    @DisplayName("PERCENTAGE operator")
    class PercentageOperator {
        @Test
        void shouldBeDeterministic() {
            Rule rule = new Rule("key", "PERCENTAGE", false, 0.0, 0.5, 12345, null, null, null, null, null);
            
            // Same input should always give same result
            boolean result1 = FlagEvaluator.evalRule(rule, "user123");
            boolean result2 = FlagEvaluator.evalRule(rule, "user123");
            assertEquals(result1, result2);
        }

        @Test
        void shouldDistributeAcrossRange() {
            Rule rule = new Rule("key", "PERCENTAGE", false, 0.0, 1.0, 12345, null, null, null, null, null);
            
            // 100% range should always match
            assertTrue(FlagEvaluator.evalRule(rule, "anyUser"));
        }

        @Test
        void shouldHandleZeroRange() {
            Rule rule = new Rule("key", "PERCENTAGE", false, 0.0, 0.0, 12345, null, null, null, null, null);
            
            // 0% range should never match
            assertFalse(FlagEvaluator.evalRule(rule, "anyUser"));
        }
    }

    @Nested
    @DisplayName("Flag evaluation")
    class FlagEvaluation {
        @Test
        void shouldReturnDefaultWhenNoConditionsMatch() {
            Flag flag = createFlag(
                activeVariation("default"),
                Collections.emptyList()
            );
            
            assertEquals("default", FlagEvaluator.evalFlag(Map.of(), flag));
        }

        @Test
        void shouldReturnFirstMatchingCondition() {
            Flag flag = createFlag(
                activeVariation("default"),
                List.of(
                    createCondition(
                        activeVariation("premium"),
                        List.of(new Rule("plan", "STR_EQUAL", false, null, null, null, List.of("premium"), null, null, null, null))
                    ),
                    createCondition(
                        activeVariation("basic"),
                        List.of(new Rule("plan", "STR_EQUAL", false, null, null, null, List.of("basic"), null, null, null, null))
                    )
                )
            );
            
            assertEquals("premium", FlagEvaluator.evalFlag(Map.of("plan", "premium"), flag));
            assertEquals("basic", FlagEvaluator.evalFlag(Map.of("plan", "basic"), flag));
            assertEquals("default", FlagEvaluator.evalFlag(Map.of("plan", "free"), flag));
        }

        @Test
        void shouldReturnNullForInactiveVariation() {
            Flag flag = createFlag(
                inactiveVariation(),
                Collections.emptyList()
            );
            
            assertNull(FlagEvaluator.evalFlag(Map.of(), flag));
        }

        @Test
        void shouldEvaluateAllRulesInCondition() {
            Flag flag = createFlag(
                activeVariation("default"),
                List.of(
                    createCondition(
                        activeVariation("match"),
                        List.of(
                            new Rule("country", "STR_EQUAL", false, null, null, null, List.of("US"), null, null, null, null),
                            new Rule("age", "GT", false, null, null, null, null, 18, null, null, null)
                        )
                    )
                )
            );
            
            // Both rules match
            assertEquals("match", FlagEvaluator.evalFlag(Map.of("country", "US", "age", 25), flag));
            
            // Only one rule matches
            assertEquals("default", FlagEvaluator.evalFlag(Map.of("country", "US", "age", 15), flag));
            assertEquals("default", FlagEvaluator.evalFlag(Map.of("country", "CA", "age", 25), flag));
        }
    }
}
