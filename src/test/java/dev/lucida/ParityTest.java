package dev.lucida;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Polling, remote fallback, payload accessors, delivery hooks, import and personless events. */
class ParityTest {
    /** "on" is local; "server" needs a cohort (undecidable locally); "cfg" is a json flag with a payload. */
    private static final String DEFS = "{\"flags\":["
            + "{\"key\":\"on\",\"type\":\"boolean\",\"active\":true,\"salt\":\"s\",\"conditions\":[{\"filters\":[],\"rollout_pct\":100}]},"
            + "{\"key\":\"cfg\",\"type\":\"json\",\"active\":true,\"salt\":\"s\",\"payload\":{\"a\":1},\"conditions\":[{\"filters\":[],\"rollout_pct\":100}]},"
            + "{\"key\":\"server\",\"type\":\"boolean\",\"active\":true,\"salt\":\"s\",\"conditions\":[{\"filters\":[{\"type\":\"cohort\",\"id\":\"c1\"}],\"rollout_pct\":100}]}"
            + "]}";
    private static final String REMOTE = "{\"request_id\":\"r1\",\"flags\":{\"server\":{\"value\":\"b\",\"payload\":{\"x\":2}},\"extra\":{\"enabled\":true}}}";

    private static Lucida.Options opts(FakeServer srv) {
        Lucida.Options o = new Lucida.Options();
        o.host = srv.host();
        o.flushInterval = Duration.ofHours(1);
        o.retryBase = Duration.ofMillis(1);
        return o;
    }

    private static Lucida withDefs(Lucida.Options o) {
        Lucida lc = new Lucida("lc_pk_x", o);
        lc.setFlagDefinitions(DEFS);
        return lc;
    }

    @Test
    void pollingFetchesAtOnceFloorsTheIntervalAndSendsIfNoneMatch() throws Exception {
        assertEquals(Duration.ofSeconds(5), FlagSync.clampInterval(Duration.ofMillis(1)));
        assertEquals(Duration.ofSeconds(60), FlagSync.clampInterval(Duration.ofSeconds(60)));
        try (FakeServer srv = new FakeServer()) {
            Lucida.Options o = opts(srv);
            o.secretKey = "lc_sk_x";
            o.flagPollInterval = Duration.ofMillis(1);
            try (Lucida lc = new Lucida("k", o)) {
                // the server records the path before the client has applied the response, so wait for the flag itself
                for (int i = 0; i < 250 && !lc.isEnabled("on", FlagContext.of("u1")); i++) Thread.sleep(20);
                assertEquals("/v1/flags/definitions", srv.paths.get(0));
                assertTrue(lc.isEnabled("on", FlagContext.of("u1")));
                assertTrue(lc.reloadFlags()); // 304 still counts as current
            }
            assertEquals("null", srv.ifNoneMatch.get(0));
            assertTrue(srv.ifNoneMatch.contains("\"v1\""));
            int calls = srv.paths.size();
            Thread.sleep(150);
            assertEquals(calls, srv.paths.size()); // stopped on close
        }
    }

    @Test
    void undecidableFlagsFallBackToTheServerUnlessOnlyLocal() throws Exception {
        try (FakeServer srv = new FakeServer()) {
            srv.remoteReply = REMOTE;
            try (Lucida lc = withDefs(opts(srv))) {
                FlagContext ctx = FlagContext.of("u1");
                FlagResult r = lc.getFlagResult("server", ctx);
                assertEquals("b", r.value());
                assertEquals("remote", r.reason());
                assertEquals(2, r.payload().get("x").asInt());
                assertEquals("b", lc.getFeatureFlag("server", ctx));
                JsonNode body = srv.bodies.get(0);
                assertEquals("lc_pk_x", body.get("api_key").asText());
                assertEquals("u1", body.get("distinct_id").asText());
                assertEquals("server", body.get("flag_keys").get(0).asText());
                int before = srv.bodies.size();
                assertEquals(Boolean.TRUE, lc.getFeatureFlag("on", ctx)); // local, no call
                assertEquals(before, srv.bodies.size());
                assertNull(lc.getFlagResult("server", ctx, true));
            }
            Lucida.Options local = opts(srv);
            local.onlyEvaluateLocally = true;
            int before = srv.bodies.size();
            try (Lucida lc = withDefs(local)) {
                assertNull(lc.getFeatureFlag("server", FlagContext.of("u1")));
                assertEquals(before, srv.bodies.size());
            }
        }
    }

    @Test
    void getAllFlagsMergesLocalAndRemoteAndPayloads() throws Exception {
        try (FakeServer srv = new FakeServer()) {
            srv.remoteReply = REMOTE;
            try (Lucida lc = withDefs(opts(srv))) {
                Map<String, Object> all = lc.getAllFlags(FlagContext.of("u1"));
                assertEquals(Boolean.TRUE, all.get("on"));
                assertEquals("b", all.get("server"));
                assertEquals(Boolean.TRUE, all.get("extra"));
                Lucida.FlagsAndPayloads fp = lc.getAllFlagsAndPayloads(FlagContext.of("u1"));
                assertEquals(1, fp.payloads().get("cfg").get("a").asInt());
                assertEquals(2, fp.payloads().get("server").get("x").asInt());
                assertFalse(fp.payloads().containsKey("on"));
                assertNull(lc.getFeatureFlagPayload("on", FlagContext.of("u1")));
            }
        }
    }

    @Test
    void testModeNeverCallsTheServerForFlags() {
        Lucida.Options o = new Lucida.Options();
        o.testModeFixtureJson = DEFS;
        try (Lucida lc = new Lucida("k", o)) {
            assertNull(lc.getFeatureFlag("server", FlagContext.of("u1")));
            assertEquals(Boolean.TRUE, lc.getAllFlags(FlagContext.of("u1")).get("on"));
        }
    }

    @Test
    void beforeSendEditsDropsAndKeepsTheOriginalWhenItThrows() {
        List<Throwable> errors = new ArrayList<>();
        Lucida.Options o = new Lucida.Options();
        o.testModeFixtureJson = DEFS;
        o.onError = errors::add;
        o.beforeSend = e -> {
            String ev = e.get("event").asText();
            if (ev.equals("drop")) return null;
            if (ev.equals("boom")) throw new IllegalStateException("x");
            ((ObjectNode) e.get("properties")).put("scrubbed", true);
            return e;
        };
        try (Lucida lc = new Lucida("k", o)) {
            lc.capture("u", "edit", null);
            lc.capture("u", "drop", null);
            lc.capture("u", "boom", null);
            assertEquals(List.of("edit", "boom"), lc.captured().stream().map(e -> e.get("event").asText()).toList());
            assertTrue(lc.captured().get(0).get("properties").get("scrubbed").asBoolean());
            assertFalse(lc.captured().get(1).get("properties").has("scrubbed"));
            assertEquals(1, errors.size());
        }
    }

    @Test
    void maxBatchSizeIsIndependentOfFlushAtAndFailuresReachOnError() throws Exception {
        try (FakeServer srv = new FakeServer()) {
            Lucida.Options o = opts(srv);
            o.flushAt = 100;
            o.maxBatchSize = 2;
            try (Lucida lc = new Lucida("k", o)) {
                for (int i = 0; i < 5; i++) lc.capture("u", "e" + i, null);
                assertTrue(lc.flush());
            }
            assertEquals(List.of(2, 2, 1), srv.bodies.stream().map(b -> b.get("batch").size()).toList());
            srv.statuses.add(400);
            List<Throwable> errors = new ArrayList<>();
            Lucida.Options bad = opts(srv);
            bad.onError = errors::add;
            try (Lucida lc = new Lucida("k", bad)) {
                lc.capture("u", "e", null);
                assertFalse(lc.flush());
            }
            assertEquals(1, errors.size());
        }
    }

    @Test
    void importEventsUsesTheSecretKeyKeepsTimestampsAndChunks() throws Exception {
        try (FakeServer srv = new FakeServer()) {
            assertThrows(LucidaException.class, () -> new Lucida("k", opts(srv)).importEvents(List.of()));
            Lucida.Options o = opts(srv);
            o.secretKey = "lc_sk_x";
            o.maxBatchSize = 2;
            Instant t = Instant.parse("2020-01-02T03:04:05.678Z");
            List<Lucida.ImportEvent> evs = new ArrayList<>();
            for (int i = 0; i < 3; i++) evs.add(new Lucida.ImportEvent("u" + i, "old", Map.of("n", i), t, null, null, null));
            try (Lucida lc = new Lucida("k", o)) {
                assertEquals(3, lc.importEvents(evs));
            }
            assertEquals(2, srv.bodies.size());
            assertTrue(srv.paths.stream().allMatch("/v1/s2s/batch"::equals));
            assertTrue(srv.auth.stream().allMatch("Bearer lc_sk_x"::equals));
            assertFalse(srv.bodies.get(0).has("api_key"));
            assertEquals("2020-01-02T03:04:05.678Z", srv.bodies.get(0).get("batch").get(0).get("timestamp").asText());
            srv.statuses.add(500);
            try (Lucida lc = new Lucida("k", o)) {
                assertThrows(LucidaException.class, () -> lc.importEvents(evs));
            }
        }
    }

    @Test
    void identifyIsResentAfterItsBatchWasDropped() throws Exception {
        try (FakeServer srv = new FakeServer()) {
            srv.statuses.add(400);
            try (Lucida lc = new Lucida("k", opts(srv))) {
                lc.identify("u", Map.of("a", 1), null);
                lc.groupIdentify("org", "acme", Map.of("n", 1), null);
                assertFalse(lc.flush());
                lc.identify("u", Map.of("a", 1), null);
                lc.groupIdentify("org", "acme", Map.of("n", 1), null);
                assertTrue(lc.flush());
            }
            assertEquals(List.of(2, 2), srv.bodies.stream().map(b -> b.get("batch").size()).toList());
        }
    }

    @Test
    void importEventsAppliesDefaultsAndBeforeSendUnlessRaw() throws Exception {
        try (FakeServer srv = new FakeServer()) {
            Lucida.Options o = opts(srv);
            o.secretKey = "lc_sk_x";
            o.defaultProperties = Map.of("env", "prod");
            o.beforeSend = e -> "drop".equals(e.get("event").asText()) ? null : e;
            try (Lucida lc = new Lucida("k", o)) {
                assertEquals(1, lc.importEvents(List.of(
                        new Lucida.ImportEvent("u", "keep", null, null, null, null, null),
                        new Lucida.ImportEvent("u", "drop", null, null, null, null, null))));
                assertEquals(1, lc.importEventsRaw(List.of(new Lucida.ImportEvent("u", "plain", null, null, null, null, null))));
            }
            assertEquals("prod", srv.bodies.get(0).get("batch").get(0).get("properties").get("env").asText());
            assertFalse(srv.bodies.get(1).get("batch").get(0).get("properties").has("env"));
        }
    }

    @Test
    void personlessEventsFlagThePropertyAndGenerateAUuidId() {
        Lucida.Options o = new Lucida.Options();
        o.testModeFixtureJson = DEFS;
        try (Lucida lc = new Lucida("k", o)) {
            lc.capturePersonless("anon", Map.of("a", 1));
            lc.capture("u1", "named", null, Lucida.Extras.PERSONLESS);
            lc.capture("u1", "normal", null);
            ObjectNode a = lc.captured().get(0);
            assertEquals(4, UUID.fromString(a.get("distinct_id").asText()).version());
            assertFalse(a.get("properties").get("$process_person_profile").asBoolean(true));
            assertEquals("u1", lc.captured().get(1).get("distinct_id").asText());
            assertTrue(lc.captured().get(1).get("properties").has("$process_person_profile"));
            assertFalse(lc.captured().get(2).get("properties").has("$process_person_profile"));
            assertThrows(IllegalArgumentException.class, () -> lc.capture(null, "x", null));
        }
    }
}
