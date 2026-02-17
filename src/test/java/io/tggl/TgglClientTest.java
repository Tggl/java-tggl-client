package io.tggl;

import io.tggl.core.Condition;
import io.tggl.core.Flag;
import io.tggl.core.Rule;
import io.tggl.core.Variation;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TgglClientTest {

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
    @DisplayName("should fetch config and evaluate flags")
    void shouldFetchConfigAndEvaluateFlags() throws Exception {
        // Mock API response
        server.enqueue(new MockResponse()
            .setBody("""
                [
                    {
                        "slug": "myFlag",
                        "defaultVariation": { "active": true, "value": "on" },
                        "conditions": []
                    }
                ]
                """)
            .addHeader("Content-Type", "application/json"));

        try (TgglClient client = new TgglClient(TgglClientOptions.builder()
            .apiKey("test-api-key")
            .baseUrls(List.of(baseUrl))
            .pollingIntervalMs(0)
            .reportingEnabled(false)
            .build())) {

            client.waitReady().get(5, TimeUnit.SECONDS);

            assertTrue(client.isReady());
            assertEquals("on", client.get(Map.of(), "myFlag", "off"));
        }
    }

    @Test
    @DisplayName("should return default value for unknown flag")
    void shouldReturnDefaultForUnknownFlag() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("[]")
            .addHeader("Content-Type", "application/json"));

        try (TgglClient client = new TgglClient(TgglClientOptions.builder()
            .baseUrls(List.of(baseUrl))
            .pollingIntervalMs(0)
            .reportingEnabled(false)
            .build())) {

            client.waitReady().get(5, TimeUnit.SECONDS);

            assertEquals("default", client.get(Map.of(), "unknownFlag", "default"));
        }
    }

    @Test
    @DisplayName("should evaluate flag with context")
    void shouldEvaluateFlagWithContext() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("""
                [
                    {
                        "slug": "premiumFlag",
                        "defaultVariation": { "active": true, "value": "basic" },
                        "conditions": [
                            {
                                "rules": [
                                    { "key": "plan", "operator": "STR_EQUAL", "values": ["premium"], "negate": false }
                                ],
                                "variation": { "active": true, "value": "premium" }
                            }
                        ]
                    }
                ]
                """)
            .addHeader("Content-Type", "application/json"));

        try (TgglClient client = new TgglClient(TgglClientOptions.builder()
            .baseUrls(List.of(baseUrl))
            .pollingIntervalMs(0)
            .reportingEnabled(false)
            .build())) {

            client.waitReady().get(5, TimeUnit.SECONDS);

            assertEquals("premium", client.get(Map.of("plan", "premium"), "premiumFlag", "none"));
            assertEquals("basic", client.get(Map.of("plan", "basic"), "premiumFlag", "none"));
        }
    }

    @Test
    @DisplayName("should emit config change event")
    void shouldEmitConfigChangeEvent() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("""
                [
                    {
                        "slug": "flag1",
                        "defaultVariation": { "active": true, "value": "v1" },
                        "conditions": []
                    }
                ]
                """)
            .addHeader("Content-Type", "application/json"));

        AtomicReference<List<String>> changedFlags = new AtomicReference<>();

        try (TgglClient client = new TgglClient(TgglClientOptions.builder()
            .baseUrls(List.of(baseUrl))
            .pollingIntervalMs(0)
            .reportingEnabled(false)
            .initialFetch(false)
            .build())) {

            client.onConfigChange(changedFlags::set);
            client.refetch().get(5, TimeUnit.SECONDS);

            assertNotNull(changedFlags.get());
            assertTrue(changedFlags.get().contains("flag1"));
        }
    }

    @Test
    @DisplayName("should handle API errors gracefully")
    void shouldHandleApiErrorsGracefully() throws Exception {
        server.enqueue(new MockResponse()
            .setResponseCode(500)
            .setBody("{\"error\": \"Internal Server Error\"}"));

        AtomicBoolean errorReceived = new AtomicBoolean(false);

        try (TgglClient client = new TgglClient(TgglClientOptions.builder()
            .baseUrls(List.of(baseUrl))
            .pollingIntervalMs(0)
            .maxRetries(0)
            .reportingEnabled(false)
            .build())) {

            client.onError(e -> errorReceived.set(true));
            client.waitReady().get(5, TimeUnit.SECONDS);

            assertTrue(errorReceived.get());
            assertNotNull(client.getError());
        }
    }

    @Test
    @DisplayName("should create static client for context")
    void shouldCreateStaticClientForContext() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("""
                [
                    {
                        "slug": "testFlag",
                        "defaultVariation": { "active": true, "value": true },
                        "conditions": []
                    }
                ]
                """)
            .addHeader("Content-Type", "application/json"));

        try (TgglClient client = new TgglClient(TgglClientOptions.builder()
            .baseUrls(List.of(baseUrl))
            .pollingIntervalMs(0)
            .reportingEnabled(false)
            .build())) {

            client.waitReady().get(5, TimeUnit.SECONDS);

            Map<String, Object> context = Map.of("userId", "123");
            TgglStaticClient staticClient = client.createClientForContext(context);

            assertNotNull(staticClient);
            assertTrue(staticClient.get("testFlag", false));
            assertEquals(context, staticClient.getContext());
        }
    }

    @Test
    @DisplayName("should set config directly")
    void shouldSetConfigDirectly() {
        try (TgglClient client = new TgglClient(TgglClientOptions.builder()
            .pollingIntervalMs(0)
            .reportingEnabled(false)
            .initialFetch(false)
            .build())) {

            Flag flag = new Flag(
                "directFlag",
                new Variation(true, "direct"),
                List.of()
            );

            client.setConfig(Map.of("directFlag", flag));

            assertTrue(client.isReady());
            assertEquals("direct", client.get(Map.of(), "directFlag", "none"));
        }
    }

    @Test
    @DisplayName("should get all flags for context")
    void shouldGetAllFlagsForContext() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("""
                [
                    {
                        "slug": "flag1",
                        "defaultVariation": { "active": true, "value": "v1" },
                        "conditions": []
                    },
                    {
                        "slug": "flag2",
                        "defaultVariation": { "active": true, "value": "v2" },
                        "conditions": []
                    },
                    {
                        "slug": "inactiveFlag",
                        "defaultVariation": { "active": false, "value": null },
                        "conditions": []
                    }
                ]
                """)
            .addHeader("Content-Type", "application/json"));

        try (TgglClient client = new TgglClient(TgglClientOptions.builder()
            .baseUrls(List.of(baseUrl))
            .pollingIntervalMs(0)
            .reportingEnabled(false)
            .build())) {

            client.waitReady().get(5, TimeUnit.SECONDS);

            Map<String, Object> all = client.getAll(Map.of());

            assertEquals(2, all.size());
            assertEquals("v1", all.get("flag1"));
            assertEquals("v2", all.get("flag2"));
            assertFalse(all.containsKey("inactiveFlag"));
        }
    }
}
