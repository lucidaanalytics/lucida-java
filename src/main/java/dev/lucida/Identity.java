package dev.lucida;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
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

    /** True the first time this exact call (kind, id, properties) is seen. */
    boolean first(Object... parts) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsString(parts).getBytes(StandardCharsets.UTF_8));
            return seen.put(HexFormat.of().formatHex(d), Boolean.TRUE) == null;
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            return true; // unhashable properties: never suppress
        }
    }
}
