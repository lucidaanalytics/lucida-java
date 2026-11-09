package dev.lucida;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

/**
 * Server SDK. Calls name their distinct id, or inherit it from an open {@link LucidaRequestContext}. Delivery is at-least-once
 * (the same uuid is resent on retry, the server dedupes).
 */
public final class Lucida implements AutoCloseable {
    public static final String LIB = "lucida-java";
    public static final String VERSION = "0.1.1";
    private static final int MIN_GZIP_BYTES = 1024;
    private static final int MAX_BATCH = 1000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RNG = new SecureRandom();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** Client settings: public fields, set the ones you need. */
    public static final class Options {
        public String host = "https://capture.lucidace.com";
        public int flushAt = 20;
        public Duration flushInterval = Duration.ofSeconds(5);
        public int maxQueueSize = 1000;
        public int maxRetries = 5;
        public Duration retryBase = Duration.ofSeconds(1);
        /** Secret key (lc_sk_...): needed to load flag definitions. */
        public String secretKey;
        public boolean disabled;
        /** Inject to observe or fake the network. */
        public HttpClient http;
        /** Offline test mode: a flag fixture ({@code {"flags": [...]}} or a bare array). Events go to {@link #captured()}, nothing is sent. */
        public String testModeFixtureJson;
        /** Logs delivery and flag activity through {@code System.Logger} "dev.lucida" at INFO. */
        public boolean debug;
        /** Per-request timeout for every HTTP call (default 10s). */
        public Duration requestTimeout = Duration.ofSeconds(10);
        /** Called with the cause of a failed background send or flag fetch; exceptions it throws are swallowed. */
        public Consumer<Throwable> onError;
        /** Edit or drop events before they are queued: return null to drop. If it throws, the original event is kept. */
        public UnaryOperator<ObjectNode> beforeSend;
        /** Events per request, independent of {@link #flushAt} (the flush trigger). Capped at 1000. */
        public int maxBatchSize = MAX_BATCH;
        /** Poll flag definitions in the background (needs {@link #secretKey}); null disables. Floor 5s. */
        public Duration flagPollInterval;
        /** Timeout for flag requests (definitions poll and remote evaluation); null uses {@link #requestTimeout}. */
        public Duration flagTimeout;
        /** Send {@code $geoip_disable=true} on every event. */
        public boolean disableGeoip;
        /** Super properties on every event (also {@link Lucida#register}); context and per-call properties win. */
        public Map<String, ?> defaultProperties;
        /** Never ask the server for flags undecidable locally (they read as unknown). */
        public boolean onlyEvaluateLocally;
        /** App contexts of this process; flags scoped to other app contexts are dropped from results. A FlagContext may override. */
        public List<String> appContexts;
        /** Shares one definitions fetch between workers; see {@link FlagDefinitionCacheProvider}. */
        public FlagDefinitionCacheProvider flagDefinitionCache;
        /**
         * {@code {flagKey: value}} (Boolean or variant String) served with reason {@code "fallback"} when a flag cannot be
         * evaluated (network error, nothing loaded, quota limited, unknown key). Fallback reads send no exposure.
         */
        public Map<String, Object> fallbacks;

        public Options fallbacks(Map<String, Object> fallbacks) {
            this.fallbacks = fallbacks;
            return this;
        }
    }

    private final String apiKey;
    private final Options o;
    private final HttpClient http;
    private final List<ObjectNode> queue = new ArrayList<>();
    private final List<ObjectNode> captured = new ArrayList<>();
    private final ScheduledExecutorService timer;
    private final AtomicInteger dropped = new AtomicInteger();
    private final Reporter log;
    private final FlagSync sync;
    private final Identity identity = new Identity();

    public Lucida(String apiKey, Options options) {
        this.apiKey = apiKey;
        this.o = options == null ? new Options() : options;
        this.log = new Reporter(o.debug, o.onError);
        boolean offline = o.testModeFixtureJson != null || o.disabled;
        http = offline ? null : o.http != null ? o.http : HttpClient.newHttpClient();
        sync = new FlagSync(http, o.host, apiKey, o.secretKey, o.flagTimeout != null ? o.flagTimeout : o.requestTimeout, log);
        sync.cache(o.flagDefinitionCache);
        if (o.defaultProperties != null) identity.superProps.putAll(o.defaultProperties);
        if (o.testModeFixtureJson != null) sync.set(o.testModeFixtureJson);
        if (offline) {
            timer = null;
        } else {
            timer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "lucida-flush");
                t.setDaemon(true);
                return t;
            });
            long ms = o.flushInterval.toMillis();
            timer.scheduleWithFixedDelay(this::flush, ms, ms, TimeUnit.MILLISECONDS);
            if (o.flagPollInterval != null) sync.startPolling(o.flagPollInterval);
        }
    }

    public Lucida(String apiKey) {
        this(apiKey, null);
    }

    public boolean isTestMode() {
        return o.testModeFixtureJson != null;
    }

    /** Test mode only: every captured event, including exposures. */
    public List<ObjectNode> captured() {
        synchronized (captured) {
            return List.copyOf(captured);
        }
    }

    /** Events discarded because the queue was full or the server kept failing. */
    public int droppedEvents() {
        return dropped.get();
    }

    // events ---------------------------------------------------------------------------------

    /**
     * Optional capture arguments; any may be null. {@code personless} sends {@code $process_person_profile=false}
     * (no person is created or updated; {@code $set} is ignored by the server) and, with no distinct id, a random UUID v4 one.
     */
    public record Extras(Map<String, String> groups, Instant timestamp, String uuid, Map<String, ?> set, Map<String, ?> setOnce, boolean personless,
            FlagSnapshot flags, boolean sendFeatureFlags) {
        public static final Extras NONE = new Extras(null, null, null, null, null, false);
        public static final Extras PERSONLESS = new Extras(null, null, null, null, null, true);

        public Extras(Map<String, String> groups, Instant timestamp, String uuid, Map<String, ?> set, Map<String, ?> setOnce, boolean personless) {
            this(groups, timestamp, uuid, set, setOnce, personless, null, false);
        }

        /** Attach a snapshot's {@code $feature/*} properties to the event. */
        public static Extras withFlags(FlagSnapshot snapshot) {
            return new Extras(null, null, null, null, null, false, snapshot, false);
        }

        /** Evaluate the distinct id's flags first (locally when definitions are loaded) and attach them. */
        public static Extras sendingFeatureFlags(Map<String, String> groups) {
            return new Extras(groups, null, null, null, null, false, null, true);
        }

        public Extras(Map<String, String> groups, Instant timestamp, String uuid, Map<String, ?> set, Map<String, ?> setOnce) {
            this(groups, timestamp, uuid, set, setOnce, false);
        }
    }

    /** One historical event for {@link #importEvents}. {@code distinctId} and {@code event} are required. */
    public record ImportEvent(String distinctId, String event, Map<String, ?> properties, Instant timestamp, String uuid, Map<String, ?> set, Map<String, ?> setOnce) {}

    public void capture(String distinctId, String event, Map<String, ?> properties) {
        capture(distinctId, event, properties, Extras.NONE);
    }

    /** Capture with no distinct id and no person profile: a random UUID v4 id is generated. */
    public void capturePersonless(String event, Map<String, ?> properties) {
        capture(null, event, properties, Extras.PERSONLESS);
    }

    /** A null {@code distinctId} falls back to the open {@link LucidaRequestContext}, then (personless only) a random UUID v4. */
    public void capture(String distinctId, String event, Map<String, ?> properties, Extras x) {
        LucidaRequestContext.Data ctx = LucidaRequestContext.current();
        String id = distinctId != null ? distinctId : ctx != null ? ctx.distinctId() : null;
        if (id == null && x.personless()) id = UUID.randomUUID().toString();
        if (id == null) throw new IllegalArgumentException("distinctId is required (or open a LucidaRequestContext)");
        ObjectNode props = JSON.createObjectNode();
        if (!identity.superProps.isEmpty()) props.setAll((ObjectNode) JSON.valueToTree(identity.superProps));
        if (ctx != null) {
            if (ctx.sessionId() != null) props.put("$session_id", ctx.sessionId());
            props.setAll((ObjectNode) JSON.valueToTree(ctx.props()));
        }
        if (properties != null) props.setAll((ObjectNode) JSON.valueToTree(properties)); // explicit args win
        if (x.personless()) props.put("$process_person_profile", false);
        if (o.disableGeoip) props.put("$geoip_disable", true);
        Map<String, Object> flagProps = null;
        if (x.flags() != null) flagProps = x.flags().values();
        else if (x.sendFeatureFlags()) flagProps = valuesOf(getAllFlagResults(withDefaults(new FlagContext(id, null, null, null, x.groups(), null, null, null, null))));
        if (flagProps != null) {
            ObjectNode merged = (ObjectNode) JSON.valueToTree(FlagSnapshot.eventProps(null, flagProps));
            merged.setAll(props); // caller properties win
            props = merged;
        }
        ObjectNode e = wire(id, event, props, x.timestamp(), x.uuid(), x.set(), x.setOnce());
        if (x.groups() != null && !x.groups().isEmpty()) e.set("$groups", JSON.valueToTree(x.groups()));
        if (o.beforeSend != null) {
            try {
                e = o.beforeSend.apply(e.deepCopy());
            } catch (RuntimeException ex) {
                log.error("beforeSend threw; keeping the original event", ex);
            }
            if (e == null) return;
        }
        enqueue(e);
    }

    private static ObjectNode wire(String id, String event, ObjectNode props, Instant ts, String uuid, Map<String, ?> set, Map<String, ?> setOnce) {
        props.put("$lib", LIB);
        props.put("$lib_version", VERSION);
        ObjectNode e = JSON.createObjectNode();
        e.put("uuid", uuid != null ? uuid : uuid7());
        e.put("event", event);
        e.put("distinct_id", id);
        e.put("timestamp", ISO.format(ts != null ? ts : Instant.now()));
        e.set("properties", props);
        if (set != null && !set.isEmpty()) e.set("$set", JSON.valueToTree(set));
        if (setOnce != null && !setOnce.isEmpty()) e.set("$set_once", JSON.valueToTree(setOnce));
        return e;
    }

    /**
     * Historical import through {@code POST /v1/s2s/batch} with the secret key; timestamps are kept. Chunks of
     * {@link Options#maxBatchSize}; returns the number accepted. In test mode the events land in {@link #captured()}.
     *
     * @throws LucidaException without a secret key, or on the first rejected chunk
     */
    public int importEvents(List<ImportEvent> events) {
        if (o.secretKey == null) throw new LucidaException("importEvents requires secretKey");
        if (o.disabled) return 0;
        List<ObjectNode> wire = new ArrayList<>();
        for (ImportEvent ev : events) {
            ObjectNode props = ev.properties() == null ? JSON.createObjectNode() : JSON.valueToTree(ev.properties());
            wire.add(wire(ev.distinctId(), ev.event(), props, ev.timestamp(), ev.uuid(), ev.set(), ev.setOnce()));
        }
        if (isTestMode()) {
            synchronized (captured) {
                captured.addAll(wire);
            }
            return wire.size();
        }
        int size = batchSize(), sent = 0;
        for (int i = 0; i < wire.size(); i += size) {
            List<ObjectNode> chunk = wire.subList(i, Math.min(wire.size(), i + size));
            ObjectNode payload = JSON.createObjectNode();
            ArrayNode arr = payload.putArray("batch");
            chunk.forEach(arr::add);
            payload.put("sent_at", ISO.format(Instant.now()));
            HttpResponse<Void> res = post("/v1/s2s/batch", payload.toString(), o.secretKey);
            int status = res == null ? 0 : res.statusCode();
            if (status < 200 || status >= 300) throw new LucidaException("import failed: status " + status + " after " + sent + " events");
            sent += chunk.size();
        }
        return sent;
    }

    private int batchSize() {
        return Math.max(1, Math.min(MAX_BATCH, o.maxBatchSize));
    }

    /** Adds super properties to every later event; context and per-call properties win. */
    public void register(Map<String, ?> properties) {
        identity.superProps.putAll(properties);
    }

    public void unregister(String key) {
        identity.superProps.remove(key);
    }

    /** {@code $set} event updating person properties. */
    public void setPersonProperties(String distinctId, Map<String, ?> properties) {
        capture(distinctId, "$set", null, new Extras(null, null, null, properties, null));
    }

    /** {@code $set} event with {@code $set_once}: only fills properties that are not set yet. */
    public void setPersonPropertiesOnce(String distinctId, Map<String, ?> properties) {
        capture(distinctId, "$set", null, new Extras(null, null, null, null, properties));
    }

    /** Capture with exactly the snapshot's flag values attached; explicit properties win. */
    public void captureWithFlags(String distinctId, String event, Map<String, ?> properties, FlagSnapshot flags) {
        capture(distinctId, event, flags.eventProps(properties));
    }

    /** An identical call (same id and properties) already sent by this client is skipped. */
    public void identify(String distinctId, Map<String, ?> properties, Map<String, ?> propertiesSetOnce) {
        if (!identity.first("identify", distinctId, properties, propertiesSetOnce)) return;
        capture(distinctId, "$identify", null, new Extras(null, null, null, properties, propertiesSetOnce));
    }

    public void alias(String distinctId, String alias) {
        capture(distinctId, "$create_alias", Map.of("alias", alias, "distinct_id", distinctId));
    }

    public void groupIdentify(String groupType, String groupKey, Map<String, ?> properties, String distinctId) {
        if (!identity.first("group", groupType, groupKey, distinctId, properties)) return;
        capture(distinctId != null ? distinctId : "$" + groupType + "_" + groupKey, "$groupidentify",
                Map.of("$group_type", groupType, "$group_key", groupKey, "$group_set", properties == null ? Map.of() : properties));
    }

    private void enqueue(ObjectNode e) {
        if (o.disabled) return;
        if (isTestMode()) {
            synchronized (captured) {
                captured.add(e);
            }
            return;
        }
        boolean full;
        int size;
        synchronized (queue) {
            full = queue.size() >= o.maxQueueSize;
            if (!full) queue.add(e);
            size = queue.size();
        }
        if (full) dropped.incrementAndGet();
        else if (size >= o.flushAt) timer.execute(this::flush);
    }

    /** Sends everything queued. Returns false if events were dropped after exhausting retries. */
    public boolean flush() {
        if (http == null) return true;
        boolean ok = true;
        synchronized (this) { // one flush at a time keeps batches in order
            while (true) {
                List<ObjectNode> batch;
                synchronized (queue) {
                    int n = Math.min(batchSize(), queue.size());
                    if (n == 0) return ok;
                    batch = new ArrayList<>(queue.subList(0, n));
                    queue.subList(0, n).clear();
                }
                if (!sendWithRetry(batch)) {
                    dropped.addAndGet(batch.size());
                    ok = false;
                }
            }
        }
    }

    private boolean sendWithRetry(List<ObjectNode> batch) {
        ObjectNode payload = JSON.createObjectNode();
        ArrayNode arr = payload.putArray("batch");
        batch.forEach(arr::add);
        payload.put("sent_at", ISO.format(Instant.now()));
        payload.put("api_key", apiKey);
        for (int attempt = 0; ; attempt++) {
            HttpResponse<Void> res = post("/v1/batch", payload.toString(), null);
            int status = res == null ? 0 : res.statusCode();
            if (status >= 200 && status < 300) return true;
            boolean retryable = status == 0 || status == 408 || status == 429 || status >= 500;
            if (!retryable || attempt >= o.maxRetries) {
                log.error("dropping " + batch.size() + " events: status " + status, null);
                return false;
            }
            double step = Math.min(30_000, o.retryBase.toMillis() * Math.pow(2, attempt)) * (0.5 + 0.5 * RNG.nextDouble());
            long retryAfter = res == null ? 0 : res.headers().firstValueAsLong("retry-after").orElse(0) * 1000;
            try {
                Thread.sleep((long) Math.max(step, Math.min(retryAfter, 60_000)));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** JSON body; gzip above 1 KB flagged with {@code ?compression=gzip}. */
    private HttpResponse<Void> post(String path, String json, String bearer) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String url = o.host.replaceAll("/+$", "") + path;
        if (body.length >= MIN_GZIP_BYTES) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
                gz.write(body);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            body = out.toByteArray();
            url += (url.contains("?") ? "&" : "?") + "compression=gzip";
        }
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).timeout(o.requestTimeout).header("Content-Type", "application/json");
        if (bearer != null) rb.header("Authorization", "Bearer " + bearer);
        try {
            return http.send(rb.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            log.debug("post " + path + " failed: " + e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // flags ----------------------------------------------------------------------------------

    /** Loads definitions from a fixture or a {@code /v1/flags/definitions} body: {@code {"flags": [...]}} or a bare array. */
    public void setFlagDefinitions(String json) {
        sync.set(json);
    }

    /** Fetches definitions once (needs a secret key; ETag aware: 304 counts as success); keeps the previous ones on any failure. */
    public boolean reloadFlags() {
        return sync.reload();
    }

    /** Evaluates one flag locally; null when the key is unknown. {@link Evaluator#REQUIRES_SERVER} means cohorts or other server-only data are involved. */
    public FlagResult evaluate(String flagKey, FlagContext ctx) {
        return evaluateAll(ctx).get(flagKey);
    }

    private FlagContext withDefaults(FlagContext ctx) {
        return ctx.appContexts() == null && o.appContexts != null ? ctx.withAppContexts(o.appContexts) : ctx;
    }

    /** Every flag evaluated locally, minus the ones scoped to other app contexts. */
    private Map<String, FlagResult> evaluateAll(FlagContext ctx) {
        Map<String, FlagResult> all = new LinkedHashMap<>(Evaluator.evaluateAll(sync.definitions(), withDefaults(ctx)));
        all.values().removeIf(r -> "app_context_excluded".equals(r.reason()));
        return all;
    }

    private static Map<String, Object> valuesOf(Map<String, FlagResult> results) {
        Map<String, Object> out = new LinkedHashMap<>();
        results.forEach((k, r) -> out.put(k, r.value()));
        return out;
    }

    /** Evaluates every flag once; read the snapshot freely and attach it with {@link Extras#withFlags}. No exposure events. */
    public FlagSnapshot evaluateFlags(FlagContext ctx) {
        Map<String, FlagResult> all = getAllFlagResults(ctx);
        Map<String, JsonNode> payloads = new LinkedHashMap<>();
        all.forEach((k, r) -> {
            if (r.payload() != null) payloads.put(k, r.payload());
        });
        return new FlagSnapshot(valuesOf(all), payloads);
    }

    /**
     * Payload of a remote-config flag, decrypted by the server when stored encrypted (needs the secret key). Null when
     * unknown or on failure.
     *
     * @throws LucidaException without a secret key
     */
    public JsonNode getRemoteConfigPayload(String key) {
        if (o.secretKey == null) throw new LucidaException("getRemoteConfigPayload requires a secret key");
        return o.disabled ? null : sync.remoteConfig(key);
    }

    public boolean isEnabled(String flagKey, FlagContext ctx) {
        FlagResult r = evaluate(flagKey, ctx);
        return r != null && !Boolean.FALSE.equals(r.value());
    }

    /**
     * Value, payload and reason of one flag. Decided locally when possible; otherwise (unknown key, cohorts, nothing
     * loaded) asked of the server with reason {@code "remote"}, unless {@link Options#onlyEvaluateLocally}. Null when undecided.
     */
    public FlagResult getFlagResult(String flagKey, FlagContext ctx) {
        return getFlagResult(flagKey, ctx, o.onlyEvaluateLocally);
    }

    public FlagResult getFlagResult(String flagKey, FlagContext ctx, boolean onlyEvaluateLocally) {
        FlagResult r = evaluate(flagKey, ctx);
        if (r != null && !Evaluator.REQUIRES_SERVER.equals(r.reason())) return r;
        r = onlyEvaluateLocally ? null : sync.remote(withDefaults(ctx), List.of(flagKey)).get(flagKey);
        if (r == null && o.fallbacks != null && o.fallbacks.containsKey(flagKey)) return FlagResult.fallback(o.fallbacks.get(flagKey));
        return r;
    }

    /** Value, payload, variant, raw reason, condition index, request id and local flag of one read (same as {@link #getFlagResult}). */
    public FlagResult getFeatureFlagDetails(String flagKey, FlagContext ctx) {
        return getFlagResult(flagKey, ctx);
    }

    /** Calls {@code r} after definitions are (re)loaded with a change (first load included). */
    public void onFlagsChange(Runnable r) {
        sync.onChange.add(r);
    }

    /** Calls {@code r} when a definitions reload fails. */
    public void onFlagsStale(Runnable r) {
        sync.onStale.add(r);
    }

    /** Waits for the first definitions fetch; true when loaded or nothing is polled. */
    public boolean awaitFlags(Duration max) {
        return sync.await(max);
    }

    /** {@code Boolean}, a variant key, or null when unknown / undecidable. */
    public Object getFeatureFlag(String flagKey, FlagContext ctx) {
        FlagResult r = getFlagResult(flagKey, ctx);
        return r == null ? null : r.value();
    }

    /** The flag's JSON payload for its resolved variant, or null. */
    public JsonNode getFeatureFlagPayload(String flagKey, FlagContext ctx) {
        FlagResult r = getFlagResult(flagKey, ctx);
        return r == null ? null : r.payload();
    }

    /** The AI Config variation (completion or agent mode) served for {@code ctx}, or {@code fallback} (P-M49). */
    public AiConfig getAiConfig(String flagKey, FlagContext ctx, AiConfig fallback, Map<String, String> vars) {
        return AiConfig.from(getFlagResult(flagKey, ctx), fallback, vars);
    }

    /** Every flag decided locally, plus (unless local-only) whatever the server decides for the rest. */
    public Map<String, FlagResult> getAllFlagResults(FlagContext ctx) {
        Map<String, FlagResult> out = new LinkedHashMap<>();
        boolean undecided = !sync.loaded();
        for (Map.Entry<String, FlagResult> e : evaluateAll(ctx).entrySet()) {
            if (Evaluator.REQUIRES_SERVER.equals(e.getValue().reason())) undecided = true;
            else out.put(e.getKey(), e.getValue());
        }
        if (undecided && !o.onlyEvaluateLocally) sync.remote(withDefaults(ctx), null).forEach(out::putIfAbsent);
        return out;
    }

    public Map<String, Object> getAllFlags(FlagContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        getAllFlagResults(ctx).forEach((k, r) -> out.put(k, r.value()));
        return out;
    }

    /** Values and payloads; flags without a payload are absent from {@code payloads}. */
    public record FlagsAndPayloads(Map<String, Object> flags, Map<String, JsonNode> payloads) {}

    public FlagsAndPayloads getAllFlagsAndPayloads(FlagContext ctx) {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, JsonNode> payloads = new LinkedHashMap<>();
        getAllFlagResults(ctx).forEach((k, r) -> {
            values.put(k, r.value());
            if (r.payload() != null) payloads.put(k, r.payload());
        });
        return new FlagsAndPayloads(values, payloads);
    }

    static String uuid7() {
        byte[] b = new byte[16];
        RNG.nextBytes(b);
        long ms = System.currentTimeMillis();
        for (int i = 0; i < 6; i++) b[i] = (byte) (ms >> (8 * (5 - i)));
        b[6] = (byte) (0x70 | (b[6] & 0x0F));
        b[8] = (byte) (0x80 | (b[8] & 0x3F));
        String h = HexFormat.of().formatHex(b);
        return h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16) + "-" + h.substring(16, 20) + "-" + h.substring(20);
    }

    @Override
    public void close() {
        if (timer != null) timer.shutdownNow();
        sync.close();
        flush();
    }
}
