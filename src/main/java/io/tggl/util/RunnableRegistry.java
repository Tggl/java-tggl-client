package io.tggl.util;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A thread-safe registry for managing Runnable listeners (events with no arguments).
 * Provides type-safe listener registration, removal, and event emission.
 */
public class RunnableRegistry {
    
    private static final Logger logger = LoggerFactory.getLogger(RunnableRegistry.class);
    
    private final Map<Long, Runnable> listeners = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(0);

    /**
     * Registers a listener that will be called when events are emitted.
     *
     * @param listener The listener to register
     * @return A Runnable that, when called, will unregister the listener
     */
    @NotNull
    public Runnable addListener(@NotNull Runnable listener) {
        long id = nextId.incrementAndGet();
        listeners.put(id, listener);
        return () -> listeners.remove(id);
    }

    /**
     * Emits an event to all registered listeners.
     * If a listener throws an exception, it is logged and other listeners continue to be notified.
     */
    public void emit() {
        for (Runnable listener : listeners.values()) {
            try {
                listener.run();
            } catch (Exception e) {
                logger.debug("Error in event listener: {}", e.getMessage());
            }
        }
    }

    /**
     * Removes all registered listeners.
     */
    public void clear() {
        listeners.clear();
    }

    /**
     * Returns the number of registered listeners.
     *
     * @return The listener count
     */
    public int size() {
        return listeners.size();
    }

    /**
     * Returns whether any listeners are registered.
     *
     * @return true if there are listeners, false otherwise
     */
    public boolean hasListeners() {
        return !listeners.isEmpty();
    }
}
