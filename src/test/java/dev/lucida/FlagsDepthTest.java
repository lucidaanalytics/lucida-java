package dev.lucida;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Snapshot, event enrichment, definition cache and remote-config payload (LC-FLG-28, 29, 45, 46). */
class FlagsDepthTest {
    private static final ObjectMapper M = new ObjectMapper();

    private static String flag(String key, String ctxs, int pct) {
        return "{\"key\":\"" + key + "\",\"type\":\"boolean\",\"active\":true,\"salt\":\"s\",\"aggregation\":{\"kind\":\"person\"},"
                + "\"app_contexts\":" + ctxs + ",\"conditions\":[{\"rollout_pct\":" + pct + "}]}";
    }

    private static final String DEFS = "{\"flags\":[" + flag("a", "[]", 100) + "," + flag("b", "[]", 100) + "," + flag("off", "[]", 0) + ","
            + flag("web", "[\"web\"]", 100) + "]}";

    private static Lucida.Options fixture() {
        Lucida.Options o = new Lucida.Options();
        o.testModeFixtureJson = DEFS;
        o.appContexts = List.of("api");
        return o;
    }

    @Test
    void snapshotAndEnrichment() {
        try (Lucida lc = new Lucida("lc_pk_x", fixture())) {
            lc.capture("u", "buy", Map.of("$feature/a", "mine"), Lucida.Extras.sendingFeatureFlags(null));
            FlagSnapshot snap = lc.evaluateFlags(FlagContext.of("u"));
            assertTrue(snap.isEnabled("a"));
            assertEquals(List.of("a", "b", "off"), snap.keys());
            lc.capture("u", "e1", null, Lucida.Extras.withFlags(snap.onlyAccessed()));
            JsonNode p0 = lc.captured().get(0).get("properties"), p1 = lc.captured().get(1).get("properties");
            assertEquals("mine", p0.get("$feature/a").asText());
            assertFalse(p0.get("$feature/off").asBoolean(true));
            assertFalse(p0.has("$feature/web"));
            assertEquals("[\"a\",\"b\"]", p0.get("$active_feature_flags").toString());
            assertTrue(p1.get("$feature/a").asBoolean());
            assertFalse(p1.has("$feature/b"));
            assertTrue(lc.captured().stream().noneMatch(e -> e.get("event").asText().equals("$feature_flag_called")));
        }
    }

    @Test
    void eventPropsCallerWins() {
        assertEquals(Map.of("x", 1), FlagSnapshot.eventProps(Map.of("x", 1), Map.of()));
        Map<String, Object> out = FlagSnapshot.eventProps(Map.of("$active_feature_flags", List.of()), Map.of("a", "v"));
        assertEquals(List.of(), out.get("$active_feature_flags"));
    }

    @Test
    void definitionCacheIsAdoptedAndPublished() throws Exception {
        try (FakeServer srv = new FakeServer()) {
            InMemoryFlagDefinitionCache cache = new InMemoryFlagDefinitionCache(Duration.ofMinutes(1));
            cache.onFlagDefinitionsReceived(List.of(M.readTree(flag("a", "[]", 100))));
            Lucida.Options o = new Lucida.Options();
            o.host = srv.host();
            o.secretKey = "lc_sk_x";
            o.flushInterval = Duration.ofHours(1);
            o.flagDefinitionCache = cache;
            try (Lucida lc = new Lucida("lc_pk_x", o)) {
                assertTrue(lc.reloadFlags());
                assertEquals(0, srv.paths.size(), "fresh cache means no fetch");
                assertTrue(lc.isEnabled("a", FlagContext.of("u")));
            }
            InMemoryFlagDefinitionCache empty = new InMemoryFlagDefinitionCache(Duration.ofMinutes(1));
            o.flagDefinitionCache = empty;
            try (Lucida lc = new Lucida("lc_pk_x", o)) {
                assertTrue(lc.reloadFlags());
                assertEquals(1, empty.flagDefinitions().size());
            }
        }
    }

    @Test
    void brokenCacheMeansFetch() throws Exception {
        try (FakeServer srv = new FakeServer()) {
            Lucida.Options o = new Lucida.Options();
            o.host = srv.host();
            o.secretKey = "lc_sk_x";
            o.flushInterval = Duration.ofHours(1);
            o.flagDefinitionCache = new FlagDefinitionCacheProvider() {
                public List<JsonNode> flagDefinitions() { throw new IllegalStateException("down"); }
                public boolean shouldFetchFlagDefinitions() { throw new IllegalStateException("down"); }
                public void onFlagDefinitionsReceived(List<JsonNode> d) { throw new IllegalStateException("down"); }
            };
            try (Lucida lc = new Lucida("lc_pk_x", o)) {
                assertTrue(lc.reloadFlags());
                assertTrue(lc.isEnabled("on", FlagContext.of("u")));
            }
        }
    }

    @Test
    void remoteConfigPayloadUsesTheSecretKey() throws Exception {
        try (FakeServer srv = new FakeServer()) {
            srv.remoteConfigReply = "{\"key\":\"a/b\",\"enabled\":true,\"payload\":{\"x\":1}}";
            Lucida.Options o = new Lucida.Options();
            o.host = srv.host();
            o.secretKey = "lc_sk_x";
            o.flushInterval = Duration.ofHours(1);
            try (Lucida lc = new Lucida("lc_pk_x", o)) {
                JsonNode p = lc.getRemoteConfigPayload("a/b");
                assertNotNull(p);
                assertEquals(1, p.get("x").asInt());
                int i = srv.rawPaths.indexOf("/v1/flags/a%2Fb/remote-config");
                assertTrue(i >= 0);
                assertEquals("Bearer lc_sk_x", srv.auth.get(i));
                srv.remoteConfigReply = null;
                assertNull(lc.getRemoteConfigPayload("missing"));
            }
            try (Lucida lc = new Lucida("lc_pk_x")) {
                assertThrows(LucidaException.class, () -> lc.getRemoteConfigPayload("cfg"));
            }
        }
    }
}
