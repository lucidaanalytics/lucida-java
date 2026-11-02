package dev.lucida;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** AI Config helper: completion and agent modes, placeholder filling, fallback (P-M49). */
class AiConfigTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static final String AGENT = "{\"model\":\"m-large\",\"mode\":\"agent\",\"version\":2,\"instructions\":\"Help {{name}} {{missing}}\","
            + "\"tools\":[{\"name\":\"search_docs\",\"parameters\":{\"type\":\"object\"}}]}";
    private static final AiConfig FALLBACK = AiConfig.completion("m-small", List.of(Map.of("role", "system", "content", "Hi {{name}}")));

    @Test
    void rendersAgentCompletionAndFallback() throws Exception {
        FlagResult agent = new FlagResult("a", "condition_match", 0, "a", M.readTree(AGENT));
        AiConfig c = AiConfig.from(agent, FALLBACK, Map.of("name", "Ada"));
        assertTrue(c.enabled());
        assertEquals("agent", c.mode());
        assertEquals(2, c.version());
        assertEquals("Help Ada {{missing}}", c.instructions());
        assertEquals("search_docs", c.tools().get(0).get("name"));
        assertTrue(c.messages().isEmpty());

        FlagResult chat = new FlagResult("b", "condition_match", 0, "b",
                M.readTree("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi {{name}}\"}]}"));
        AiConfig d = AiConfig.from(chat, FALLBACK, Map.of("name", "Ada"));
        assertEquals("completion", d.mode());
        assertEquals("Hi Ada", d.messages().get(0).get("content"));
        assertNull(d.instructions());

        AiConfig off = AiConfig.from(null, FALLBACK, Map.of("name", "Ada"));
        assertFalse(off.enabled());
        assertEquals("m-small", off.model());
        assertEquals("Hi Ada", off.messages().get(0).get("content"));
        assertEquals("agent", AiConfig.from(null, AiConfig.agent("x", "Be brief", List.of()), null).mode());
    }

    @Test
    void clientServesTheVariation() {
        Lucida.Options o = new Lucida.Options();
        o.testModeFixtureJson = "{\"flags\":[{\"key\":\"assistant\",\"type\":\"multivariate\",\"active\":true,\"salt\":\"s\","
                + "\"aggregation\":{\"kind\":\"person\"},\"variants\":[{\"key\":\"agent\",\"rollout_pct\":100,\"payload\":" + AGENT + "}],"
                + "\"conditions\":[{\"rollout_pct\":100}]}]}";
        try (Lucida lc = new Lucida("lc_pk_x", o)) {
            AiConfig c = lc.getAiConfig("assistant", FlagContext.of("u"), FALLBACK, Map.of("name", "Ada"));
            assertTrue(c.enabled());
            assertEquals("agent", c.variation());
            assertEquals("Help Ada {{missing}}", c.instructions());
        }
    }
}
