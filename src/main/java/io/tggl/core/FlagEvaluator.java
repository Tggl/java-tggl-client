package io.tggl.core;

import net.jpountz.xxhash.XXHash32;
import net.jpountz.xxhash.XXHashFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Evaluates feature flags against a given context.
 * This is the core evaluation engine that processes flag conditions and rules.
 */
public final class FlagEvaluator {

    private static final Logger logger = LoggerFactory.getLogger(FlagEvaluator.class);
    private static final long EPOCH_THRESHOLD = 631152000000L; // Year 1990 in milliseconds
    private static final XXHash32 XXHASH32 = XXHashFactory.fastestInstance().hash32();
    private static final int MAX_PATTERN_CACHE_SIZE = 256;
    private static final ConcurrentHashMap<String, Pattern> patternCache = new ConcurrentHashMap<>();

    private FlagEvaluator() {
        // Utility class
    }

    /**
     * Evaluates a flag against the given context.
     *
     * @param context The context map containing user/session attributes
     * @param flag    The flag configuration to evaluate
     * @return The flag value if active, or null if inactive
     */
    @Nullable
    public static Object evalFlag(@NotNull Map<String, Object> context, @NotNull Flag flag) {
        for (Condition condition : flag.getConditions()) {
            if (evalRules(context, condition.rules())) {
                return condition.variation().active() ? condition.variation().value() : null;
            }
        }
        
        return flag.getDefaultVariation().active() ? flag.getDefaultVariation().value() : null;
    }

    /**
     * Evaluates all rules in a condition.
     * All rules must pass for the condition to match (AND logic).
     */
    public static boolean evalRules(@NotNull Map<String, Object> context, @NotNull List<Rule> rules) {
        for (Rule rule : rules) {
            if (!evalRule(rule, context.get(rule.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Evaluates a single rule against a value.
     */
    public static boolean evalRule(@NotNull Rule rule, @Nullable Object value) {
        Operator op = rule.getOperator();

        // Unknown operator: skip this rule gracefully
        if (op == null) {
            logger.warn("Skipping rule with unknown operator for key '{}'. SDK may need an update.", rule.getKey());
            return false;
        }

        // EMPTY operator - checks if value is null, undefined, or empty string
        if (op == Operator.EMPTY) {
            boolean isEmpty = value == null || "".equals(value);
            return isEmpty != rule.isNegate();
        }

        // For all other operators, null/undefined values return false
        if (value == null) {
            return false;
        }

        switch (op) {
            case EMPTY: throw new AssertionError("unreachable");
            case TRUE: return evalTrue(rule, value);
            case STR_EQUAL: return evalStrEqual(rule, value);
            case STR_EQUAL_SOFT: return evalStrEqualSoft(rule, value);
            case STR_CONTAINS: return evalStrContains(rule, value);
            case STR_STARTS_WITH: return evalStrStartsWith(rule, value);
            case STR_ENDS_WITH: return evalStrEndsWith(rule, value);
            case STR_AFTER: return evalStrAfter(rule, value);
            case STR_BEFORE: return evalStrBefore(rule, value);
            case REGEXP: return evalRegexp(rule, value);
            case EQ: return evalEq(rule, value);
            case LT: return evalLt(rule, value);
            case GT: return evalGt(rule, value);
            case ARR_OVERLAP: return evalArrOverlap(rule, value);
            case DATE_AFTER: return evalDateAfter(rule, value);
            case DATE_BEFORE: return evalDateBefore(rule, value);
            case SEMVER_EQ: return evalSemverEq(rule, value);
            case SEMVER_GTE: return evalSemverGte(rule, value);
            case SEMVER_LTE: return evalSemverLte(rule, value);
            case PERCENTAGE: return evalPercentage(rule, value);
            default: return false;
        }
    }

    private static boolean evalTrue(@NotNull Rule rule, @NotNull Object value) {
        boolean boolValue;
        if (value instanceof Boolean) {
            boolValue = (Boolean) value;
        } else {
            return false;
        }
        return boolValue == !rule.isNegate();
    }

    private static boolean evalStrEqual(@NotNull Rule rule, @NotNull Object value) {
        if (!(value instanceof String)) {
            return false;
        }
        List<String> values = rule.getValues();
        if (values == null) {
            return false;
        }
        boolean matches = values.contains(value);
        return matches != rule.isNegate();
    }

    private static boolean evalStrEqualSoft(@NotNull Rule rule, @NotNull Object value) {
        if (!(value instanceof String || value instanceof Number)) {
            return false;
        }
        List<String> values = rule.getValues();
        if (values == null) {
            return false;
        }
        String strValue = String.valueOf(value).toLowerCase();
        boolean matches = values.contains(strValue);
        return matches != rule.isNegate();
    }

    private static boolean evalStrContains(@NotNull Rule rule, @NotNull Object value) {
        if (!(value instanceof String)) {
            return false;
        }
        String strValue = (String) value;
        List<String> values = rule.getValues();
        if (values == null) {
            return false;
        }
        boolean matches = values.stream().anyMatch(strValue::contains);
        return matches != rule.isNegate();
    }

    private static boolean evalStrStartsWith(@NotNull Rule rule, @NotNull Object value) {
        if (!(value instanceof String)) {
            return false;
        }
        String strValue = (String) value;
        List<String> values = rule.getValues();
        if (values == null) {
            return false;
        }
        boolean matches = values.stream().anyMatch(strValue::startsWith);
        return matches != rule.isNegate();
    }

    private static boolean evalStrEndsWith(@NotNull Rule rule, @NotNull Object value) {
        if (!(value instanceof String)) {
            return false;
        }
        String strValue = (String) value;
        List<String> values = rule.getValues();
        if (values == null) {
            return false;
        }
        boolean matches = values.stream().anyMatch(strValue::endsWith);
        return matches != rule.isNegate();
    }

    private static boolean evalStrAfter(@NotNull Rule rule, @NotNull Object value) {
        if (!(value instanceof String)) {
            return false;
        }
        String strValue = (String) value;
        String ruleValue = rule.getValue();
        if (ruleValue == null) {
            return false;
        }
        boolean matches = strValue.compareTo(ruleValue) >= 0;
        return matches != rule.isNegate();
    }

    private static boolean evalStrBefore(@NotNull Rule rule, @NotNull Object value) {
        if (!(value instanceof String)) {
            return false;
        }
        String strValue = (String) value;
        String ruleValue = rule.getValue();
        if (ruleValue == null) {
            return false;
        }
        boolean matches = strValue.compareTo(ruleValue) <= 0;
        return matches != rule.isNegate();
    }

    private static boolean evalRegexp(@NotNull Rule rule, @NotNull Object value) {
        if (!(value instanceof String)) {
            return false;
        }
        String strValue = (String) value;
        String patternStr = rule.getValue();
        if (patternStr == null) {
            return false;
        }
        try {
            if (patternCache.size() >= MAX_PATTERN_CACHE_SIZE) {
                patternCache.clear();
            }
            Pattern compiled = patternCache.computeIfAbsent(patternStr, Pattern::compile);
            boolean matches = compiled.matcher(strValue).find();
            return matches != rule.isNegate();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    private static boolean evalEq(@NotNull Rule rule, @NotNull Object value) {
        if (!(value instanceof Number)) {
            return false;
        }
        Number numValue = (Number) value;
        Double ruleValue = rule.getNumericValue();
        if (ruleValue == null) {
            return false;
        }
        boolean matches = Double.compare(numValue.doubleValue(), ruleValue) == 0;
        return matches != rule.isNegate();
    }

    private static boolean evalLt(@NotNull Rule rule, @NotNull Object value) {
        if (!(value instanceof Number)) {
            return false;
        }
        Number numValue = (Number) value;
        Double ruleValue = rule.getNumericValue();
        if (ruleValue == null) {
            return false;
        }
        boolean matches = numValue.doubleValue() < ruleValue;
        return matches != rule.isNegate();
    }

    private static boolean evalGt(@NotNull Rule rule, @NotNull Object value) {
        if (!(value instanceof Number)) {
            return false;
        }
        Number numValue = (Number) value;
        Double ruleValue = rule.getNumericValue();
        if (ruleValue == null) {
            return false;
        }
        boolean matches = numValue.doubleValue() > ruleValue;
        return matches != rule.isNegate();
    }

    private static boolean evalArrOverlap(@NotNull Rule rule, @NotNull Object value) {
        if (!(value instanceof Collection)) {
            return false;
        }
        Collection<?> collection = (Collection<?>) value;
        List<String> values = rule.getValues();
        if (values == null) {
            return false;
        }
        boolean matches = collection.stream().anyMatch(item -> values.contains(String.valueOf(item)));
        return matches != rule.isNegate();
    }

    private static boolean evalDateAfter(@NotNull Rule rule, @NotNull Object value) {
        Long timestamp = rule.getTimestamp();
        String iso = rule.getIso();
        
        if (value instanceof String) {
            String strValue = (String) value;
            if (iso == null) {
                return false;
            }
            String template = "2000-01-01T23:59:59";
            String paddedValue = strValue.length() < template.length() 
                ? strValue + template.substring(strValue.length())
                : strValue.substring(0, template.length());
            boolean matches = iso.compareTo(paddedValue) <= 0;
            return matches != rule.isNegate();
        }
        
        if (value instanceof Number) {
            Number numValue = (Number) value;
            if (timestamp == null) {
                return false;
            }
            long val = numValue.longValue();
            if (val < EPOCH_THRESHOLD) {
                val = val * 1000;
            }
            boolean matches = val >= timestamp;
            return matches != rule.isNegate();
        }
        
        return false;
    }

    private static boolean evalDateBefore(@NotNull Rule rule, @NotNull Object value) {
        Long timestamp = rule.getTimestamp();
        String iso = rule.getIso();
        
        if (value instanceof String) {
            String strValue = (String) value;
            if (iso == null) {
                return false;
            }
            String template = "2000-01-01T00:00:00";
            String paddedValue = strValue.length() < template.length()
                ? strValue + template.substring(strValue.length())
                : strValue.substring(0, template.length());
            boolean matches = iso.compareTo(paddedValue) >= 0;
            return matches != rule.isNegate();
        }
        
        if (value instanceof Number) {
            Number numValue = (Number) value;
            if (timestamp == null) {
                return false;
            }
            long val = numValue.longValue();
            if (val < EPOCH_THRESHOLD) {
                val = val * 1000;
            }
            boolean matches = val <= timestamp;
            return matches != rule.isNegate();
        }
        
        return false;
    }

    private static boolean evalSemverEq(@NotNull Rule rule, @NotNull Object value) {
        if (!(value instanceof String)) {
            return false;
        }
        String strValue = (String) value;
        List<Integer> ruleVersion = rule.getVersion();
        if (ruleVersion == null || ruleVersion.isEmpty()) {
            return false;
        }
        
        int[] semver = parseSemver(strValue);
        
        for (int i = 0; i < ruleVersion.size(); i++) {
            if (i >= semver.length || semver[i] != ruleVersion.get(i)) {
                return rule.isNegate();
            }
        }
        
        return !rule.isNegate();
    }

    private static boolean evalSemverGte(@NotNull Rule rule, @NotNull Object value) {
        if (!(value instanceof String)) {
            return false;
        }
        String strValue = (String) value;
        List<Integer> ruleVersion = rule.getVersion();
        if (ruleVersion == null || ruleVersion.isEmpty()) {
            return false;
        }
        
        int[] semver = parseSemver(strValue);
        
        for (int i = 0; i < ruleVersion.size(); i++) {
            if (i >= semver.length) {
                return rule.isNegate();
            }
            
            if (semver[i] > ruleVersion.get(i)) {
                return !rule.isNegate();
            }
            
            if (semver[i] < ruleVersion.get(i)) {
                return rule.isNegate();
            }
        }
        
        return !rule.isNegate();
    }

    private static boolean evalSemverLte(@NotNull Rule rule, @NotNull Object value) {
        if (!(value instanceof String)) {
            return false;
        }
        String strValue = (String) value;
        List<Integer> ruleVersion = rule.getVersion();
        if (ruleVersion == null || ruleVersion.isEmpty()) {
            return false;
        }
        
        int[] semver = parseSemver(strValue);
        
        for (int i = 0; i < ruleVersion.size(); i++) {
            if (i >= semver.length) {
                return rule.isNegate();
            }
            
            if (semver[i] < ruleVersion.get(i)) {
                return !rule.isNegate();
            }
            
            if (semver[i] > ruleVersion.get(i)) {
                return rule.isNegate();
            }
        }
        
        return !rule.isNegate();
    }

    private static int[] parseSemver(String version) {
        String[] parts = version.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                result[i] = 0;
            }
        }
        return result;
    }

    private static boolean evalPercentage(@NotNull Rule rule, @NotNull Object value) {
        if (!(value instanceof String || value instanceof Number)) {
            return false;
        }
        
        Double rangeStart = rule.getRangeStart();
        Double rangeEnd = rule.getRangeEnd();
        Integer seed = rule.getSeed();
        
        if (rangeStart == null || rangeEnd == null || seed == null) {
            return false;
        }
        
        String strValue = String.valueOf(value);
        byte[] data = strValue.getBytes(StandardCharsets.UTF_8);
        
        // Use xxHash32 for consistent hashing (matching JavaScript xxhashjs implementation)
        int hash = XXHASH32.hash(data, 0, data.length, seed);
        
        // Convert to unsigned 32-bit and normalize to 0-1 range (matching JS: (hash >>> 0) / 0xffffffff)
        double probability = (hash & 0xFFFFFFFFL) / (double) 0xFFFFFFFFL;
        
        // Handle edge case where probability is exactly 1 (matching JS: Number.EPSILON)
        if (probability == 1.0) {
            probability = Math.nextDown(1.0);
        }
        
        boolean matches = probability >= rangeStart && probability < rangeEnd;
        return matches != rule.isNegate();
    }
}
