package io.tggl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * A client that sends a context to the Tggl API and receives pre-evaluated
 * flag values. Unlike {@link TgglClient} which fetches config and evaluates
 * flags locally, this client delegates evaluation to the server.
 * <p>
 * Usage in Java:
 * <pre>{@code
 * TgglRemoteClient client = new TgglRemoteClient(TgglRemoteClientOptions.builder()
 *     .apiKey("your-api-key")
 *     .build());
 *
 * // Set context and fetch flags
 * client.setContext(Map.of("userId", "123")).join();
 *
 * // Read flag values
 * boolean value = client.get("myFlag", false);
 * }</pre>
 */
public class TgglRemoteClient implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(TgglRemoteClient.class);
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

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

    private final ListenerRegistry<FlagEvalEvent> flagEvalListeners = new ListenerRegistry<>();
    private final RunnableRegistry fetchSuccessListeners = new RunnableRegistry();
    private final ListenerRegistry<List<String>> flagsChangeListeners = new ListenerRegistry<>();
    private final ListenerRegistry<Exception> errorListeners = new ListenerRegistry<>();

    private volatile long pollingIntervalMs;
    private final Object pollingLock = new Object();
    private final AtomicReference<ScheduledFuture<?>> nextPolling = new AtomicReference<>();
    private final AtomicInteger contextVersion = new AtomicInteger(1);
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicBoolean fetchedOnce = new AtomicBoolean(false);
    private final CompletableFuture<Void> readyFuture = new CompletableFuture<>();
    private final Object fetchLock = new Object();
    private boolean fetching = false;
    private CompletableFuture<Void> fetchingFuture = CompletableFuture.completedFuture(null);
    private volatile Exception error = null;

    private volatile Map<String, Object> context;
    private volatile Map<String, Object> flags = Collections.emptyMap();

    /**
     * Creates a new TgglRemoteClient with the given options.
     */
    public TgglRemoteClient(@NotNull TgglRemoteClientOptions options) {
        this.apiKey = options.getApiKey();
        this.baseUrls = new ArrayList<>(options.getBaseUrls());
        this.appName = options.getAppName();
        this.maxRetries = options.getMaxRetries();
        this.timeoutMs = options.getTimeoutMs();
        this.storages = new ArrayList<>(options.getStorages());
        this.pollingIntervalMs = options.getPollingIntervalMs();
        this.context = new HashMap<>(options.getInitialContext());

        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build();
        this.objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tggl-remote-client");
            t.setDaemon(true);
            return t;
        });

        this.clientId = "java-client:" + TgglVersion.VERSION + "/TgglRemoteClient" +
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

        // Setup storage persistence on flags change
        flagsChangeListeners.addListener(changedFlags -> {
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
     * Creates a new TgglRemoteClient with default options.
     */
    public TgglRemoteClient() {
        this(TgglRemoteClientOptions.builder().build());
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
                        if (parsed == null || !"TgglRemoteClientState".equals(parsed.get("type"))) {
                            return;
                        }
                        long date = ((Number) parsed.getOrDefault("date", 0)).longValue();
                        long previousDate = latestDate.getAndUpdate(
                            current -> date > current ? date : current);
                        if (date > previousDate) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> storedFlags = (Map<String, Object>) parsed.get("flags");
                            if (storedFlags != null) {
                                setFlags(storedFlags);
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Failed to parse stored flags: {}", e.getMessage());
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
            state.put("type", "TgglRemoteClientState");
            state.put("date", System.currentTimeMillis());
            state.put("flags", new HashMap<>(this.flags));

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
            logger.debug("Failed to serialize flags: {}", e.getMessage());
        }
    }

    /**
     * Sets the context and fetches flag values from the server.
     * If called multiple times in quick succession, only the response for the
     * latest context will be applied.
     *
     * @param context The evaluation context to send to the server
     * @return A CompletableFuture that completes when the fetch is done
     */
    public CompletableFuture<Void> setContext(@NotNull Map<String, Object> context) {
        ScheduledFuture<?> current = nextPolling.getAndSet(null);
        if (current != null) {
            current.cancel(false);
        }

        final CompletableFuture<Void> future;
        synchronized (fetchLock) {
            if (!fetching) {
                fetching = true;
                future = new CompletableFuture<>();
                fetchingFuture = future;
            } else {
                future = fetchingFuture;
            }
        }

        int version = contextVersion.incrementAndGet();
        Map<String, Object> contextCopy = new HashMap<>(context);

        CompletableFuture.runAsync(() -> {
            try {
                fetchFlags(contextCopy, version);
            } finally {
                if (version == contextVersion.get()) {
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

    private void fetchFlags(Map<String, Object> context, int version) {
        String postData;
        try {
            postData = objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException e) {
            error = e;
            errorListeners.emit(e);
            return;
        }

        RequestBody requestBody = RequestBody.create(postData, JSON_MEDIA_TYPE);

        Exception lastError = null;
        Map<String, Object> response = null;

        for (String baseUrl : baseUrls) {
            for (int retry = 0; retry <= maxRetries; retry++) {
                try {
                    Request.Builder requestBuilder = new Request.Builder()
                        .url(baseUrl + "/flags")
                        .post(requestBody);

                    if (apiKey != null) {
                        requestBuilder.addHeader("x-tggl-api-key", apiKey);
                    }
                    requestBuilder.addHeader("Content-Type", "application/json");

                    try (Response httpResponse = httpClient.newCall(requestBuilder.build()).execute()) {
                        if (version != contextVersion.get()) {
                            return;
                        }

                        if (httpResponse.isSuccessful() && httpResponse.body() != null) {
                            String body = httpResponse.body().string();
                            response = objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
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

        if (version != contextVersion.get()) {
            return;
        }

        if (response == null) {
            error = lastError;
            if (lastError != null) {
                errorListeners.emit(lastError);
            }
        } else {
            fetchedOnce.set(true);
            this.context = new HashMap<>(context);
            setFlags(response);
            fetchSuccessListeners.emit();
        }
    }

    /**
     * Sets flags directly and emits change events for any flags that changed.
     *
     * @param newFlags The new flag values
     */
    public void setFlags(@NotNull Map<String, Object> newFlags) {
        error = null;
        ready.set(true);

        Map<String, Object> oldFlags = this.flags;

        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(oldFlags.keySet());
        allKeys.addAll(newFlags.keySet());

        List<String> changedFlags = new ArrayList<>();
        for (String key : allKeys) {
            Object oldValue = oldFlags.get(key);
            Object newValue = newFlags.get(key);

            if (!deepEquals(oldValue, newValue)) {
                changedFlags.add(key);
            }
        }

        this.flags = Collections.unmodifiableMap(new HashMap<>(newFlags));

        if (!changedFlags.isEmpty()) {
            flagsChangeListeners.emit(changedFlags);
        }

        if (!readyFuture.isDone()) {
            readyFuture.complete(null);
        }
    }

    private boolean deepEquals(Object a, Object b) {
        if (Objects.equals(a, b)) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        try {
            return objectMapper.writeValueAsString(a).equals(objectMapper.writeValueAsString(b));
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    /**
     * Gets a flag value with a default fallback.
     *
     * @param slug         The flag slug
     * @param defaultValue The default value to return if the flag is not found
     * @param <T>          The type of the flag value
     * @return The flag value or the default value
     */
    @SuppressWarnings("unchecked")
    public <T> T get(@NotNull String slug, T defaultValue) {
        Map<String, Object> snapshot = this.flags;
        Object value = snapshot.containsKey(slug) ? snapshot.get(slug) : defaultValue;

        if (defaultValue != null && value != null && !defaultValue.getClass().isInstance(value)) {
            value = defaultValue;
        }

        reporting.reportFlag(slug, value, defaultValue, clientId);

        flagEvalListeners.emit(new FlagEvalEvent(value, defaultValue, slug));

        return (T) value;
    }

    /**
     * Gets all flag values.
     *
     * @return A copy of the flags map
     */
    @NotNull
    public Map<String, Object> getAll() {
        return new HashMap<>(this.flags);
    }

    /**
     * Gets the current context.
     *
     * @return A copy of the context map
     */
    @NotNull
    public Map<String, Object> getContext() {
        return new HashMap<>(this.context);
    }

    /**
     * Refetches flags by re-sending the current context.
     *
     * @return A CompletableFuture that completes when the fetch is done
     */
    public CompletableFuture<Void> refetch() {
        return setContext(getContext());
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
     * Registers a callback for flags change events.
     *
     * @param callback The callback to invoke with the list of changed flag slugs
     * @return A Runnable that, when called, will unregister the listener
     */
    public Runnable onFlagsChange(@NotNull Consumer<List<String>> callback) {
        return flagsChangeListeners.addListener(callback);
    }

    /**
     * Starts polling for flag updates by re-sending the current context.
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
     * Stops polling for flag updates.
     */
    public void stopPolling() {
        startPolling(0);
    }

    private void scheduleNextPolling() {
        if (pollingIntervalMs <= 0) {
            return;
        }

        ScheduledFuture<?> future = scheduler.schedule(() -> refetch(), pollingIntervalMs, TimeUnit.MILLISECONDS);
        nextPolling.set(future);
    }

    /**
     * Returns whether the client is ready (has received flags at least once or loaded from storage).
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
     * Closes the client and releases all resources.
     */
    @Override
    public void close() {
        stopPolling();

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
