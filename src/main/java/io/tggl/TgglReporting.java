package io.tggl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Handles reporting of flag evaluations and context data to the Tggl API.
 * Reports are batched and sent periodically to minimize network overhead.
 */
public class TgglReporting implements AutoCloseable {
    
    private static final Logger logger = LoggerFactory.getLogger(TgglReporting.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Pattern CONSTANT_CASE_PATTERN = Pattern.compile("([a-z])([A-Z])");
    
    private final String apiKey;
    private final List<String> baseUrls;
    private volatile long flushIntervalMs;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<ScheduledFuture<?>> nextFlush = new AtomicReference<>();
    
    // Report data structures (thread-safe, volatile for atomic swap in flushSync)
    private volatile ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<String, FlagReport>>> reportFlags = new ConcurrentHashMap<>();
    private volatile ConcurrentHashMap<String, long[]> reportProperties = new ConcurrentHashMap<>();
    private volatile ConcurrentHashMap<String, ConcurrentHashMap<String, String>> reportValues = new ConcurrentHashMap<>();

    /**
     * Creates a new TgglReporting instance with the given options.
     */
    public TgglReporting(@NotNull TgglReportingOptions options) {
        this.apiKey = options.getApiKey();
        this.baseUrls = new ArrayList<>(options.getBaseUrls());
        this.flushIntervalMs = options.getFlushIntervalMs();
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();
        this.objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tggl-reporting");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Creates a new TgglReporting instance with default options.
     */
    public TgglReporting() {
        this(TgglReportingOptions.builder().build());
    }

    /**
     * Stops the reporting scheduler.
     */
    public void stop() {
        start(0);
    }

    /**
     * Starts or restarts the reporting scheduler with the given interval.
     *
     * @param flushIntervalMs The interval in milliseconds between flushes. Set to 0 to disable.
     */
    public void start(long flushIntervalMs) {
        this.flushIntervalMs = Math.max(0, flushIntervalMs);
        
        ScheduledFuture<?> currentFlush = nextFlush.get();
        if (flushIntervalMs <= 0 && currentFlush != null) {
            currentFlush.cancel(false);
            nextFlush.set(null);
        }
        
        scheduleNextFlush();
    }

    /**
     * Returns whether the reporting scheduler is active.
     */
    public boolean isActive() {
        return flushIntervalMs > 0;
    }

    /**
     * Flushes all pending reports to the API.
     *
     * @return A CompletableFuture that completes when the flush is done
     */
    public CompletableFuture<Void> flush() {
        return CompletableFuture.runAsync(() -> {
            ScheduledFuture<?> currentFlush = nextFlush.getAndSet(null);
            if (currentFlush != null) {
                currentFlush.cancel(false);
            }
            
            try {
                flushSync();
            } finally {
                scheduleNextFlush();
            }
        }, scheduler);
    }

    private void flushSync() {
        Map<String, Object> report = new HashMap<>();
        
        // Collect flags report (atomic swap: replace with new empty map, process old)
        if (!reportFlags.isEmpty()) {
            ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<String, FlagReport>>> flagsToReport = reportFlags;
            reportFlags = new ConcurrentHashMap<>();
            
            List<Map<String, Object>> clients = new ArrayList<>();
            for (Map.Entry<String, ConcurrentHashMap<String, ConcurrentHashMap<String, FlagReport>>> clientEntry : flagsToReport.entrySet()) {
                Map<String, Object> client = new HashMap<>();
                String clientId = clientEntry.getKey();
                if (!clientId.isEmpty()) {
                    client.put("id", clientId);
                }
                
                Map<String, List<Map<String, Object>>> flags = new HashMap<>();
                for (Map.Entry<String, ConcurrentHashMap<String, FlagReport>> slugEntry : clientEntry.getValue().entrySet()) {
                    List<Map<String, Object>> values = new ArrayList<>();
                    for (FlagReport flagReport : slugEntry.getValue().values()) {
                        Map<String, Object> value = new HashMap<>();
                        value.put("value", flagReport.value);
                        value.put("default", flagReport.defaultValue);
                        value.put("count", flagReport.count);
                        values.add(value);
                    }
                    flags.put(slugEntry.getKey(), values);
                }
                client.put("flags", flags);
                clients.add(client);
            }
            report.put("clients", clients);
        }
        
        // Collect properties report (atomic swap)
        if (!reportProperties.isEmpty()) {
            ConcurrentHashMap<String, long[]> receivedProperties = reportProperties;
            reportProperties = new ConcurrentHashMap<>();
            
            Map<String, List<Long>> propsMap = new HashMap<>();
            for (Map.Entry<String, long[]> entry : receivedProperties.entrySet()) {
                propsMap.put(entry.getKey(), Arrays.asList(entry.getValue()[0], entry.getValue()[1]));
            }
            report.put("receivedProperties", propsMap);
        }
        
        // Collect values report (atomic swap)
        List<List<String>> values = new ArrayList<>();
        if (!reportValues.isEmpty()) {
            ConcurrentHashMap<String, ConcurrentHashMap<String, String>> receivedValues = reportValues;
            reportValues = new ConcurrentHashMap<>();
            
            for (Map.Entry<String, ConcurrentHashMap<String, String>> keyEntry : receivedValues.entrySet()) {
                for (Map.Entry<String, String> valueEntry : keyEntry.getValue().entrySet()) {
                    String label = valueEntry.getValue();
                    if (label != null) {
                        values.add(Arrays.asList(keyEntry.getKey(), valueEntry.getKey(), label));
                    } else {
                        values.add(Arrays.asList(keyEntry.getKey(), valueEntry.getKey()));
                    }
                }
            }
        }
        
        // Send reports in batches
        for (int i = 0; i < values.size() || report.containsKey("clients") || report.containsKey("receivedProperties"); i += 2000) {
            if (!values.isEmpty()) {
                List<List<String>> batch = values.subList(i, Math.min(i + 2000, values.size()));
                Map<String, List<List<String>>> receivedValues = new HashMap<>();
                for (List<String> item : batch) {
                    String key = item.get(0);
                    List<String> value = item.subList(1, item.size());
                    // Truncate values to 240 characters
                    List<String> truncated = new ArrayList<>();
                    for (String v : value) {
                        truncated.add(v.length() > 240 ? v.substring(0, 240) : v);
                    }
                    receivedValues.computeIfAbsent(key, k -> new ArrayList<>()).add(truncated);
                }
                if (!receivedValues.isEmpty()) {
                    report.put("receivedValues", receivedValues);
                }
            }
            
            if (report.isEmpty()) {
                break;
            }
            
            sendReport(report);
            
            // Clear for next iteration
            report = new HashMap<>();
        }
    }

    private void sendReport(Map<String, Object> report) {
        try {
            String json = objectMapper.writeValueAsString(report);
            RequestBody body = RequestBody.create(json, JSON);
            
            Exception lastError = null;
            for (String baseUrl : baseUrls) {
                Request.Builder requestBuilder = new Request.Builder()
                    .url(baseUrl + "/report")
                    .post(body);
                
                if (apiKey != null) {
                    requestBuilder.addHeader("x-tggl-api-key", apiKey);
                }
                
                try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
                    if (response.isSuccessful()) {
                        return;
                    }
                    lastError = new IOException("HTTP " + response.code());
                } catch (IOException e) {
                    lastError = e;
                }
            }
            
            if (lastError != null) {
                logger.debug("Failed to send report: {}", lastError.getMessage());
                // Merge report back for retry
                mergeReport(report);
            }
        } catch (JsonProcessingException e) {
            logger.debug("Failed to serialize report: {}", e.getMessage());
        }
    }

    private void scheduleNextFlush() {
        if (flushIntervalMs <= 0 || nextFlush.get() != null) {
            return;
        }
        
        if (reportFlags.isEmpty() && reportProperties.isEmpty() && reportValues.isEmpty()) {
            return;
        }
        
        ScheduledFuture<?> future = scheduler.schedule(this::flushSync, flushIntervalMs, TimeUnit.MILLISECONDS);
        nextFlush.set(future);
    }

    /**
     * Reports a flag evaluation.
     *
     * @param slug         The flag slug
     * @param value        The evaluated value
     * @param defaultValue The default value
     * @param clientId     The client ID (optional)
     */
    public void reportFlag(@NotNull String slug, @Nullable Object value, @Nullable Object defaultValue, @Nullable String clientId) {
        reportFlag(slug, value, defaultValue, clientId, 1);
    }

    /**
     * Reports a flag evaluation with a count.
     *
     * @param slug         The flag slug
     * @param value        The evaluated value
     * @param defaultValue The default value
     * @param clientId     The client ID (optional)
     * @param count        The number of evaluations
     */
    public void reportFlag(@NotNull String slug, @Nullable Object value, @Nullable Object defaultValue, @Nullable String clientId, int count) {
        try {
            String cid = clientId != null ? clientId : "";
            String key = serializeValue(value) + serializeValue(defaultValue);
            
            reportFlags.computeIfAbsent(cid, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(slug, k -> new ConcurrentHashMap<>())
                .compute(key, (k, existing) -> {
                    if (existing == null) {
                        return new FlagReport(value, defaultValue, count);
                    } else {
                        existing.count += count;
                        return existing;
                    }
                });
        } catch (Exception e) {
            // Ignore errors
        }
        
        scheduleNextFlush();
    }

    /**
     * Reports context properties.
     *
     * @param context The context map
     */
    public void reportContext(@NotNull Map<String, Object> context) {
        try {
            long now = System.currentTimeMillis() / 1000;
            
            for (Map.Entry<String, Object> entry : context.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                
                reportProperties.compute(key, (k, existing) -> {
                    if (existing == null) {
                        return new long[] { now, now };
                    } else {
                        existing[1] = now;
                        return existing;
                    }
                });
                
                if (value instanceof String && !((String) value).isEmpty()) {
                    String strValue = (String) value;
                    String constantCaseKey = toConstantCase(key).replaceAll("_I_D$", "_ID");
                    String labelKeyTarget = constantCaseKey.endsWith("_ID") 
                        ? constantCaseKey.substring(0, constantCaseKey.length() - 3) + "_NAME"
                        : null;
                    
                    String labelKey = null;
                    if (labelKeyTarget != null) {
                        for (String ctxKey : context.keySet()) {
                            if (toConstantCase(ctxKey).equals(labelKeyTarget)) {
                                labelKey = ctxKey;
                                break;
                            }
                        }
                    }
                    
                    String finalLabelKey = labelKey;
                    reportValues.computeIfAbsent(key, k -> new ConcurrentHashMap<>())
                        .compute(strValue, (k, existing) -> {
                            if (finalLabelKey != null) {
                                Object labelObj = context.get(finalLabelKey);
                                if (labelObj instanceof String && !((String) labelObj).isEmpty()) {
                                    return (String) labelObj;
                                }
                            }
                            return existing;
                        });
                }
            }
        } catch (Exception e) {
            // Ignore errors
        }
        
        scheduleNextFlush();
    }

    /**
     * Merges an existing report back into the pending reports.
     * Used for retry logic.
     */
    @SuppressWarnings("unchecked")
    public void mergeReport(@NotNull Map<String, Object> report) {
        try {
            if (report.containsKey("receivedProperties")) {
                Map<String, List<Number>> receivedProperties = (Map<String, List<Number>>) report.get("receivedProperties");
                for (Map.Entry<String, List<Number>> entry : receivedProperties.entrySet()) {
                    long min = entry.getValue().get(0).longValue();
                    long max = entry.getValue().get(1).longValue();
                    reportProperties.compute(entry.getKey(), (k, existing) -> {
                        if (existing == null) {
                            return new long[] { min, max };
                        } else {
                            existing[0] = Math.min(existing[0], min);
                            existing[1] = Math.max(existing[1], max);
                            return existing;
                        }
                    });
                }
            }
            
            if (report.containsKey("receivedValues")) {
                Map<String, List<List<String>>> receivedValues = (Map<String, List<List<String>>>) report.get("receivedValues");
                for (Map.Entry<String, List<List<String>>> entry : receivedValues.entrySet()) {
                    for (List<String> value : entry.getValue()) {
                        String val = value.get(0);
                        String label = value.size() > 1 ? value.get(1) : null;
                        reportValues.computeIfAbsent(entry.getKey(), k -> new ConcurrentHashMap<>())
                            .compute(val, (k, existing) -> label != null ? label : existing);
                    }
                }
            }
            
            if (report.containsKey("clients")) {
                List<Map<String, Object>> clients = (List<Map<String, Object>>) report.get("clients");
                for (Map<String, Object> client : clients) {
                    String clientId = (String) client.getOrDefault("id", "");
                    Map<String, List<Map<String, Object>>> flags = (Map<String, List<Map<String, Object>>>) client.get("flags");
                    for (Map.Entry<String, List<Map<String, Object>>> flagEntry : flags.entrySet()) {
                        for (Map<String, Object> data : flagEntry.getValue()) {
                            Object value = data.get("value");
                            Object defaultValue = data.get("default");
                            int count = ((Number) data.getOrDefault("count", 1)).intValue();
                            reportFlag(flagEntry.getKey(), value, defaultValue, clientId, count);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Ignore errors
        }
        
        scheduleNextFlush();
    }

    /**
     * Closes the reporting system, flushing pending reports and releasing all resources.
     * Equivalent to calling {@link #stop()}, flushing, then shutting down the executor
     * and HTTP client.
     */
    @Override
    public void close() {
        stop();
        try {
            flush().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.debug("Timeout or error flushing reports during close: {}", e.getMessage());
        }

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    private String serializeValue(@Nullable Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "null";
        }
    }

    private static String toConstantCase(String str) {
        return CONSTANT_CASE_PATTERN.matcher(str)
            .replaceAll("$1_$2")
            .replaceAll("[\\W_]+", "_")
            .toUpperCase();
    }

    private static class FlagReport {
        Object value;
        Object defaultValue;
        int count;

        FlagReport(Object value, Object defaultValue, int count) {
            this.value = value;
            this.defaultValue = defaultValue;
            this.count = count;
        }
    }
}
