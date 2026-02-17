package io.tggl;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Configuration options for TgglReporting.
 */
public class TgglReportingOptions {
    
    @Nullable
    private final String apiKey;
    
    @NotNull
    private final List<String> baseUrls;
    
    private final long flushIntervalMs;

    private TgglReportingOptions(Builder builder) {
        this.apiKey = builder.apiKey;
        List<String> urls = new ArrayList<>(builder.baseUrls);
        if (!urls.contains("https://api.tggl.io")) {
            urls.add("https://api.tggl.io");
        }
        this.baseUrls = Collections.unmodifiableList(urls);
        this.flushIntervalMs = builder.flushIntervalMs;
    }

    @Nullable
    public String getApiKey() {
        return apiKey;
    }

    @NotNull
    public List<String> getBaseUrls() {
        return baseUrls;
    }

    public long getFlushIntervalMs() {
        return flushIntervalMs;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String apiKey = null;
        private List<String> baseUrls = new ArrayList<>();
        private long flushIntervalMs = 5000;

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

        public Builder flushIntervalMs(long flushIntervalMs) {
            this.flushIntervalMs = flushIntervalMs;
            return this;
        }

        public TgglReportingOptions build() {
            return new TgglReportingOptions(this);
        }
    }
}
