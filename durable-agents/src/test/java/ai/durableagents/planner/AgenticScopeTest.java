package ai.durableagents.planner;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgenticScopeTest {

    @Test
    void putAndGet() {
        AgenticScope scope = new AgenticScope();
        scope.put("key", "value");
        assertEquals("value", scope.get("key"));
    }

    @Test
    void getMissingKeyReturnsNull() {
        AgenticScope scope = new AgenticScope();
        assertNull(scope.get("missing"));
    }

    @Test
    void getOrDefault() {
        AgenticScope scope = new AgenticScope();
        assertEquals("fallback", scope.getOrDefault("missing", "fallback"));
        scope.put("k", "v");
        assertEquals("v", scope.getOrDefault("k", "fallback"));
    }

    @Test
    void containsKey() {
        AgenticScope scope = new AgenticScope();
        assertFalse(scope.containsKey("k"));
        scope.put("k", "v");
        assertTrue(scope.containsKey("k"));
    }

    @Test
    void getDataReturnsCopy() {
        AgenticScope scope = new AgenticScope();
        scope.put("a", "1");
        Map<String, String> data = scope.getData();
        assertEquals(Map.of("a", "1"), data);
        assertThrows(UnsupportedOperationException.class, () -> data.put("b", "2"));
    }

    @Test
    void toContextStringEmpty() {
        AgenticScope scope = new AgenticScope();
        assertEquals("(no prior context)", scope.toContextString());
    }

    @Test
    void toContextStringWithEntries() {
        AgenticScope scope = new AgenticScope();
        scope.put("risk", "low");
        String ctx = scope.toContextString();
        assertTrue(ctx.contains("risk: low"));
    }

    @Test
    void constructorWithMap() {
        AgenticScope scope = new AgenticScope(Map.of("x", "y"));
        assertEquals("y", scope.get("x"));
    }
}
