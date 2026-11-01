package dev.lucida;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** testdata/flags vectors (evaluator) and testdata/events wire fixtures (client). */
class ConformanceTest {
    static final ObjectMapper M = new ObjectMapper();
    private static final Map<String, List<String>> CTX = Map.of(
            "distinct_id", List.of("distinct_id", "bucketing_key"), "groups", List.of("groups"),
            "person_properties", List.of("person_properties"), "group_properties", List.of("group_properties"),
            "device_properties", List.of("device_properties"), "geo_properties", List.of("geo_properties"),
            "app_version", List.of("app_version"));

    static JsonNode testdata(String rel) throws IOException {
        String env = System.getenv("LUCIDA_TESTDATA");
        if (env != null && !env.isEmpty()) return M.readTree(new File(env, rel));
        File d = new File("").getAbsoluteFile();
        while (d != null && !new File(d, "testdata/" + rel).exists()) d = d.getParentFile();
        if (d == null) throw new IOException("testdata/" + rel + " not found");
        return M.readTree(new File(d, "testdata/" + rel));
    }

    private static Map<String, JsonNode> obj(JsonNode c, String name) {
        JsonNode v = c.get(name);
        if (v == null || !v.isObject()) return null;
        Map<String, JsonNode> m = new HashMap<>();
        v.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue()));
        return m;
    }

    /** Only the context fields named by `needs`: anything else must not influence the result. */
    static FlagContext supplied(JsonNode vec) {
        JsonNode ctx = vec.get("context");
        Set<String> keep = new HashSet<>();
        if (vec.has("needs")) vec.get("needs").forEach(n -> keep.addAll(CTX.getOrDefault(n.asText(), List.of())));
        else ctx.fieldNames().forEachRemaining(keep::add);
        java.util.function.Function<String, String> s = n -> keep.contains(n) && ctx.path(n).isTextual() ? ctx.get(n).asText() : null;
        Map<String, String> groups = null;
        if (keep.contains("groups") && ctx.path("groups").isObject()) {
            groups = new HashMap<>();
            for (var it = ctx.get("groups").fields(); it.hasNext(); ) {
                var e = it.next();
                groups.put(e.getKey(), e.getValue().asText());
            }
        }
        Map<String, Map<String, JsonNode>> gp = null;
        if (keep.contains("group_properties") && ctx.path("group_properties").isObject()) {
            gp = new HashMap<>();
            for (var it = ctx.get("group_properties").fields(); it.hasNext(); ) {
                var e = it.next();
                gp.put(e.getKey(), obj(ctx.get("group_properties"), e.getKey()));
            }
        }
        return new FlagContext(s.apply("distinct_id"), s.apply("bucketing_key"), s.apply("device_id"), s.apply("app_version"), groups,
                keep.contains("person_properties") ? obj(ctx, "person_properties") : null, gp,
                keep.contains("device_properties") ? obj(ctx, "device_properties") : null,
                keep.contains("geo_properties") ? obj(ctx, "geo_properties") : null,
                obj(ctx, "sticky_assignments"), s.apply("now"), appContexts(ctx));
    }

    private static List<String> appContexts(JsonNode ctx) {
        if (!ctx.path("app_contexts").isArray()) return null;
        List<String> out = new ArrayList<>();
        ctx.get("app_contexts").forEach(n -> out.add(n.asText()));
        return out;
    }

    private static int checkFile(String name) throws IOException {
        JsonNode root = testdata("flags/" + name);
        for (JsonNode h : root.get("hash_vectors")) assertEquals(h.get("hash").asDouble(), Evaluator.hash01(h.get("input").asText()), h.get("input").asText());
        List<JsonNode> flags = new ArrayList<>();
        root.get("flags").forEach(flags::add);
        int n = 0;
        for (JsonNode v : root.get("vectors")) {
            FlagResult r = Evaluator.evaluateAll(flags, supplied(v)).get(v.get("flag").asText());
            JsonNode e = v.get("expected");
            String label = v.get("name").asText();
            assertEquals(e.get("value"), M.valueToTree(r.value()), label + " value");
            assertEquals(e.get("reason").asText(), r.reason(), label + " reason");
            assertEquals(e.get("condition_index").asInt(), r.conditionIndex(), label + " index");
            assertEquals(e.path("variant").isTextual() ? e.get("variant").asText() : null, r.variant(), label + " variant");
            JsonNode want = e.has("payload") && !e.get("payload").isNull() ? e.get("payload") : null;
            JsonNode got = r.payload() == null || r.payload().isNull() ? null : r.payload();
            assertEquals(want, got, label + " payload");
            n++;
        }
        return n;
    }

    @Test void vectorsBasic() throws IOException { assertTrue(checkFile("vectors_basic.json") > 20); }
    @Test void vectorsG10() throws IOException { assertTrue(checkFile("vectors_g10.json") > 15); }
    @Test void vectorsSticky() throws IOException { assertTrue(checkFile("vectors_sticky.json") >= 19); }
    @Test void vectorsOpsV2() throws IOException { assertTrue(checkFile("vectors_ops.json") >= 100); }
    @Test void vectorsGenerated() throws IOException { assertEquals(10_000, checkFile("vectors.json")); }

    static Stream<JsonNode> eventCases() throws IOException {
        List<JsonNode> cases = new ArrayList<>();
        testdata("events/server_sdk.json").get("cases").forEach(cases::add);
        return cases.stream();
    }

    private static Map<String, Object> map(JsonNode c, String name) {
        return c.has(name) ? M.convertValue(c.get(name), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}) : null;
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("eventCases")
    void eventFixtures(JsonNode c) throws Exception {
        JsonNode call = c.get("call");
        try (FakeServer srv = new FakeServer()) {
            Lucida.Options o = new Lucida.Options();
            o.host = srv.host();
            o.flushInterval = java.time.Duration.ofHours(1);
            String id = call.path("distinct_id").asText(null);
            try (Lucida lc = new Lucida("lc_pk_x", o)) {
                switch (call.get("method").asText()) {
                    case "capture" -> lc.capture(id, call.get("event").asText(), map(call, "properties"), new Lucida.Extras(
                            call.has("groups") ? M.convertValue(call.get("groups"), new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {}) : null,
                            call.has("timestamp") ? java.time.Instant.parse(call.get("timestamp").asText()) : null,
                            call.path("uuid").asText(null), map(call, "set"), map(call, "set_once")));
                    case "identify" -> lc.identify(id, map(call, "properties"), map(call, "properties_set_once"));
                    case "alias" -> lc.alias(id, call.get("alias").asText());
                    default -> lc.groupIdentify(call.get("group_type").asText(), call.get("group_key").asText(), map(call, "properties"), id);
                }
                assertTrue(lc.flush());
            }
            assertEquals(1, srv.bodies.size());
            assertEquals("lc_pk_x", srv.bodies.get(0).get("api_key").asText());
            ObjectNode ev = (ObjectNode) srv.bodies.get(0).get("batch").get(0);
            ObjectNode props = (ObjectNode) ev.get("properties");
            assertTrue(props.get("$lib").asText().startsWith("lucida-"));
            assertFalse(props.get("$lib_version").asText().isEmpty());
            props.remove("$lib");
            props.remove("$lib_version");
            ObjectNode want = c.get("expected").deepCopy();
            for (String k : new String[] {"uuid", "timestamp"}) if (want.get(k).asText().equals("*")) want.set(k, ev.get(k));
            assertEquals(want, ev);
        }
    }
}
