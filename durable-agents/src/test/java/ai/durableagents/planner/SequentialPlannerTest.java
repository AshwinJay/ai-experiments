package ai.durableagents.planner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SequentialPlannerTest {

    private static AgenticScope emptyScope() {
        return new AgenticScope();
    }

    @Test
    void yieldsAgentsInOrder() {
        SequentialPlanner planner = new SequentialPlanner("a", "b", "c");
        AgenticScope scope = emptyScope();

        assertEquals(List.of("a"), planner.nextAgents(scope));
        assertEquals(List.of("b"), planner.nextAgents(scope));
        assertEquals(List.of("c"), planner.nextAgents(scope));
    }

    @Test
    void isDoneAfterAllAgents() {
        SequentialPlanner planner = new SequentialPlanner("a", "b");
        AgenticScope scope = emptyScope();

        assertFalse(planner.isDone(scope));
        planner.nextAgents(scope);
        assertFalse(planner.isDone(scope));
        planner.nextAgents(scope);
        assertTrue(planner.isDone(scope));
    }

    @Test
    void nextAgentsReturnsEmptyWhenDone() {
        SequentialPlanner planner = new SequentialPlanner("a");
        AgenticScope scope = emptyScope();
        planner.nextAgents(scope);
        assertTrue(planner.isDone(scope));
        assertEquals(List.of(), planner.nextAgents(scope));
    }

    @Test
    void resetRestartsCycle() {
        SequentialPlanner planner = new SequentialPlanner("a", "b");
        AgenticScope scope = emptyScope();

        planner.nextAgents(scope);
        planner.nextAgents(scope);
        assertTrue(planner.isDone(scope));

        planner.reset();
        assertFalse(planner.isDone(scope));
        assertEquals(List.of("a"), planner.nextAgents(scope));
    }

    @Test
    void emptyPlannerIsDoneImmediately() {
        SequentialPlanner planner = new SequentialPlanner(List.of());
        assertTrue(planner.isDone(emptyScope()));
        assertEquals(List.of(), planner.nextAgents(emptyScope()));
    }
}
