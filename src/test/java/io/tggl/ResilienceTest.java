package io.tggl;

import io.tggl.core.Condition;
import io.tggl.core.Flag;
import io.tggl.core.FlagEvaluator;
import io.tggl.core.Operator;
import io.tggl.core.Rule;
import io.tggl.core.Variation;
import io.tggl.storage.TgglStorage;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Resilience Tests")
class ResilienceTest {

    @Nested
    @DisplayName("Unknown operator handling")
    class UnknownOperatorHandling {

        @Test
        @DisplayName("should return null for unknown operator from Operator.fromValue")
        void shouldReturnNullForUnknownOperator() {
            assertNull(Operator.fromValue("TOTALLY_NEW_OPERATOR"));
            assertNull(Operator.fromValue(""));
        }

        @Test
        @DisplayName("should gracefully skip rules with unknown operators")
        void shouldGracefullySkipUnknownOperatorRules() {
            // Create a rule with an unknown operator - the Rule constructor
            // stores null for unknown operators
            Rule rule = new Rule("key", "FUTURE_OPERATOR", false,
                null, null, null, null, null, null, null, null);
            assertNull(rule.getOperator());
            assertFalse(FlagEvaluator.evalRule(rule, "anyValue"));
        }

        @Test
        @DisplayName("should evaluate flag with mix of known and unknown operators")
        void shouldEvaluateFlagWithMixedOperators() {
            // A flag where one condition has an unknown operator
            Rule unknownRule = new Rule("key", "UNKNOWN_OP", false,
                null, null, null, null, null, null, null, null);
            Rule knownRule = new Rule("plan", "STR_EQUAL", false,
                null, null, null, List.of("premium"), null, null, null, null);

            Flag flag = new Flag(
                "testFlag",
                new Variation(true, "default"),
                List.of(
                    new Condition(
                        List.of(unknownRule),
                        new Variation(true, "unknown_branch")
                    ),
                    new Condition(
                        List.of(knownRule),
                        new Variation(true, "premium_branch")
                    )
                )
            );

            // The unknown operator condition should fail, so it falls through to the next one
            assertEquals("premium_branch",
                FlagEvaluator.evalFlag(Map.of("plan", "premium"), flag));

            // When no known conditions match, returns default
            assertEquals("default",
                FlagEvaluator.evalFlag(Map.of("plan", "basic"), flag));
        }
    }

    @Nested
    @DisplayName("Concurrent access safety")
    class ConcurrentAccessSafety {

        private MockWebServer server;
        private String baseUrl;

        @BeforeEach
        void setUp() throws IOException {
            server = new MockWebServer();
            server.start();
            baseUrl = server.url("/").toString().replaceAll("/$", "");
        }

        @AfterEach
        void tearDown() throws IOException {
            server.shutdown();
        }

        @Test
        @DisplayName("should safely evaluate flags during config swap")
        void shouldSafelyEvaluateFlagsDuringConfigSwap() throws Exception {
            try (TgglClient client = new TgglClient(TgglClientOptions.builder()
                .pollingIntervalMs(0)
                .reportingEnabled(false)
                .initialFetch(false)
                .build())) {

                Flag flagV1 = new Flag("myFlag", new Variation(true, "v1"), List.of());
                Flag flagV2 = new Flag("myFlag", new Variation(true, "v2"), List.of());

                client.setConfig(Map.of("myFlag", flagV1));

                int numThreads = 10;
                int iterationsPerThread = 1000;
                ExecutorService executor = Executors.newFixedThreadPool(numThreads);
                CountDownLatch startLatch = new CountDownLatch(1);
                CountDownLatch doneLatch = new CountDownLatch(numThreads);
                AtomicInteger errors = new AtomicInteger(0);
                ConcurrentHashMap<String, Boolean> observedValues = new ConcurrentHashMap<>();

                for (int t = 0; t < numThreads; t++) {
                    final int threadId = t;
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            for (int i = 0; i < iterationsPerThread; i++) {
                                // Half the threads read, half the threads swap config
                                if (threadId % 2 == 0) {
                                    try {
                                        String val = client.get(Map.of(), "myFlag", "fallback");
                                        // Should never get null or throw
                                        if (val == null) {
                                            errors.incrementAndGet();
                                        }
                                        observedValues.put(val, true);
                                    } catch (Exception e) {
                                        errors.incrementAndGet();
                                    }
                                } else {
                                    Flag flag = (i % 2 == 0) ? flagV1 : flagV2;
                                    client.setConfig(Map.of("myFlag", flag));
                                }
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                startLatch.countDown();
                assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
                executor.shutdown();

                assertEquals(0, errors.get(), "No errors should occur during concurrent access");
                // Should only observe valid values, never null or unexpected values
                for (String val : observedValues.keySet()) {
                    assertTrue(
                        "v1".equals(val) || "v2".equals(val) || "fallback".equals(val),
                        "Observed unexpected value: " + val
                    );
                }
            }
        }
    }

    @Nested
    @DisplayName("Builder validation")
    class BuilderValidation {

        @Test
        @DisplayName("should reject negative timeoutMs")
        void shouldRejectNegativeTimeout() {
            assertThrows(IllegalArgumentException.class, () ->
                TgglClientOptions.builder().timeoutMs(-1).build());
        }

        @Test
        @DisplayName("should reject negative pollingIntervalMs")
        void shouldRejectNegativePollingInterval() {
            assertThrows(IllegalArgumentException.class, () ->
                TgglClientOptions.builder().pollingIntervalMs(-1).build());
        }

        @Test
        @DisplayName("should reject negative maxRetries")
        void shouldRejectNegativeMaxRetries() {
            assertThrows(IllegalArgumentException.class, () ->
                TgglClientOptions.builder().maxRetries(-1).build());
        }

        @Test
        @DisplayName("should accept zero values")
        void shouldAcceptZeroValues() {
            assertDoesNotThrow(() ->
                TgglClientOptions.builder()
                    .timeoutMs(0)
                    .pollingIntervalMs(0)
                    .maxRetries(0)
                    .build());
        }
    }

    @Nested
    @DisplayName("Resource cleanup")
    class ResourceCleanup {

        @Test
        @DisplayName("should close TgglClient without hanging")
        void shouldCloseTgglClientWithoutHanging() throws Exception {
            TgglClient client = new TgglClient(TgglClientOptions.builder()
                .pollingIntervalMs(0)
                .reportingEnabled(false)
                .initialFetch(false)
                .build());

            // close() should not hang - verify it completes within 10 seconds
            CompletableFuture<Void> closeFuture = CompletableFuture.runAsync(client::close);
            assertDoesNotThrow(() -> closeFuture.get(10, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("should close TgglReporting without hanging")
        void shouldCloseTgglReportingWithoutHanging() throws Exception {
            TgglReporting reporting = new TgglReporting(TgglReportingOptions.builder()
                .flushIntervalMs(0)
                .build());

            CompletableFuture<Void> closeFuture = CompletableFuture.runAsync(reporting::close);
            assertDoesNotThrow(() -> closeFuture.get(10, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("should handle double close gracefully")
        void shouldHandleDoubleCloseGracefully() {
            TgglClient client = new TgglClient(TgglClientOptions.builder()
                .pollingIntervalMs(0)
                .reportingEnabled(false)
                .initialFetch(false)
                .build());

            assertDoesNotThrow(() -> {
                client.close();
                client.close();
            });
        }
    }

    @Nested
    @DisplayName("Storage persistence")
    class StoragePersistence {

        private MockWebServer server;
        private String baseUrl;

        @BeforeEach
        void setUp() throws IOException {
            server = new MockWebServer();
            server.start();
            baseUrl = server.url("/").toString().replaceAll("/$", "");
        }

        @AfterEach
        void tearDown() throws IOException {
            server.shutdown();
        }

        @Test
        @DisplayName("should save config to storage after fetch")
        void shouldSaveConfigToStorageAfterFetch() throws Exception {
            server.enqueue(new MockResponse()
                .setBody("""
                    [
                        {
                            "slug": "storedFlag",
                            "defaultVariation": { "active": true, "value": "stored" },
                            "conditions": []
                        }
                    ]
                    """)
                .addHeader("Content-Type", "application/json"));

            CompletableFuture<String> storedValue = new CompletableFuture<>();
            TgglStorage storage = new TgglStorage() {
                @Override
                public CompletableFuture<String> get() {
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletableFuture<Void> set(String value) {
                    storedValue.complete(value);
                    return CompletableFuture.completedFuture(null);
                }
            };

            try (TgglClient client = new TgglClient(TgglClientOptions.builder()
                .baseUrls(List.of(baseUrl))
                .pollingIntervalMs(0)
                .reportingEnabled(false)
                .addStorage(storage)
                .build())) {

                client.waitReady().get(5, TimeUnit.SECONDS);

                String saved = storedValue.get(5, TimeUnit.SECONDS);
                assertNotNull(saved);
                assertTrue(saved.contains("TgglClientState"));
                assertTrue(saved.contains("storedFlag"));
            }
        }

        @Test
        @DisplayName("should load config from storage on startup")
        void shouldLoadConfigFromStorageOnStartup() throws Exception {
            String storedConfig = """
                {
                    "type": "TgglClientState",
                    "date": 9999999999999,
                    "config": {
                        "cachedFlag": {
                            "slug": "cachedFlag",
                            "defaultVariation": { "active": true, "value": "cached" },
                            "conditions": []
                        }
                    }
                }
                """;

            TgglStorage storage = new TgglStorage() {
                @Override
                public CompletableFuture<String> get() {
                    return CompletableFuture.completedFuture(storedConfig);
                }

                @Override
                public CompletableFuture<Void> set(String value) {
                    return CompletableFuture.completedFuture(null);
                }
            };

            try (TgglClient client = new TgglClient(TgglClientOptions.builder()
                .pollingIntervalMs(0)
                .reportingEnabled(false)
                .initialFetch(false)
                .addStorage(storage)
                .build())) {

                // Give async storage loading a moment to complete
                Thread.sleep(100);

                assertTrue(client.isReady());
                assertEquals("cached", client.get(Map.of(), "cachedFlag", "default"));
            }
        }

        @Test
        @DisplayName("should handle storage errors gracefully")
        void shouldHandleStorageErrorsGracefully() {
            TgglStorage failingStorage = new TgglStorage() {
                @Override
                public CompletableFuture<String> get() {
                    return CompletableFuture.failedFuture(new RuntimeException("storage failed"));
                }

                @Override
                public CompletableFuture<Void> set(String value) {
                    return CompletableFuture.failedFuture(new RuntimeException("storage failed"));
                }
            };

            assertDoesNotThrow(() -> {
                TgglClient client = new TgglClient(TgglClientOptions.builder()
                    .pollingIntervalMs(0)
                    .reportingEnabled(false)
                    .initialFetch(false)
                    .addStorage(failingStorage)
                    .build());
                client.close();
            });
        }
    }

    @Nested
    @DisplayName("Flag equals/hashCode")
    class FlagEquality {

        @Test
        @DisplayName("equal flags should be equal")
        void equalFlagsShouldBeEqual() {
            Flag flag1 = new Flag("slug", new Variation(true, "val"),
                List.of(new Condition(
                    List.of(new Rule("key", "EMPTY", false, null, null, null, null, null, null, null, null)),
                    new Variation(true, "v")
                )));
            Flag flag2 = new Flag("slug", new Variation(true, "val"),
                List.of(new Condition(
                    List.of(new Rule("key", "EMPTY", false, null, null, null, null, null, null, null, null)),
                    new Variation(true, "v")
                )));

            assertEquals(flag1, flag2);
            assertEquals(flag1.hashCode(), flag2.hashCode());
        }

        @Test
        @DisplayName("different flags should not be equal")
        void differentFlagsShouldNotBeEqual() {
            Flag flag1 = new Flag("slug", new Variation(true, "val1"), List.of());
            Flag flag2 = new Flag("slug", new Variation(true, "val2"), List.of());

            assertNotEquals(flag1, flag2);
        }

        @Test
        @DisplayName("config change detection should work with equals")
        void configChangeDetectionShouldWork() {
            try (TgglClient client = new TgglClient(TgglClientOptions.builder()
                .pollingIntervalMs(0)
                .reportingEnabled(false)
                .initialFetch(false)
                .build())) {

                Flag flag = new Flag("f", new Variation(true, "v"), List.of());
                AtomicInteger changeCount = new AtomicInteger(0);
                client.onConfigChange(changed -> changeCount.incrementAndGet());

                client.setConfig(Map.of("f", flag));
                assertEquals(1, changeCount.get());

                // Same config again: should not trigger change
                client.setConfig(Map.of("f", flag));
                assertEquals(1, changeCount.get());

                // Different config: should trigger change
                Flag flag2 = new Flag("f", new Variation(true, "v2"), List.of());
                client.setConfig(Map.of("f", flag2));
                assertEquals(2, changeCount.get());
            }
        }
    }

    @Nested
    @DisplayName("Defensive copies")
    class DefensiveCopies {

        @Test
        @DisplayName("Options lists should be unmodifiable")
        void optionsListsShouldBeUnmodifiable() {
            TgglClientOptions options = TgglClientOptions.builder().build();
            assertThrows(UnsupportedOperationException.class,
                () -> options.getBaseUrls().add("http://evil.com"));
            assertThrows(UnsupportedOperationException.class,
                () -> options.getStorages().add(null));
        }

        @Test
        @DisplayName("ReportingOptions lists should be unmodifiable")
        void reportingOptionsListsShouldBeUnmodifiable() {
            TgglReportingOptions options = TgglReportingOptions.builder().build();
            assertThrows(UnsupportedOperationException.class,
                () -> options.getBaseUrls().add("http://evil.com"));
        }

        @Test
        @DisplayName("Flag conditions should be unmodifiable")
        void flagConditionsShouldBeUnmodifiable() {
            Flag flag = new Flag("s", new Variation(true, "v"),
                List.of(new Condition(List.of(), new Variation(false, null))));
            assertThrows(UnsupportedOperationException.class,
                () -> flag.getConditions().add(null));
        }

        @Test
        @DisplayName("Condition rules should be unmodifiable")
        void conditionRulesShouldBeUnmodifiable() {
            Condition condition = new Condition(
                List.of(new Rule("k", "EMPTY", false, null, null, null, null, null, null, null, null)),
                new Variation(true, "v"));
            assertThrows(UnsupportedOperationException.class,
                () -> condition.rules().add(null));
        }
    }
}
