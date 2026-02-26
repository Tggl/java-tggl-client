package io.tggl;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TgglRemoteClientTest {

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
    @DisplayName("should fetch flags for context and evaluate via get()")
    void shouldFetchFlagsAndEvaluate() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("{\"myFlag\": \"on\", \"otherFlag\": true}")
            .addHeader("Content-Type", "application/json"));

        try (TgglRemoteClient client = new TgglRemoteClient(TgglRemoteClientOptions.builder()
            .apiKey("test-api-key")
            .baseUrls(List.of(baseUrl))
            .pollingIntervalMs(0)
            .reportingEnabled(false)
            .initialContext(Map.of("userId", "123"))
            .build())) {

            client.waitReady().get(5, TimeUnit.SECONDS);

            assertTrue(client.isReady());
            assertEquals("on", client.get("myFlag", "off"));
            assertTrue(client.get("otherFlag", false));
        }
    }

    @Test
    @DisplayName("should send context as POST body to /flags")
    void shouldSendContextAsPost() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("{\"flag1\": \"value1\"}")
            .addHeader("Content-Type", "application/json"));

        try (TgglRemoteClient client = new TgglRemoteClient(TgglRemoteClientOptions.builder()
            .apiKey("test-key")
            .baseUrls(List.of(baseUrl))
            .pollingIntervalMs(0)
            .reportingEnabled(false)
            .initialContext(Map.of("userId", "abc"))
            .build())) {

            client.waitReady().get(5, TimeUnit.SECONDS);

            RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("POST", request.getMethod());
            assertTrue(request.getPath().endsWith("/flags"));
            assertEquals("test-key", request.getHeader("x-tggl-api-key"));
            assertTrue(request.getBody().readUtf8().contains("\"userId\""));
        }
    }

    @Test
    @DisplayName("should return default value for unknown flag")
    void shouldReturnDefaultForUnknownFlag() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("{}")
            .addHeader("Content-Type", "application/json"));

        try (TgglRemoteClient client = new TgglRemoteClient(TgglRemoteClientOptions.builder()
            .baseUrls(List.of(baseUrl))
            .pollingIntervalMs(0)
            .reportingEnabled(false)
            .build())) {

            client.waitReady().get(5, TimeUnit.SECONDS);

            assertEquals("default", client.get("unknownFlag", "default"));
        }
    }

    @Test
    @DisplayName("should update flags when setContext is called")
    void shouldUpdateFlagsOnSetContext() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("{\"flag1\": \"v1\"}")
            .addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse()
            .setBody("{\"flag1\": \"v2\", \"flag2\": true}")
            .addHeader("Content-Type", "application/json"));

        try (TgglRemoteClient client = new TgglRemoteClient(TgglRemoteClientOptions.builder()
            .baseUrls(List.of(baseUrl))
            .pollingIntervalMs(0)
            .reportingEnabled(false)
            .build())) {

            client.waitReady().get(5, TimeUnit.SECONDS);
            assertEquals("v1", client.get("flag1", "none"));

            client.setContext(Map.of("plan", "premium")).get(5, TimeUnit.SECONDS);
            assertEquals("v2", client.get("flag1", "none"));
            assertTrue(client.get("flag2", false));
        }
    }

    @Test
    @DisplayName("should emit flagsChange event")
    void shouldEmitFlagsChangeEvent() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("{\"flag1\": \"v1\"}")
            .addHeader("Content-Type", "application/json"));

        AtomicReference<List<String>> changedFlags = new AtomicReference<>();

        try (TgglRemoteClient client = new TgglRemoteClient(TgglRemoteClientOptions.builder()
            .baseUrls(List.of(baseUrl))
            .pollingIntervalMs(0)
            .reportingEnabled(false)
            .initialFetch(false)
            .build())) {

            client.onFlagsChange(changedFlags::set);
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

        try (TgglRemoteClient client = new TgglRemoteClient(TgglRemoteClientOptions.builder()
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
    @DisplayName("should get all flags")
    void shouldGetAllFlags() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("{\"flag1\": \"v1\", \"flag2\": \"v2\", \"flag3\": 42}")
            .addHeader("Content-Type", "application/json"));

        try (TgglRemoteClient client = new TgglRemoteClient(TgglRemoteClientOptions.builder()
            .baseUrls(List.of(baseUrl))
            .pollingIntervalMs(0)
            .reportingEnabled(false)
            .build())) {

            client.waitReady().get(5, TimeUnit.SECONDS);

            Map<String, Object> all = client.getAll();
            assertEquals(3, all.size());
            assertEquals("v1", all.get("flag1"));
            assertEquals("v2", all.get("flag2"));
            assertEquals(42, all.get("flag3"));
        }
    }

    @Test
    @DisplayName("should set flags directly")
    void shouldSetFlagsDirectly() {
        try (TgglRemoteClient client = new TgglRemoteClient(TgglRemoteClientOptions.builder()
            .pollingIntervalMs(0)
            .reportingEnabled(false)
            .initialFetch(false)
            .build())) {

            client.setFlags(Map.of("directFlag", "direct"));

            assertTrue(client.isReady());
            assertEquals("direct", client.get("directFlag", "none"));
        }
    }

    @Test
    @DisplayName("should return context")
    void shouldReturnContext() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("{}")
            .addHeader("Content-Type", "application/json"));

        try (TgglRemoteClient client = new TgglRemoteClient(TgglRemoteClientOptions.builder()
            .baseUrls(List.of(baseUrl))
            .pollingIntervalMs(0)
            .reportingEnabled(false)
            .initialFetch(false)
            .build())) {

            client.setContext(Map.of("userId", "abc")).get(5, TimeUnit.SECONDS);

            Map<String, Object> context = client.getContext();
            assertEquals("abc", context.get("userId"));
        }
    }
}
