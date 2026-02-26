<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://tggl.io/tggl-io-logo-white.svg">
    <img align="center" alt="Tggl Logo" src="https://tggl.io/tggl-io-logo-black.svg" width="200rem" />
  </picture>
</p>

<h1 align="center">Tggl Java / Kotlin SDK</h1>

<p align="center">
  The Java / Kotlin SDK can be used both on the server or the client to evaluate flags and report usage to the Tggl API or a <a href="https://tggl.io/developers/evaluating-flags/tggl-proxy">proxy</a>.
</p>

<p align="center">
  <a href="https://tggl.io/">🔗 Website</a>
  •
  <a href="https://tggl.io/developers/sdks/java">📚 Documentation</a>
  •
  <a href="https://central.sonatype.com/artifact/io.tggl/tggl-client">📦 Maven Central</a>
  •
  <a href="https://www.youtube.com/@Tggl-io">🎥 Videos</a>
</p>

<p align="center">
  <img src="https://img.shields.io/github/actions/workflow/status/Tggl/java-tggl-client/ci.yml" alt="GitHub Workflow Status (with event)" />
  <img src="https://img.shields.io/maven-central/v/io.tggl/tggl-client" alt="Maven Central" />
</p>

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("io.tggl:tggl-client:1.0.0")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'io.tggl:tggl-client:1.0.0'
}
```

### Maven

```xml
<dependency>
    <groupId>io.tggl</groupId>
    <artifactId>tggl-client</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Quick Start

The `TgglClient` fetches the flag configuration once and evaluates flags locally, providing the best performance.

### Java

```java
import io.tggl.TgglClient;
import io.tggl.TgglClientOptions;
import java.util.HashMap;
import java.util.Map;

// Create client
TgglClient client = new TgglClient(TgglClientOptions.builder()
    .apiKey("your-server-api-key")
    .pollingIntervalMs(5000) // Refresh config every 5 seconds
    .build());

// Wait for initial fetch
client.waitReady().join();

// Evaluate flags for a user
Map<String, Object> context = new HashMap<>();
context.put("userId", "user-123");
context.put("email", "user@example.com");
context.put("country", "US");

boolean showFeature = client.get(context, "newFeature", false);
String variant = client.get(context, "experimentVariant", "control");

// Get all active flags for a context
Map<String, Object> allFlags = client.getAll(context);

// Don't forget to close when done
client.close();
```

### Kotlin

```kotlin
import io.tggl.TgglClient
import io.tggl.TgglClientOptions

// Create client
val client = TgglClient(TgglClientOptions.builder()
    .apiKey("your-server-api-key")
    .pollingIntervalMs(5000)
    .build())

// Wait for initial fetch
client.waitReady().join()

// Evaluate flags for a user
val context = mapOf(
    "userId" to "user-123",
    "email" to "user@example.com",
    "country" to "US"
)

val showFeature: Boolean = client.get(context, "newFeature", false)
val variant: String = client.get(context, "experimentVariant", "control")

// Get all active flags
val allFlags = client.getAll(context)

// Close when done
client.close()
```

## Event Listeners

Both clients support event listeners for various lifecycle events.

### Java

```java
// Listen for flag changes
client.onFlagsChange(changedFlags -> {
    System.out.println("Flags changed: " + changedFlags);
});

// Listen for a specific flag change
client.onFlagChange("myFlag", () -> {
    System.out.println("myFlag changed!");
});

// Listen for errors
client.onError(error -> {
    System.err.println("Error: " + error.getMessage());
});

// Listen for successful fetches
client.onFetchSuccessful(() -> {
    System.out.println("Config fetched successfully");
});

// Listen for flag evaluations
client.onFlagEval(event -> {
    System.out.println("Flag " + event.slug() + " evaluated to " + event.value());
});

// Unregister a listener
Runnable unregister = client.onError(e -> {});
unregister.run(); // Stop listening
```

### Kotlin

```kotlin
// Listen for flag changes
client.onFlagsChange { changedFlags ->
    println("Flags changed: $changedFlags")
}

// Listen for a specific flag change
client.onFlagChange("myFlag") {
    println("myFlag changed!")
}

// Listen for errors
client.onError { error ->
    println("Error: ${error.message}")
}

// Listen for successful fetches
client.onFetchSuccessful {
    println("Config fetched successfully")
}

// Unregister a listener
val unregister = client.onError { }
unregister() // Stop listening
```

## Static Client

If you have pre-computed flag values (e.g., from server-side rendering), you can use `TgglStaticClient`:

```java
TgglStaticClient staticClient = new TgglStaticClient.Builder()
    .flags(Collections.singletonMap("feature1", true))
    .context(Collections.singletonMap("userId", "123"))
    .build();

boolean feature = staticClient.get("feature1", false);
```

You can also create a static client from a local client:

```java
TgglStaticClient staticClient = localClient.createClientForContext(context);
```

## Configuration Options

### TgglClientOptions

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `apiKey` | String | null | Your Tggl server API key |
| `baseUrls` | List<String> | ["https://api.tggl.io"] | API base URLs (for failover) |
| `maxRetries` | int | 3 | Number of retry attempts |
| `timeoutMs` | long | 8000 | Request timeout in milliseconds |
| `pollingIntervalMs` | long | 5000 | Config refresh interval (0 to disable) |
| `storages` | List<TgglStorage> | [] | Storage backends for persistence |
| `reportingEnabled` | boolean | true | Enable/disable analytics reporting |
| `appName` | String | null | Application name for analytics |
| `initialFetch` | boolean | true | Fetch config on initialization |

## Custom Storage

Implement the `TgglStorage` interface to persist flag configuration:

```java
import io.tggl.storage.TgglStorage;
import java.util.concurrent.CompletableFuture;

public class MyStorage implements TgglStorage {
    @Override
    public CompletableFuture<String> get() {
        // Load from your storage
        return CompletableFuture.completedFuture(loadFromStorage());
    }

    @Override
    public CompletableFuture<Void> set(String value) {
        // Save to your storage
        saveToStorage(value);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> close() {
        // Cleanup if needed
        return CompletableFuture.completedFuture(null);
    }
}

// Use with client
TgglClient client = new TgglClient(TgglClientOptions.builder()
    .apiKey("your-api-key")
    .addStorage(new MyStorage())
    .build());
```

## Thread Safety

All clients are thread-safe and can be shared across threads. Use a single instance per application.

## Requirements

- Java 8 or higher
- Android API 24+ (Android 7.0+), with core library desugaring recommended for older API levels
- Works with Kotlin 1.5+

## Dependencies

- OkHttp 4.x - HTTP client
- Jackson 2.x - JSON processing
- SLF4J 2.x - Logging facade

## License

MIT License - see [LICENSE](LICENSE) for details.
