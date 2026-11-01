package dev.lucida;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-request ambient identity on a ThreadLocal. Open a scope per request (try-with-resources); {@link Lucida#capture}
 * then fills a missing distinct id, adds {@code $session_id} and the request properties. Explicit capture arguments win.
 *
 * <p>Framework-neutral on purpose: the SDK has no servlet dependency, so a servlet {@code Filter} adapts in a few lines
 * (see the README): build a header map and a props map, call {@link #fromHeaders}, then {@link #with}.
 */
public final class LucidaRequestContext {
    public static final String DISTINCT_ID_HEADER = "X-Lucida-Distinct-Id";
    public static final String SESSION_ID_HEADER = "X-Lucida-Session-Id";
    private static final int MAX_HEADER = 1000;
    private static final ThreadLocal<Data> CURRENT = new ThreadLocal<>();

    private LucidaRequestContext() {}

    /** What the request carries. {@code props} are event properties ({@code $current_url}, {@code $request_path}, ...). */
    public record Data(String distinctId, String sessionId, Map<String, Object> props) {}

    /** Restores the previous context on close. */
    public static final class Scope implements AutoCloseable {
        private final Data previous;
        private boolean closed;

        private Scope(Data previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public static Scope with(Data data) {
        Data prev = CURRENT.get();
        CURRENT.set(data);
        return new Scope(prev);
    }

    public static Scope with(String distinctId, String sessionId, Map<String, Object> props) {
        return with(new Data(distinctId, sessionId, props == null ? Map.of() : Map.copyOf(props)));
    }

    /** The open context of this thread, or null. */
    public static Data current() {
        return CURRENT.get();
    }

    /**
     * Reads a header case-insensitively; values may be a String or a collection (the first entry is used).
     * Control characters are stripped, the value trimmed and capped at 1000 chars; empty gives null.
     * These headers are client-controlled: never use them for authentication or authorisation.
     */
    public static String readHeader(Map<String, ?> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, ?> e : headers.entrySet()) {
            if (e.getKey() == null || !e.getKey().equalsIgnoreCase(name)) continue;
            Object v = e.getValue();
            if (v instanceof Collection<?> c) v = c.isEmpty() ? null : c.iterator().next();
            if (v == null) return null;
            String s = v.toString().replaceAll("[\\x00-\\x1F\\x7F]", "").trim();
            if (s.length() > MAX_HEADER) s = s.substring(0, MAX_HEADER);
            return s.isEmpty() ? null : s;
        }
        return null;
    }

    /**
     * Builds the context from request headers. {@code props} are the request facts the framework knows
     * ({@code $current_url}, {@code $request_method}, {@code $request_path}, {@code $ip} = remote address); the
     * user agent comes from the headers when absent. With {@code trustProxy}, {@code $ip} becomes the first
     * {@code X-Forwarded-For} entry.
     */
    public static Data fromHeaders(Map<String, ?> headers, boolean trustProxy, Map<String, Object> props) {
        Map<String, Object> p = new LinkedHashMap<>(props == null ? Map.of() : props);
        String ua = readHeader(headers, "User-Agent");
        if (ua != null) p.putIfAbsent("$user_agent", ua);
        if (trustProxy) {
            String xff = readHeader(headers, "X-Forwarded-For");
            if (xff != null) {
                String first = xff.split(",")[0].trim();
                if (!first.isEmpty()) p.put("$ip", first);
            }
        }
        return new Data(readHeader(headers, DISTINCT_ID_HEADER), readHeader(headers, SESSION_ID_HEADER), Map.copyOf(p));
    }
}
