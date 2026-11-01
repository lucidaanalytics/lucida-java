package dev.lucida;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Lets many workers share one definitions fetch (Redis, a database, files...). Before each poll the client asks
 * {@link #shouldFetchFlagDefinitions()}: when false it adopts {@link #flagDefinitions()} instead of calling the
 * server; the worker that did fetch calls back through {@link #onFlagDefinitionsReceived}. A provider that throws
 * is treated as "fetch yourself".
 */
public interface FlagDefinitionCacheProvider {
    /** Cached definitions, or null when nothing is cached. */
    List<JsonNode> flagDefinitions();

    /** True when this process should call the server (for example it won the fetch lock). */
    boolean shouldFetchFlagDefinitions();

    /** Stores freshly fetched definitions for the other workers. */
    void onFlagDefinitionsReceived(List<JsonNode> definitions);

    /** Releases locks and connections. */
    default void shutdown() {}
}
