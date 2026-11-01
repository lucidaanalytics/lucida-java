package dev.lucida;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;

/** Reference {@link FlagDefinitionCacheProvider}: process-local, fetches again once {@code ttl} has passed. */
public final class InMemoryFlagDefinitionCache implements FlagDefinitionCacheProvider {
    private final long ttlNanos;
    private List<JsonNode> defs;
    private long at;

    public InMemoryFlagDefinitionCache(Duration ttl) {
        this.ttlNanos = ttl.toNanos();
    }

    public InMemoryFlagDefinitionCache() {
        this(Duration.ofSeconds(30));
    }

    @Override
    public synchronized List<JsonNode> flagDefinitions() {
        return defs;
    }

    @Override
    public synchronized boolean shouldFetchFlagDefinitions() {
        return defs == null || System.nanoTime() - at >= ttlNanos;
    }

    @Override
    public synchronized void onFlagDefinitionsReceived(List<JsonNode> definitions) {
        defs = List.copyOf(definitions);
        at = System.nanoTime();
    }
}
