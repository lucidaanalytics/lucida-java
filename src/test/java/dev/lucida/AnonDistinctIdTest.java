package dev.lucida;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The pre-identify id travels with a flag context (sent on remote requests as anon_distinct_id, LC-FLG-34). */
class AnonDistinctIdTest {
    @Test
    void withAnonDistinctIdKeepsTheRestOfTheContext() {
        FlagContext base = FlagContext.of("u1").withAppContexts(List.of("api"));
        assertNull(base.anonDistinctId());
        FlagContext linked = base.withAnonDistinctId("anon-9");
        assertEquals("anon-9", linked.anonDistinctId());
        assertEquals("u1", linked.distinctId());
        assertEquals(List.of("api"), linked.appContexts());
        assertEquals("anon-9", linked.withAppContexts(List.of("web")).anonDistinctId());
    }
}
