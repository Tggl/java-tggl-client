package io.tggl.storage;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * Interface for storing and retrieving Tggl client state.
 * Implementations can provide persistence for flag configurations
 * to enable faster startup times and offline support.
 */
public interface TgglStorage {

    /**
     * Retrieves the stored value.
     *
     * @return A CompletableFuture that resolves to the stored string value, or null if not found
     */
    CompletableFuture<@Nullable String> get();

    /**
     * Stores a value.
     *
     * @param value The string value to store
     * @return A CompletableFuture that completes when the value is stored
     */
    CompletableFuture<Void> set(String value);

    /**
     * Closes the storage, releasing any resources.
     * This method is optional - implementations may choose not to implement it.
     *
     * @return A CompletableFuture that completes when the storage is closed
     */
    default CompletableFuture<Void> close() {
        return CompletableFuture.completedFuture(null);
    }
}
