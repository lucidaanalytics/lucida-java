package dev.lucida;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Flag definitions state: ETag-aware loading, optional background polling, and the remote {@code /v1/flags} call. */
final class FlagSync implements AutoCloseable {
    /** Polling faster than this only burns the definitions endpoint; shorter intervals are raised to it. */
    static final Duration MIN_POLL = Duration.ofSeconds(5);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http; // null in test mode / disabled
    private final String host;
    private final String secretKey;
    private final String apiKey;
    private final Duration timeout;
    private final Reporter log;
    private ScheduledExecutorService poller;
    private volatile List<JsonNode> flags = List.of();
    private volatile boolean loaded;
    private volatile boolean attempted; // a first fetch finished (ok or not)
    final List<Runnable> onChange = new CopyOnWriteArrayList<>();
    final List<Runnable> onStale = new CopyOnWriteArrayList<>();
    private volatile String etag;
    private volatile FlagDefinitionCacheProvider cache;

    FlagSync(HttpClient http, String host, String apiKey, String secretKey, Duration timeout, Reporter log) {
        this.http = http;
        this.host = host.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.timeout = timeout;
        this.log = log;
    }

    void cache(FlagDefinitionCacheProvider provider) {
        this.cache = provider;
    }

    /** Definitions another worker already fetched, or null when this process must fetch. A throwing provider means "fetch". */
    private List<JsonNode> adopt() {
        FlagDefinitionCacheProvider c = cache;
        if (c == null) return null;
        try {
            return c.shouldFetchFlagDefinitions() ? null : c.flagDefinitions();
        } catch (RuntimeException e) {
            log.error("flag definition cache failed", e);
            return null;
        }
    }

    private void publish() {
        FlagDefinitionCacheProvider c = cache;
        if (c == null) return;
        try {
            c.onFlagDefinitionsReceived(flags);
        } catch (RuntimeException e) {
            log.error("flag definition cache failed", e);
        }
    }

    static Duration clampInterval(Duration d) {
        return d.compareTo(MIN_POLL) < 0 ? MIN_POLL : d;
    }

    List<JsonNode> definitions() {
        return flags;
    }

    boolean loaded() {
        return loaded;
    }

    /** Waits for the first fetch attempt; true when definitions are loaded (or nothing is polled). */
    boolean await(Duration max) {
        if (http == null || secretKey == null || poller == null) return true;
        long end = System.nanoTime() + max.toNanos();
        while (!attempted && System.nanoTime() < end) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return loaded;
    }

    private static void fire(List<Runnable> hooks) {
        for (Runnable r : hooks) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
                // a listener never breaks the reload loop
            }
        }
    }

    boolean canFetchRemote() {
        return http != null;
    }

    /** Loads a fixture or {@code /v1/flags/definitions} body: {@code {"flags": [...]}} or a bare array. */
    void set(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            JsonNode arr = root.isObject() && root.has("flags") ? root.get("flags") : root;
            if (!arr.isArray()) throw new IllegalArgumentException("flag definitions must be {\"flags\": [...]} or an array");
            List<JsonNode> list = new ArrayList<>();
            arr.forEach(list::add);
            flags = List.copyOf(list);
            loaded = true;
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid flag definitions: " + e.getMessage(), e);
        }
    }

    /** One conditional fetch; true when the definitions are current (new body or 304). Keeps the old ones on failure. */
    boolean reload() {
        if (http == null || secretKey == null) return false;
        boolean ok = fetch();
        attempted = true;
        if (!ok) fire(onStale);
        return ok;
    }

    private boolean fetch() {
        List<JsonNode> cached = adopt();
        if (cached != null) {
            flags = List.copyOf(cached);
            loaded = true;
            fire(onChange);
            return true;
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(host + "/v1/flags/definitions"))
                .timeout(timeout).header("Authorization", "Bearer " + secretKey);
        if (etag != null) b.header("If-None-Match", etag);
        try {
            HttpResponse<String> res = http.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 304) return true;
            if (res.statusCode() / 100 != 2) {
                log.error("flag definitions fetch failed: status " + res.statusCode(), null);
                return false;
            }
            set(res.body());
            etag = res.headers().firstValue("etag").orElse(null);
            publish();
            fire(onChange);
            log.debug("flag definitions loaded (" + flags.size() + ")");
            return true;
        } catch (IOException | IllegalArgumentException e) {
            log.error("flag definitions fetch failed", e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Polls at {@code interval} (floored at {@link #MIN_POLL}); the first fetch runs at once. Stops on {@link #close}. */
    synchronized void startPolling(Duration interval) {
        if (http == null || secretKey == null || poller != null) return;
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lucida-flags");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(this::reload, 0, clampInterval(interval).toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Asks the server for flags; {@code keys} null means all. Empty on any failure. */
    Map<String, FlagResult> remote(FlagContext ctx, List<String> keys) {
        Map<String, FlagResult> out = new LinkedHashMap<>();
        if (http == null) return out;
        ObjectNode body = JSON.createObjectNode();
        body.put("api_key", apiKey);
        body.put("distinct_id", ctx.distinctId());
        body.set("groups", JSON.valueToTree(ctx.groups() == null ? Map.of() : ctx.groups()));
        body.set("person_properties", JSON.valueToTree(ctx.personProperties() == null ? Map.of() : ctx.personProperties()));
        body.set("group_properties", JSON.valueToTree(ctx.groupProperties() == null ? Map.of() : ctx.groupProperties()));
        if (keys != null && !keys.isEmpty()) body.set("flag_keys", JSON.valueToTree(keys));
        if (ctx.appContexts() != null && !ctx.appContexts().isEmpty()) body.set("app_contexts", JSON.valueToTree(ctx.appContexts()));
        if (ctx.anonDistinctId() != null && !ctx.anonDistinctId().isEmpty()) body.put("anon_distinct_id", ctx.anonDistinctId());
        return post(body, out);
    }

    /** Payload of a remote-config flag (the server decrypts encrypted ones for secret keys); null when unknown or on failure. */
    JsonNode remoteConfig(String key) {
        if (http == null) return null;
        String escaped = URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20");
        HttpRequest req = HttpRequest.newBuilder(URI.create(host + "/v1/flags/" + escaped + "/remote-config"))
                .timeout(timeout).header("Authorization", "Bearer " + secretKey).GET().build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 404) return null; // unknown key or not a remote-config flag
            if (res.statusCode() / 100 != 2) {
                log.error("remote config failed: status " + res.statusCode(), null);
                return null;
            }
            JsonNode p = JSON.readTree(res.body()).get("payload");
            return p == null || p.isNull() ? null : p;
        } catch (IOException e) {
            log.error("remote config failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private Map<String, FlagResult> post(ObjectNode body, Map<String, FlagResult> out) {
        if (http == null) return out;
        HttpRequest req = HttpRequest.newBuilder(URI.create(host + "/v1/flags")).timeout(timeout)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                log.error("remote flags failed: status " + res.statusCode(), null);
                return out;
            }
            JsonNode flagsNode = JSON.readTree(res.body()).path("flags");
            String rid = JSON.readTree(res.body()).path("request_id").asText(null);
            flagsNode.fields().forEachRemaining(e -> out.put(e.getKey(), parseRemote(e.getValue(), rid)));
        } catch (IOException e) {
            log.error("remote flags failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out;
    }

    static FlagResult parseRemote(JsonNode f, String requestId) {
        JsonNode v = f.get("value");
        Object value;
        String variant = f.hasNonNull("variant") ? f.get("variant").asText() : null;
        if (v != null && !v.isNull()) value = v.isBoolean() ? (Object) v.asBoolean() : v.asText();
        else if (variant != null) value = variant;
        else value = f.path("enabled").asBoolean(false);
        JsonNode payload = f.hasNonNull("payload") ? f.get("payload") : null;
        String reason = f.hasNonNull("reason") && !f.get("reason").asText().isEmpty() ? f.get("reason").asText() : "remote";
        return new FlagResult(value, reason, f.path("condition_index").asInt(-1), value instanceof String s ? s : variant, payload, requestId, false);
    }

    @Override
    public synchronized void close() {
        if (poller != null) poller.shutdownNow();
        FlagDefinitionCacheProvider c = cache;
        if (c != null) {
            try {
                c.shutdown();
            } catch (RuntimeException e) {
                log.error("flag definition cache shutdown failed", e);
            }
        }
    }
}
