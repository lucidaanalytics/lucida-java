package dev.lucida;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Super properties, person properties, identify/group dedupe, geoip, evaluate-once snapshot. */
class IdentityTest {
    private static final String DEFS = "{\"flags\":["
            + "{\"key\":\"on\",\"type\":\"boolean\",\"active\":true,\"salt\":\"s\",\"conditions\":[{\"filters\":[],\"rollout_pct\":100}]},"
            + "{\"key\":\"off\",\"type\":\"boolean\",\"active\":false,\"salt\":\"s\",\"conditions\":[{\"filters\":[],\"rollout_pct\":100}]}]}";

    private static Lucida test(Lucida.Options o) {
        o.testModeFixtureJson = DEFS;
        o.onlyEvaluateLocally = true;
        return new Lucida("lc_pk_x", o);
    }

    @Test
    void superPropertiesPerCallWinsAndGeoipDisabled() {
        Lucida.Options o = new Lucida.Options();
        o.disableGeoip = true;
        o.defaultProperties = Map.of("app", "a", "env", "prod");
        try (Lucida lc = test(o)) {
            lc.register(Map.of("app", "b"));
            lc.unregister("env");
            lc.capture("u", "e1", null);
            lc.capture("u", "e2", Map.of("app", "mine"));
            List<ObjectNode> ev = lc.captured();
            assertEquals("b", ev.get(0).get("properties").get("app").asText());
            assertFalse(ev.get(0).get("properties").has("env"));
            assertTrue(ev.get(0).get("properties").get("$geoip_disable").asBoolean());
            assertEquals("mine", ev.get(1).get("properties").get("app").asText());
        }
    }

    @Test
    void personPropertiesAndDedupe() {
        try (Lucida lc = test(new Lucida.Options())) {
            lc.setPersonProperties("u", Map.of("plan", "pro"));
            lc.setPersonPropertiesOnce("u", Map.of("first", "x"));
            for (int i = 0; i < 3; i++) {
                lc.identify("u", Map.of("a", 1), null);
                lc.groupIdentify("org", "acme", Map.of("n", 1), null);
            }
            lc.identify("u", Map.of("a", 2), null);
            List<ObjectNode> ev = lc.captured();
            assertEquals("pro", ev.get(0).get("$set").get("plan").asText());
            assertEquals("x", ev.get(1).get("$set_once").get("first").asText());
            assertEquals(List.of("$set", "$set", "$identify", "$groupidentify", "$identify"), ev.stream().map(e -> e.get("event").asText()).toList());
        }
    }

    @Test
    void evaluateOnceSnapshotAttachesFeatureProps() {
        try (Lucida lc = test(new Lucida.Options())) {
            FlagSnapshot snap = lc.evaluateFlags(FlagContext.of("u"));
            assertTrue(snap.isEnabled("on"));
            assertFalse(snap.isEnabled("off"));
            lc.captureWithFlags("u", "page", Map.of("$feature/on", "mine"), snap);
            ObjectNode p = (ObjectNode) lc.captured().get(0).get("properties");
            assertEquals("mine", p.get("$feature/on").asText());
            assertFalse(p.get("$feature/off").asBoolean());
            assertEquals("[\"on\"]", p.get("$active_feature_flags").toString());
        }
    }
}
