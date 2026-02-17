package io.tggl;

import io.tggl.util.ListenerRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A static client that holds pre-computed flag values.
 * This is the base class for other Tggl clients.
 * <p>
 * Usage in Java:
 * <pre>{@code
 * TgglStaticClient client = new TgglStaticClient.Builder()
 *     .flags(Map.of("myFlag", true))
 *     .reporting(reporting)
 *     .build();
 * 
 * boolean value = client.get("myFlag", false);
 * }</pre>
 * <p>
 * Usage in Kotlin:
 * <pre>{@code
 * val client = TgglStaticClient.Builder()
 *     .flags(mapOf("myFlag" to true))
 *     .reporting(reporting)
 *     .build()
 * 
 * val value = client.get("myFlag", false)
 * }</pre>
 */
public class TgglStaticClient {

    private final Map<String, Object> context;
    private final Map<String, Object> flags;
    private final TgglReporting reporting;
    private final String clientId;

    private final ListenerRegistry<FlagEvalEvent> flagEvalListeners = new ListenerRegistry<>();

    protected TgglStaticClient(
        @NotNull Map<String, Object> context,
        @NotNull Map<String, Object> flags,
        @NotNull TgglReporting reporting,
        @Nullable String appName
    ) {
        this.context = new HashMap<>(context);
        this.flags = new HashMap<>(flags);
        this.reporting = reporting;
        String id = "java-client:" + TgglVersion.VERSION + "/TgglStaticClient";
        if (appName != null && !appName.isEmpty()) {
            id += "/" + appName;
        }
        this.clientId = id;
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
        Object value = flags.containsKey(slug) ? flags.get(slug) : defaultValue;
        
        // Guard against ClassCastException: if the flag value has a different
        // type than the default, return the default to avoid exceptions at call sites.
        if (defaultValue != null && value != null && !defaultValue.getClass().isInstance(value)) {
            value = defaultValue;
        }
        
        reporting.reportFlag(slug, value, defaultValue, clientId);
        
        flagEvalListeners.emit(new FlagEvalEvent(value, defaultValue, slug));
        
        return (T) value;
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
     * Gets all flag values.
     *
     * @return A copy of the flags map
     */
    @NotNull
    public Map<String, Object> getAll() {
        return new HashMap<>(flags);
    }

    /**
     * Gets the current context.
     *
     * @return A copy of the context map
     */
    @NotNull
    public Map<String, Object> getContext() {
        return new HashMap<>(context);
    }

    /**
     * Gets the reporting instance.
     *
     * @return The TgglReporting instance
     */
    @NotNull
    public TgglReporting getReporting() {
        return reporting;
    }

    /**
     * Event data for flag evaluation.
     */
    public record FlagEvalEvent(
        @Nullable Object value,
        @Nullable Object defaultValue,
        @NotNull String slug
    ) {}

    /**
     * Builder for TgglStaticClient.
     */
    public static class Builder {
        private Map<String, Object> context = new HashMap<>();
        private Map<String, Object> flags = new HashMap<>();
        private TgglReporting reporting;
        private String appName;

        public Builder context(@NotNull Map<String, Object> context) {
            this.context = new HashMap<>(context);
            return this;
        }

        public Builder flags(@NotNull Map<String, Object> flags) {
            this.flags = new HashMap<>(flags);
            return this;
        }

        public Builder reporting(@NotNull TgglReporting reporting) {
            this.reporting = reporting;
            return this;
        }

        public Builder appName(@Nullable String appName) {
            this.appName = appName;
            return this;
        }

        public TgglStaticClient build() {
            if (reporting == null) {
                reporting = new TgglReporting(TgglReportingOptions.builder().flushIntervalMs(0).build());
            }
            return new TgglStaticClient(context, flags, reporting, appName);
        }
    }
}
