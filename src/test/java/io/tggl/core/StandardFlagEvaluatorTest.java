package io.tggl.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the standard tests from standard_tests.json to ensure compatibility
 * with the Tggl specification across all client implementations.
 */
@DisplayName("Standard Flag Evaluator Tests")
class StandardFlagEvaluatorTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static List<JsonNode> testCases;

    @BeforeAll
    static void loadTestCases() throws IOException {
        try (InputStream is = StandardFlagEvaluatorTest.class.getResourceAsStream("/standard_tests.json")) {
            assertNotNull(is, "standard_tests.json not found on classpath");
            testCases = objectMapper.readValue(is, new TypeReference<List<JsonNode>>() {});
        }
    }

    static Stream<Arguments> standardTestCases() throws IOException {
        // Ensure test cases are loaded
        if (testCases == null) {
            loadTestCases();
        }
        
        return testCases.stream()
                .map(node -> Arguments.of(
                        node.get("name").asText(),
                        node
                ));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("standardTestCases")
    @DisplayName("Standard test")
    void runStandardTest(String testName, JsonNode testCase) throws Exception {
        // Parse the flag configuration
        Flag flag = objectMapper.treeToValue(testCase.get("flag"), Flag.class);
        
        // Parse the context - handle various value types properly
        Map<String, Object> context = parseContext(testCase.get("context"));
        
        // Get expected result
        JsonNode expected = testCase.get("expected");
        boolean expectedActive = expected.get("active").asBoolean();
        Object expectedValue = parseValue(expected.get("value"));
        
        // Evaluate the flag
        Object result = FlagEvaluator.evalFlag(context, flag);
        
        // Validate results
        if (!expectedActive) {
            // When expected is inactive, result should be null
            assertNull(result, 
                    String.format("Test '%s': expected inactive (null) but got: %s", testName, result));
        } else {
            // When expected is active, result should match expected value
            if (expectedValue == null) {
                assertNull(result,
                        String.format("Test '%s': expected null value but got: %s", testName, result));
            } else {
                assertNotNull(result,
                        String.format("Test '%s': expected %s but got null", testName, expectedValue));
                
                // Handle numeric comparison with tolerance for floating point
                if (expectedValue instanceof Number && result instanceof Number) {
                    assertEquals(
                            ((Number) expectedValue).doubleValue(),
                            ((Number) result).doubleValue(),
                            0.0001,
                            String.format("Test '%s': numeric value mismatch", testName)
                    );
                } else {
                    assertEquals(expectedValue, result,
                            String.format("Test '%s': value mismatch", testName));
                }
            }
        }
    }

    /**
     * Parse the context JSON node into a Map with properly typed values.
     */
    private Map<String, Object> parseContext(JsonNode contextNode) {
        Map<String, Object> context = new HashMap<>();
        
        if (contextNode == null || contextNode.isNull()) {
            return context;
        }
        
        contextNode.fields().forEachRemaining(entry -> {
            context.put(entry.getKey(), parseValue(entry.getValue()));
        });
        
        return context;
    }

    /**
     * Parse a JSON value into the appropriate Java type.
     */
    private Object parseValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        
        if (node.isTextual()) {
            return node.asText();
        }
        
        if (node.isInt()) {
            return node.asInt();
        }
        
        if (node.isLong()) {
            return node.asLong();
        }
        
        if (node.isDouble() || node.isFloat()) {
            return node.asDouble();
        }
        
        if (node.isNumber()) {
            // Handle other numeric types
            return node.numberValue();
        }
        
        if (node.isArray()) {
            List<Object> list = new java.util.ArrayList<>();
            for (JsonNode element : node) {
                list.add(parseValue(element));
            }
            return list;
        }
        
        if (node.isObject()) {
            Map<String, Object> map = new HashMap<>();
            node.fields().forEachRemaining(entry -> {
                map.put(entry.getKey(), parseValue(entry.getValue()));
            });
            return map;
        }
        
        return node.asText();
    }
}
