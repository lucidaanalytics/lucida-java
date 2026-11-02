package dev.lucida;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Init-time fallbacks, the details read and the change / stale hooks (N-EXP-16). */
class FallbackTest {
    private static final String REMOTE = "{\"request_id\":\"r9\",\"flags\":{\"dark\":{\"value\":\"b\",\"variant\":\"b\",\"reason\":\"condition_match\",\"condition_index\":2}}}";

    private static Lucida.Options opts(String host) {
        Lucida.Options o = new Lucida.Options();
        o.host = host;
        o.flushInterval = Duration.ofHours(1);
        o.fallbacks = Map.of("dark", false, "theme", "blue");
        return o;
    }

    @Test
    void networkFailureAndMissingFlagServeFallbackWithoutExposure() throws Exception {
        String dead;
        try (FakeServer srv = new FakeServer()) {
            dead = srv.host();
        }
        try (Lucida lc = new Lucida("lc_pk_x", opts(dead))) {
            FlagResult r = lc.getFeatureFlagDetails("theme", FlagContext.of("u"));
            assertEquals("blue", r.value());
            assertEquals("fallback", r.reason());
            assertTrue(r.isFallback());
            assertEquals(Boolean.FALSE, lc.getFeatureFlag("dark", FlagContext.of("u")));
            assertNull(lc.getFeatureFlag("other", FlagContext.of("u"))); // no fallback: old behaviour
        }
    }

    @Test
    void servedFlagKeepsServerValueReasonAndRequestId() throws Exception {
        try (FakeServer srv = new FakeServer()) {
            srv.remoteReply = REMOTE;
            try (Lucida lc = new Lucida("lc_pk_x", opts(srv.host()))) {
                FlagResult r = lc.getFeatureFlagDetails("dark", FlagContext.of("u"));
                assertEquals("b", r.value());
                assertEquals("condition_match", r.reason());
                assertEquals(2, r.conditionIndex());
                assertEquals("r9", r.requestId());
                assertFalse(r.evaluatedLocally());
            }
        }
    }

    @Test
    void changeAndStaleHooksFire() throws Exception {
        try (FakeServer srv = new FakeServer()) {
            Lucida.Options o = opts(srv.host());
            o.secretKey = "lc_sk_x";
            try (Lucida lc = new Lucida("lc_pk_x", o)) {
                AtomicInteger changed = new AtomicInteger(), stale = new AtomicInteger();
                lc.onFlagsChange(changed::incrementAndGet);
                lc.onFlagsStale(stale::incrementAndGet);
                assertTrue(lc.reloadFlags());
                assertEquals(1, changed.get());
                assertEquals(0, stale.get());
            }
        }
        String dead;
        try (FakeServer srv = new FakeServer()) {
            dead = srv.host();
        }
        Lucida.Options o = opts(dead);
        o.secretKey = "lc_sk_x";
        try (Lucida lc = new Lucida("lc_pk_x", o)) {
            AtomicInteger stale = new AtomicInteger();
            lc.onFlagsStale(stale::incrementAndGet);
            assertFalse(lc.reloadFlags());
            assertEquals(1, stale.get());
        }
    }
}
