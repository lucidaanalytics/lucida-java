package dev.lucida;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClientTest {
    private static Lucida.Options opts(FakeServer srv) {
        Lucida.Options o = new Lucida.Options();
        o.host = srv.host();
        o.flushInterval = Duration.ofHours(1);
        o.retryBase = Duration.ofMillis(1);
        return o;
    }

    @Test
    void testModeKeepsEventsAndNeverTouchesTheNetwork() {
        Lucida.Options o = new Lucida.Options();
        o.testModeFixtureJson = FakeServer.DEFS;
        try (Lucida lc = new Lucida("lc_pk_x", o)) {
            lc.capture("u1", "purchase", null);
            lc.identify("u1", Map.of("plan", "pro"), null);
            assertEquals(List.of("purchase", "$identify"), lc.captured().stream().map(e -> e.get("event").asText()).toList());
            assertTrue(lc.isTestMode());
            assertTrue(lc.isEnabled("on", FlagContext.of("u1")));
            assertNull(lc.evaluate("missing", FlagContext.of("u1")));
        }
    }

    @Test
    void batchRetriesRetryableStatusesThenDeliversWithTheSameUuid() throws Exception {
        try (FakeServer srv = new FakeServer()) {
            srv.statuses.add(503);
            srv.statuses.add(429);
            try (Lucida lc = new Lucida("lc_pk_x", opts(srv))) {
                lc.capture("u1", "a", null, new Lucida.Extras(null, null, "01890a5d-ac96-7ab2-80e2-4536629c90de", null, null));
                assertTrue(lc.flush());
            }
            assertEquals(3, srv.bodies.size());
            srv.bodies.forEach(b -> assertEquals("01890a5d-ac96-7ab2-80e2-4536629c90de", b.get("batch").get(0).get("uuid").asText()));
        }
    }

    @Test
    void clientErrorsAreNotRetriedAndCountAsDropped() throws Exception {
        try (FakeServer srv = new FakeServer()) {
            srv.statuses.add(400);
            try (Lucida lc = new Lucida("lc_pk_x", opts(srv))) {
                lc.capture("u1", "a", null);
                assertFalse(lc.flush());
                assertEquals(1, lc.droppedEvents());
            }
            assertEquals(1, srv.bodies.size());
        }
    }

    @Test
    void largeBatchesAreGzipped() throws Exception {
        try (FakeServer srv = new FakeServer(); Lucida lc = new Lucida("lc_pk_x", opts(srv))) {
            lc.capture("u1", "big", Map.of("blob", "x".repeat(3000)));
            lc.flush();
            assertTrue(srv.queries.get(0).contains("compression=gzip"));
            assertEquals(3000, srv.bodies.get(0).get("batch").get(0).get("properties").get("blob").asText().length());
        }
    }

    @Test
    void fullQueueDropsAndDisabledSendsNothing() throws Exception {
        try (FakeServer srv = new FakeServer()) {
            Lucida.Options o = opts(srv);
            o.maxQueueSize = 2;
            o.flushAt = 100;
            try (Lucida lc = new Lucida("k", o)) {
                for (int i = 0; i < 3; i++) lc.capture("u", "e", null);
                assertEquals(1, lc.droppedEvents());
            }
            Lucida.Options off = opts(srv);
            off.disabled = true;
            int before = srv.bodies.size();
            try (Lucida lc = new Lucida("k", off)) {
                lc.capture("u", "e", null);
                assertTrue(lc.flush());
            }
            assertEquals(before, srv.bodies.size());
        }
    }

    @Test
    void reloadFlagsNeedsSecretKeyAndLoadsDefinitions() throws Exception {
        try (FakeServer srv = new FakeServer()) {
            try (Lucida lc = new Lucida("k", opts(srv))) {
                assertFalse(lc.reloadFlags());
            }
            Lucida.Options o = opts(srv);
            o.secretKey = "lc_sk_x";
            try (Lucida lc = new Lucida("k", o)) {
                assertTrue(lc.reloadFlags());
                assertTrue(lc.isEnabled("on", FlagContext.of("u1")));
            }
            assertTrue(srv.auth.contains("Bearer lc_sk_x"));
        }
    }
}
