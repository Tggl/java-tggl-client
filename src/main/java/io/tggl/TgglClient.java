package io.tggl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tggl.core.Flag;
import io.tggl.core.FlagEvaluator;
import io.tggl.storage.TgglStorage;
import io.tggl.util.ListenerRegistry;
import io.tggl.util.RunnableRegistry;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A client that fetches flag configuration and evaluates flags locally.
 * This provides the best performance for flag evaluation as it doesn't require
 * network calls for each evaluation.
 * <p>
 * Usage in Java:
 * <pre>{@code
 * TgglClient client = new TgglClient(TgglClientOptions.builder()
 *     .apiKey("your-api-key")
 *     .build());
 * 
 * // Wait for initial fetch
 * client.waitReady().join();
 * 
 * // Evaluate flags
 * Map<String, Object> context = Collections.singletonMap("userId", "123");
 * boolean value = client.get(context, "myFlag", false);
 * }</pre>
 * <p>
 * Usage in Kotlin:
 * <pre>{@code
 * val client = TgglClient(TgglClientOptions.builder()
 *     .apiKey("your-api-key")
 *     .build())
 * 
 * // Wait for initial fetch
 * client.waitReady().join()
 * 
 * // Evaluate flags
 * val context = mapOf("userId" to "123")
 * val value = client.get(context, "myFlag", false)
 * }</pre>
 */
public class TgglClient implements AutoCloseable {
    
    private static final Logger logger = LoggerFactory.getLogger(TgglClient.class);
    
    private final String apiKey;
    private final List<String> baseUrls;
    private final String appName;
    private final TgglReporting reporting;
    private final boolean ownsReporting;
    private final int maxRetries;
    private final long timeoutMs;
    private final List<TgglStorage> storages;
    private final String clientId;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler;
    
    // Typed listener registries
    private final ListenerRegistry<FlagEvalEvent> flagEvalListeners = new ListenerRegistry<>();
    private final RunnableRegistry fetchSuccessListeners = new RunnableRegistry();
    private final ListenerRegistry<List<String>> configChangeListeners = new ListenerRegistry<>();
    private final ListenerRegistry<Exception> errorListeners = new ListenerRegistry<>();
    
    private volatile long pollingIntervalMs;
    private final Object pollingLock = new Object();
    private final AtomicReference<ScheduledFuture<?>> nextPolling = new AtomicReference<>();
    private final AtomicInteger fetchVersion = new AtomicInteger(1);
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicBoolean fetchedOnce = new AtomicBoolean(false);
    private final CompletableFuture<Void> readyFuture = new CompletableFuture<>();
    private final Object fetchLock = new Object();
    private boolean fetching = false;
    private CompletableFuture<Void> fetchingFuture = CompletableFuture.completedFuture(null);
    private volatile Exception error = null;
    
    // Thread-safe config storage: volatile reference swapped atomically on updates
    private volatile Map<String, Flag> config = Collections.emptyMap();

    /**
     * Creates a new TgglClient with the given options.
     */
    public TgglClient(@NotNull TgglClientOptions options) {
        this.apiKey = options.getApiKey();
        this.baseUrls = new ArrayList<>(options.getBaseUrls());
        this.appName = options.getAppName();
        this.maxRetries = options.getMaxRetries();
        this.timeoutMs = options.getTimeoutMs();
        this.storages = new ArrayList<>(options.getStorages());
        this.pollingIntervalMs = options.getPollingIntervalMs();
        
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build();
        this.objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tggl-local-client");
            t.setDaemon(true);
            return t;
        });
        
        this.clientId = "java-client:" + TgglVersion.VERSION + "/TgglClient" + 
            (appName != null ? "/" + appName : "");
        
        // Setup reporting
        this.ownsReporting = (options.getReporting() == null);
        if (options.getReporting() != null) {
            this.reporting = options.getReporting();
        } else if (options.isReportingEnabled()) {
            this.reporting = new TgglReporting(TgglReportingOptions.builder()
                .apiKey(apiKey)
                .baseUrls(baseUrls)
                .flushIntervalMs(10000)
                .build());
        } else {
            this.reporting = new TgglReporting(TgglReportingOptions.builder()
                .apiKey(apiKey)
                .baseUrls(baseUrls)
                .flushIntervalMs(0)
                .build());
        }
        
        // Load from storages
        loadFromStorages();
        
        // Setup storage persistence on config change
        configChangeListeners.addListener(changedFlags -> {
            if (!fetchedOnce.get()) {
                return;
            }
            saveToStorages();
        });
        
        // Start polling or initial fetch
        if (pollingIntervalMs > 0) {
            startPolling(pollingIntervalMs);
        } else if (options.isInitialFetch()) {
            refetch();
        }
    }

    /**
     * Creates a new TgglClient with default options.
     */
    public TgglClient() {
        this(TgglClientOptions.builder().build());
    }

    private void loadFromStorages() {
        AtomicLong latestDate = new AtomicLong(0);
        for (TgglStorage storage : storages) {
            try {
                storage.get().thenAccept(value -> {
                    if (fetchedOnce.get() || value == null) {
                        return;
                    }
                    try {
                        Map<String, Object> parsed = objectMapper.readValue(value, new TypeReference<Map<String, Object>>() {});
                        if (parsed == null || !"TgglClientState".equals(parsed.get("type"))) {
                            return;
                        }
                        long date = ((Number) parsed.getOrDefault("date", 0)).longValue();
                        long previousDate = latestDate.getAndUpdate(
                            current -> date > current ? date : current);
                        if (date > previousDate) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> configMap = (Map<String, Object>) parsed.get("config");
                            if (configMap != null) {
                                setConfigFromMap(configMap);
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Failed to parse stored config: {}", e.getMessage());
                    }
                }).exceptionally(e -> {
                    logger.debug("Failed to load from storage: {}", e.getMessage());
                    return null;
                });
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    private void saveToStorages() {
        try {
            Map<String, Object> state = new HashMap<>();
            state.put("type", "TgglClientState");
            state.put("date", System.currentTimeMillis());
            
            Map<String, Object> configMap = new HashMap<>();
            Map<String, Flag> snapshot = this.config;
            for (Map.Entry<String, Flag> entry : snapshot.entrySet()) {
                configMap.put(entry.getKey(), objectMapper.convertValue(entry.getValue(), Map.class));
            }
            state.put("config", configMap);
            
            String serialized = objectMapper.writeValueAsString(state);
            
            for (TgglStorage storage : storages) {
                try {
                    storage.set(serialized).exceptionally(e -> {
                        logger.debug("Failed to save to storage: {}", e.getMessage());
                        return null;
                    });
                } catch (Exception e) {
                    // Ignore
                }
            }
        } catch (JsonProcessingException e) {
            logger.debug("Failed to serialize config: {}", e.getMessage());
        }
    }

    private void setConfigFromMap(Map<String, Object> configMap) {
        Map<String, Flag> newConfig = new HashMap<>();
        for (Map.Entry<String, Object> entry : configMap.entrySet()) {
            try {
                Flag flag = objectMapper.convertValue(entry.getValue(), Flag.class);
                newConfig.put(entry.getKey(), flag);
            } catch (Exception e) {
                logger.debug("Failed to parse flag {}: {}", entry.getKey(), e.getMessage());
            }
        }
        setConfig(newConfig);
    }

    /**
     * Gets a flag value for the given context with a default fallback.
     *
     * @param context      The evaluation context
     * @param slug         The flag slug
     * @param defaultValue The default value to return if the flag is not found or inactive
     * @param <T>          The type of the flag value
     * @return The flag value or the default value
     */
    @SuppressWarnings("unchecked")
    public <T> T get(@NotNull Map<String, Object> context, @NotNull String slug, T defaultValue) {
        Object value;
        Map<String, Flag> snapshot = this.config;
        Flag flag = snapshot.get(slug);
        
        if (flag == null) {
            value = defaultValue;
        } else {
            value = FlagEvaluator.evalFlag(context, flag);
            if (value == null) {
                value = defaultValue;
            }
        }
        
        // Guard against ClassCastException: if the evaluated value has a different
        // type than the default, return the default to avoid exceptions at call sites.
        if (defaultValue != null && value != null && !defaultValue.getClass().isInstance(value)) {
            value = defaultValue;
        }
        
        reporting.reportFlag(slug, value, defaultValue, clientId);
        
        flagEvalListeners.emit(new FlagEvalEvent(value, defaultValue, slug));
        
        reporting.reportContext(context);
        
        return (T) value;
    }

    /**
     * Gets all flag values for the given context.
     *
     * @param context The evaluation context
     * @return A map of flag slugs to their values (only active flags)
     */
    @NotNull
    public Map<String, Object> getAll(@NotNull Map<String, Object> context) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Flag> snapshot = this.config;
        
        for (Map.Entry<String, Flag> entry : snapshot.entrySet()) {
            Object value = FlagEvaluator.evalFlag(context, entry.getValue());
            if (value != null) {
                result.put(entry.getKey(), value);
            }
        }
        
        reporting.reportContext(context);
        
        return result;
    }

    /**
     * Registers a callback for flag evaluation events.
     *
     * @param callback The callback to invoke
     * @return A Runnable that, when called, will unregister the listener
     */
    public Runnable onFlagEval(@NotNull Consumer<FlagEvalEvent> callback) {
        return flagEvalListeners.addListener(callback);
    }

    /**
     * Registers a callback for successful fetch events.
     *
     * @param callback The callback to invoke
     * @return A Runnable that, when called, will unregister the listener
     */
    public Runnable onFetchSuccessful(@NotNull Runnable callback) {
        return fetchSuccessListeners.addListener(callback);
    }

    /**
     * Creates a static client for a specific context.
     *
     * @param context The evaluation context
     * @return A TgglStaticClient with pre-computed flag values
     */
    @NotNull
    public TgglStaticClient createClientForContext(@NotNull Map<String, Object> context) {
        reporting.reportContext(context);
        
        return new TgglStaticClient.Builder()
            .context(context)
            .flags(getAll(context))
            .appName(appName)
            .reporting(reporting)
            .build();
    }

    /**
     * Registers a callback for config change events.
     *
     * @param callback The callback to invoke with the list of changed flag slugs
     * @return A Runnable that, when called, will unregister the listener
     */
    public Runnable onConfigChange(@NotNull Consumer<List<String>> callback) {
        return configChangeListeners.addListener(callback);
    }

    /**
     * Starts polling for config updates.
     *
     * @param pollingIntervalMs The interval in milliseconds between fetches
     */
    public void startPolling(long pollingIntervalMs) {
        boolean shouldRefetch = false;
        synchronized (pollingLock) {
            if (this.pollingIntervalMs == 0 && pollingIntervalMs > 0) {
                this.pollingIntervalMs = pollingIntervalMs;
                shouldRefetch = true;
            } else if (this.pollingIntervalMs > 0 && pollingIntervalMs <= 0) {
                this.pollingIntervalMs = 0;
                ScheduledFuture<?> current = nextPolling.getAndSet(null);
                if (current != null) {
                    current.cancel(false);
                }
            } else {
                this.pollingIntervalMs = Math.max(0, pollingIntervalMs);
            }
        }
        if (shouldRefetch) {
            refetch();
        }
    }

    /**
     * Stops polling for config updates.
     */
    public void stopPolling() {
        startPolling(0);
    }

    /**
     * Gets the current config.
     *
     * @return A copy of the current config map
     */
    @NotNull
    public Map<String, Flag> getConfig() {
        return new HashMap<>(this.config);
    }

    /**
     * Sets the config.
     *
     * @param newConfig The new config map
     */
    public void setConfig(@NotNull Map<String, Flag> newConfig) {
        error = null;
        ready.set(true);
        
        Map<String, Flag> oldConfig = this.config;
        
        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(oldConfig.keySet());
        allKeys.addAll(newConfig.keySet());
        
        List<String> changedFlags = new ArrayList<>();
        for (String key : allKeys) {
            Flag oldFlag = oldConfig.get(key);
            Flag newFlag = newConfig.get(key);
            
            if (oldFlag == null || newFlag == null || !oldFlag.equals(newFlag)) {
                changedFlags.add(key);
            }
        }
        
        // Atomic swap: readers always see a consistent snapshot
        this.config = Collections.unmodifiableMap(new HashMap<>(newConfig));
        
        if (!changedFlags.isEmpty()) {
            configChangeListeners.emit(changedFlags);
        }
        
        if (!readyFuture.isDone()) {
            readyFuture.complete(null);
        }
    }

    /**
     * Refetches the config from the API.
     *
     * @return A CompletableFuture that completes when the fetch is done
     */
    public CompletableFuture<Void> refetch() {
        ScheduledFuture<?> current = nextPolling.getAndSet(null);
        if (current != null) {
            current.cancel(false);
        }
        
        final CompletableFuture<Void> future;
        synchronized (fetchLock) {
            if (fetching) {
                return fetchingFuture;
            }
            fetching = true;
            future = new CompletableFuture<>();
            fetchingFuture = future;
        }
        
        int version = fetchVersion.incrementAndGet();
        
        CompletableFuture.runAsync(() -> {
            try {
                fetchConfig(version);
            } finally {
                if (version == fetchVersion.get()) {
                    ready.set(true);
                    if (!readyFuture.isDone()) {
                        readyFuture.complete(null);
                    }
                    synchronized (fetchLock) {
                        fetching = false;
                    }
                    future.complete(null);
                    scheduleNextPolling();
                }
            }
        }, scheduler);
        
        return future;
    }

    private void fetchConfig(int version) {
        Request.Builder requestBuilder = new Request.Builder()
            .get();
        
        if (apiKey != null) {
            requestBuilder.addHeader("x-tggl-api-key", apiKey);
        }
        requestBuilder.addHeader("Content-Type", "application/json");
        
        Exception lastError = null;
        List<Flag> response = null;
        
        for (String baseUrl : baseUrls) {
            for (int retry = 0; retry <= maxRetries; retry++) {
                try {
                    Request request = requestBuilder.url(baseUrl + "/config").build();
                    
                    try (Response httpResponse = httpClient.newCall(request).execute()) {
                        if (version != fetchVersion.get()) {
                            return;
                        }
                        
                        if (httpResponse.isSuccessful() && httpResponse.body() != null) {
                            String body = httpResponse.body().string();
                            response = objectMapper.readValue(body, new TypeReference<List<Flag>>() {});
                            break;
                        } else {
                            String errorBody = httpResponse.body() != null ? httpResponse.body().string() : "";
                            try {
                                Map<String, Object> errorJson = objectMapper.readValue(errorBody, new TypeReference<Map<String, Object>>() {});
                                if (errorJson.containsKey("error")) {
                                    lastError = new IOException(errorJson.get("error").toString());
                                } else {
                                    lastError = new IOException("HTTP " + httpResponse.code());
                                }
                            } catch (Exception e) {
                                lastError = new IOException("HTTP " + httpResponse.code());
                            }
                        }
                    }
                } catch (IOException e) {
                    lastError = e;
                    // Exponential backoff
                    if (retry < maxRetries) {
                        try {
                            Thread.sleep(Math.min(500, (long) Math.pow(2, retry) * 100));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }
            
            if (response != null) {
                break;
            }
        }
        
        if (version != fetchVersion.get()) {
            return;
        }
        
        if (response != null && !(response instanceof List)) {
            response = null;
            lastError = new IOException("Invalid response from Tggl, malformed config");
        }
        
        if (response == null) {
            error = lastError;
            if (lastError != null) {
                errorListeners.emit(lastError);
            }
        } else {
            fetchedOnce.set(true);
            Map<String, Flag> newConfig = new HashMap<>();
            for (Flag flag : response) {
                if (flag.getSlug() != null) {
                    newConfig.put(flag.getSlug(), flag);
                }
            }
            setConfig(newConfig);
            fetchSuccessListeners.emit();
        }
    }

    private void scheduleNextPolling() {
        if (pollingIntervalMs <= 0) {
            return;
        }
        
        ScheduledFuture<?> future = scheduler.schedule(() -> refetch(), pollingIntervalMs, TimeUnit.MILLISECONDS);
        nextPolling.set(future);
    }

    /**
     * Closes the client and releases all resources.
     */
    @Override
    public void close() {
        stopPolling();

        // Close reporting only if we own it (flushes with timeout internally)
        if (ownsReporting) {
            reporting.close();
        }

        for (TgglStorage storage : storages) {
            try {
                storage.close().get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.debug("Error closing storage: {}", e.getMessage());
            }
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

    /**
     * Returns whether the client is ready (has fetched config at least once or loaded from storage).
     */
    public boolean isReady() {
        return ready.get();
    }

    /**
     * Returns a CompletableFuture that completes when the client is ready.
     */
    @NotNull
    public CompletableFuture<Void> waitReady() {
        return readyFuture;
    }

    /**
     * Registers a callback to be called when the client is ready.
     *
     * @param callback The callback to invoke
     */
    public void onReady(@NotNull Runnable callback) {
        if (ready.get()) {
            callback.run();
        } else {
            readyFuture.thenRun(callback);
        }
    }

    /**
     * Returns the last error that occurred during fetch.
     */
    @Nullable
    public Exception getError() {
        return error;
    }

    /**
     * Registers a callback for error events.
     *
     * @param callback The callback to invoke
     * @return A Runnable that, when called, will unregister the listener
     */
    public Runnable onError(@NotNull Consumer<Exception> callback) {
        return errorListeners.addListener(callback);
    }

    /**
     * Gets the reporting instance.
     */
    @NotNull
    public TgglReporting getReporting() {
        return reporting;
    }

    /**
     * Event data for flag evaluation.
     */
    public static final class FlagEvalEvent {
        @Nullable
        private final Object value;
        @Nullable
        private final Object defaultValue;
        @NotNull
        private final String slug;

        public FlagEvalEvent(@Nullable Object value, @Nullable Object defaultValue, @NotNull String slug) {
            this.value = value;
            this.defaultValue = defaultValue;
            this.slug = slug;
        }

        @Nullable
        public Object value() {
            return value;
        }

        @Nullable
        public Object defaultValue() {
            return defaultValue;
        }

        @NotNull
        public String slug() {
            return slug;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FlagEvalEvent)) return false;
            FlagEvalEvent that = (FlagEvalEvent) o;
            return Objects.equals(value, that.value)
                && Objects.equals(defaultValue, that.defaultValue)
                && slug.equals(that.slug);
        }

        @Override
        public int hashCode() {
            return Objects.hash(value, defaultValue, slug);
        }

        @Override
        public String toString() {
            return "FlagEvalEvent[value=" + value + ", defaultValue=" + defaultValue + ", slug=" + slug + "]";
        }
    }
}
