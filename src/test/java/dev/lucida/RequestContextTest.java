package dev.lucida;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestContextTest {
    @Test
    void readHeaderIsCaseInsensitiveStripsControlCharsTrimsAndCaps() {
        assertEquals("abc", LucidaRequestContext.readHeader(Map.of("x-lucida-distinct-id", "  a\u0000b\nc "), "X-Lucida-Distinct-Id"));
        assertEquals("v", LucidaRequestContext.readHeader(Map.of("K", List.of("v", "w")), "k"));
        assertNull(LucidaRequestContext.readHeader(Map.of("K", " \t "), "k"));
        assertNull(LucidaRequestContext.readHeader(Map.of("K", List.of()), "k"));
        assertNull(LucidaRequestContext.readHeader(null, "k"));
        assertEquals(1000, LucidaRequestContext.readHeader(Map.of("K", "x".repeat(2000)), "k").length());
    }

    @Test
    void fromHeadersReadsIdsAndHonoursTrustProxy() {
        Map<String, Object> headers = Map.of("X-Lucida-Distinct-Id", "u1", "X-Lucida-Session-Id", "s1",
                "User-Agent", "ua", "X-Forwarded-For", "1.1.1.1, 2.2.2.2");
        Map<String, Object> props = Map.of("$request_path", "/p", "$ip", "9.9.9.9");
        LucidaRequestContext.Data trusted = LucidaRequestContext.fromHeaders(headers, true, props);
        assertEquals("u1", trusted.distinctId());
        assertEquals("s1", trusted.sessionId());
        assertEquals("1.1.1.1", trusted.props().get("$ip"));
        assertEquals("ua", trusted.props().get("$user_agent"));
        assertEquals("9.9.9.9", LucidaRequestContext.fromHeaders(headers, false, props).props().get("$ip"));
    }

    @Test
    void scopesNestRestoreAndFeedCapture() {
        Lucida.Options o = new Lucida.Options();
        o.testModeFixtureJson = "[]";
        try (Lucida lc = new Lucida("k", o)) {
            try (var outer = LucidaRequestContext.with("u1", "s1", Map.of("$request_path", "/a", "k", "ctx"))) {
                try (var inner = LucidaRequestContext.with("u2", null, null)) {
                    assertEquals("u2", LucidaRequestContext.current().distinctId());
                }
                assertEquals("u1", LucidaRequestContext.current().distinctId());
                lc.capture(null, "ev", Map.of("k", "explicit"));
                lc.capture("other", "ev2", null);
            }
            assertNull(LucidaRequestContext.current());
            var e = lc.captured().get(0);
            assertEquals("u1", e.get("distinct_id").asText());
            assertEquals("s1", e.get("properties").get("$session_id").asText());
            assertEquals("/a", e.get("properties").get("$request_path").asText());
            assertEquals("explicit", e.get("properties").get("k").asText());
            assertEquals("other", lc.captured().get(1).get("distinct_id").asText());
        }
    }
}
