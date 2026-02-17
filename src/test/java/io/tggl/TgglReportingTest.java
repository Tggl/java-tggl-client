package io.tggl;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TgglReportingTest {

    private MockWebServer server;
    private String baseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
    @DisplayName("should batch and flush flag reports")
    void shouldBatchAndFlushFlagReports() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));

        try (TgglReporting reporting = new TgglReporting(TgglReportingOptions.builder()
            .apiKey("test-key")
            .baseUrls(List.of(baseUrl))
            .flushIntervalMs(0)
            .build())) {

            reporting.reportFlag("flag1", "value1", "default1", "client1");
            reporting.reportFlag("flag1", "value1", "default1", "client1");
            reporting.reportFlag("flag2", true, false, "client1");

            reporting.start(100);
            reporting.flush().get(5, TimeUnit.SECONDS);

            RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("/report", request.getPath());

            Map<String, Object> body = objectMapper.readValue(
                request.getBody().readUtf8(), new TypeReference<>() {});
            assertNotNull(body.get("clients"));
        }
    }

    @Test
    @DisplayName("should batch and flush context property reports")
    void shouldBatchAndFlushContextProperties() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));

        try (TgglReporting reporting = new TgglReporting(TgglReportingOptions.builder()
            .apiKey("test-key")
            .baseUrls(List.of(baseUrl))
            .flushIntervalMs(0)
            .build())) {

            reporting.reportContext(Map.of("userId", "user-123", "plan", "premium"));

            reporting.start(100);
            reporting.flush().get(5, TimeUnit.SECONDS);

            RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(request);

            Map<String, Object> body = objectMapper.readValue(
                request.getBody().readUtf8(), new TypeReference<>() {});
            assertNotNull(body.get("receivedProperties"));
        }
    }

    @Test
    @DisplayName("should aggregate duplicate flag reports by incrementing count")
    void shouldAggregateDuplicateFlagReports() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));

        try (TgglReporting reporting = new TgglReporting(TgglReportingOptions.builder()
            .apiKey("test-key")
            .baseUrls(List.of(baseUrl))
            .flushIntervalMs(0)
            .build())) {

            reporting.reportFlag("flag1", "value", "default", "cid", 1);
            reporting.reportFlag("flag1", "value", "default", "cid", 1);
            reporting.reportFlag("flag1", "value", "default", "cid", 3);

            reporting.start(100);
            reporting.flush().get(5, TimeUnit.SECONDS);

            RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(request);

            String bodyStr = request.getBody().readUtf8();
            Map<String, Object> body = objectMapper.readValue(bodyStr, new TypeReference<>() {});

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> clients = (List<Map<String, Object>>) body.get("clients");
            assertNotNull(clients);
            assertFalse(clients.isEmpty());

            @SuppressWarnings("unchecked")
            Map<String, List<Map<String, Object>>> flags =
                (Map<String, List<Map<String, Object>>>) clients.get(0).get("flags");
            List<Map<String, Object>> flag1Reports = flags.get("flag1");
            assertNotNull(flag1Reports);
            assertEquals(1, flag1Reports.size());
            assertEquals(5, ((Number) flag1Reports.get(0).get("count")).intValue());
        }
    }

    @Test
    @DisplayName("should retry failed reports by merging back")
    void shouldRetryFailedReports() throws Exception {
        // First flush fails
        server.enqueue(new MockResponse().setResponseCode(500));
        // Second flush should succeed with merged data
        server.enqueue(new MockResponse().setResponseCode(200));

        try (TgglReporting reporting = new TgglReporting(TgglReportingOptions.builder()
            .apiKey("test-key")
            .baseUrls(List.of(baseUrl))
            .flushIntervalMs(0)
            .build())) {

            reporting.reportFlag("flag1", "value", "default", "cid");

            reporting.start(100);
            reporting.flush().get(5, TimeUnit.SECONDS);

            // First request fails, report merged back
            RecordedRequest firstRequest = server.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(firstRequest);

            // Second flush should send the merged data
            reporting.flush().get(5, TimeUnit.SECONDS);
            RecordedRequest secondRequest = server.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(secondRequest);
        }
    }

    @Test
    @DisplayName("should not send empty reports")
    void shouldNotSendEmptyReports() throws Exception {
        try (TgglReporting reporting = new TgglReporting(TgglReportingOptions.builder()
            .apiKey("test-key")
            .baseUrls(List.of(baseUrl))
            .flushIntervalMs(0)
            .build())) {

            reporting.start(100);
            reporting.flush().get(5, TimeUnit.SECONDS);

            RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
            assertNull(request);
        }
    }

    @Test
    @DisplayName("should implement AutoCloseable")
    void shouldImplementAutoCloseable() {
        TgglReporting reporting = new TgglReporting(TgglReportingOptions.builder()
            .flushIntervalMs(0)
            .build());

        assertDoesNotThrow(reporting::close);
    }

    @Test
    @DisplayName("should handle ID-to-name label mapping in context")
    void shouldHandleIdToNameLabelMapping() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));

        try (TgglReporting reporting = new TgglReporting(TgglReportingOptions.builder()
            .apiKey("test-key")
            .baseUrls(List.of(baseUrl))
            .flushIntervalMs(0)
            .build())) {

            reporting.reportContext(Map.of(
                "userId", "user-123",
                "userName", "John"
            ));

            reporting.start(100);
            reporting.flush().get(5, TimeUnit.SECONDS);

            RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(request);
        }
    }
}
