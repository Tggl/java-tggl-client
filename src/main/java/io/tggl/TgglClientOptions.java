package io.tggl;

import io.tggl.storage.TgglStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Configuration options for TgglClient.
 */
public class TgglClientOptions {
    
    @Nullable
    private final String apiKey;
    
    @NotNull
    private final List<String> baseUrls;
    
    private final int maxRetries;
    private final long timeoutMs;
    private final long pollingIntervalMs;
    
    @NotNull
    private final List<TgglStorage> storages;
    
    @Nullable
    private final TgglReporting reporting;
    
    private final boolean reportingEnabled;
    
    @Nullable
    private final String appName;
    
    private final boolean initialFetch;

    private TgglClientOptions(Builder builder) {
        this.apiKey = builder.apiKey;
        List<String> urls = new ArrayList<>(builder.baseUrls);
        if (!urls.contains("https://api.tggl.io")) {
            urls.add("https://api.tggl.io");
        }
        this.baseUrls = Collections.unmodifiableList(urls);
        this.maxRetries = builder.maxRetries;
        this.timeoutMs = builder.timeoutMs;
        this.pollingIntervalMs = builder.pollingIntervalMs;
        this.storages = Collections.unmodifiableList(new ArrayList<>(builder.storages));
        this.reporting = builder.reporting;
        this.reportingEnabled = builder.reportingEnabled;
        this.appName = builder.appName;
        this.initialFetch = builder.initialFetch;
    }

    @Nullable
    public String getApiKey() {
        return apiKey;
    }

    @NotNull
    public List<String> getBaseUrls() {
        return baseUrls;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public long getPollingIntervalMs() {
        return pollingIntervalMs;
    }

    @NotNull
    public List<TgglStorage> getStorages() {
        return storages;
    }

    @Nullable
    public TgglReporting getReporting() {
        return reporting;
    }

    public boolean isReportingEnabled() {
        return reportingEnabled;
    }

    @Nullable
    public String getAppName() {
        return appName;
    }

    public boolean isInitialFetch() {
        return initialFetch;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String apiKey = null;
        private List<String> baseUrls = new ArrayList<>();
        private int maxRetries = 3;
        private long timeoutMs = 8000;
        private long pollingIntervalMs = 5000;
        private List<TgglStorage> storages = new ArrayList<>();
        private TgglReporting reporting = null;
        private boolean reportingEnabled = true;
        private String appName = null;
        private boolean initialFetch = true;

        public Builder apiKey(@Nullable String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder baseUrls(@NotNull List<String> baseUrls) {
            this.baseUrls = new ArrayList<>(baseUrls);
            return this;
        }

        public Builder addBaseUrl(@NotNull String baseUrl) {
            this.baseUrls.add(baseUrl);
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder timeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        public Builder pollingIntervalMs(long pollingIntervalMs) {
            this.pollingIntervalMs = pollingIntervalMs;
            return this;
        }

        public Builder storages(@NotNull List<TgglStorage> storages) {
            this.storages = new ArrayList<>(storages);
            return this;
        }

        public Builder addStorage(@NotNull TgglStorage storage) {
            this.storages.add(storage);
            return this;
        }

        public Builder reporting(@Nullable TgglReporting reporting) {
            this.reporting = reporting;
            return this;
        }

        public Builder reportingEnabled(boolean reportingEnabled) {
            this.reportingEnabled = reportingEnabled;
            return this;
        }

        public Builder appName(@Nullable String appName) {
            this.appName = appName;
            return this;
        }

        public Builder initialFetch(boolean initialFetch) {
            this.initialFetch = initialFetch;
            return this;
        }

        public TgglClientOptions build() {
            if (timeoutMs < 0) {
                throw new IllegalArgumentException("timeoutMs must be non-negative, got: " + timeoutMs);
            }
            if (pollingIntervalMs < 0) {
                throw new IllegalArgumentException("pollingIntervalMs must be non-negative, got: " + pollingIntervalMs);
            }
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries must be non-negative, got: " + maxRetries);
            }
            return new TgglClientOptions(this);
        }
    }
}
