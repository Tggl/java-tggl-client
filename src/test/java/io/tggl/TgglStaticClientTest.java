package io.tggl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TgglStaticClientTest {

    private TgglStaticClient client;

    @BeforeEach
    void setUp() {
        client = new TgglStaticClient.Builder()
            .flags(Map.of(
                "boolFlag", true,
                "stringFlag", "value",
                "numberFlag", 42
            ))
            .context(Map.of("userId", "123"))
            .build();
    }

    @Test
    @DisplayName("should return flag value when flag exists")
    void shouldReturnFlagValueWhenExists() {
        assertTrue(client.get("boolFlag", false));
        assertEquals("value", client.get("stringFlag", "default"));
        assertEquals(42, client.get("numberFlag", 0));
    }

    @Test
    @DisplayName("should return default value when flag does not exist")
    void shouldReturnDefaultWhenNotExists() {
        assertFalse(client.get("unknownFlag", false));
        assertEquals("default", client.get("unknownFlag", "default"));
    }

    @Test
    @DisplayName("should return all flags")
    void shouldReturnAllFlags() {
        Map<String, Object> all = client.getAll();
        assertEquals(3, all.size());
        assertTrue((Boolean) all.get("boolFlag"));
    }

    @Test
    @DisplayName("should return context")
    void shouldReturnContext() {
        Map<String, Object> context = client.getContext();
        assertEquals("123", context.get("userId"));
    }

    @Test
    @DisplayName("should emit flagEval event")
    void shouldEmitFlagEvalEvent() {
        AtomicReference<TgglStaticClient.FlagEvalEvent> receivedEvent = new AtomicReference<>();
        
        client.onFlagEval(receivedEvent::set);
        client.get("boolFlag", false);
        
        assertNotNull(receivedEvent.get());
        assertEquals("boolFlag", receivedEvent.get().slug());
        assertEquals(true, receivedEvent.get().value());
        assertEquals(false, receivedEvent.get().defaultValue());
    }

    @Test
    @DisplayName("should allow unregistering event listener")
    void shouldAllowUnregisteringListener() {
        AtomicReference<TgglStaticClient.FlagEvalEvent> receivedEvent = new AtomicReference<>();
        
        Runnable unregister = client.onFlagEval(receivedEvent::set);
        unregister.run();
        
        client.get("boolFlag", false);
        
        assertNull(receivedEvent.get());
    }
}
