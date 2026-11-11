package dev.lucida;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-client super properties and the bounded set of identify/group calls already sent. */
final class Identity {
    private static final int DEDUPE_SIZE = 1000;
    private static final ObjectMapper JSON = new ObjectMapper();

    final Map<String, Object> superProps = new ConcurrentHashMap<>();
    private final Map<String, Boolean> seen = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > DEDUPE_SIZE;
        }
    });

    /** Event uuid to dedupe key, until the event is dropped. */
    private final Map<String, String> keys = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > DEDUPE_SIZE;
        }
    });

    /**
     * The uuid to send this identify/group event under the first time this exact call (kind, id, properties) is
     * seen, else null. {@link #release} frees the call again when the event is dropped.
     */
    String claim(Object... parts) {
        String key;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsString(parts).getBytes(StandardCharsets.UTF_8));
            key = HexFormat.of().formatHex(d);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            return UUID.randomUUID().toString(); // unhashable properties: never suppress
        }
        if (seen.put(key, Boolean.TRUE) != null) return null;
        String uuid = Lucida.uuid7();
        keys.put(uuid, key);
        return uuid;
    }

    /** Forgets the calls whose events were dropped (rejected batch, full queue) so repeating them is sent again. */
    void release(Iterable<? extends JsonNode> events) {
        for (JsonNode e : events) {
            String key = keys.remove(e.path("uuid").asText());
            if (key != null) seen.remove(key);
        }
    }
}
