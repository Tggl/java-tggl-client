package io.tggl.util;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * A thread-safe registry for managing typed event listeners.
 * Provides type-safe listener registration, removal, and event emission.
 *
 * @param <T> The event type that listeners will receive
 */
public class ListenerRegistry<T> {
    
    private static final Logger logger = LoggerFactory.getLogger(ListenerRegistry.class);
    
    private final Map<Long, Consumer<T>> listeners = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(0);

    /**
     * Registers a listener that will be called when events are emitted.
     *
     * @param listener The listener to register
     * @return A Runnable that, when called, will unregister the listener
     */
    @NotNull
    public Runnable addListener(@NotNull Consumer<T> listener) {
        long id = nextId.incrementAndGet();
        listeners.put(id, listener);
        return () -> listeners.remove(id);
    }

    /**
     * Emits an event to all registered listeners.
     * If a listener throws an exception, it is logged and other listeners continue to be notified.
     *
     * @param event The event to emit
     */
    public void emit(@NotNull T event) {
        for (Consumer<T> listener : listeners.values()) {
            try {
                listener.accept(event);
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
