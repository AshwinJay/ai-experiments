package ai.durableagents.planner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConditionalPlannerTest {

    @Test
    void routesToFirstMatchingBranch() {
        AgenticScope scope = new AgenticScope();
        scope.put("category", "medical");

        ConditionalPlanner planner = new ConditionalPlanner()
                .when(s -> "medical".equals(s.get("category")), "medicalAgent")
                .when(s -> "legal".equals(s.get("category")), "legalAgent")
                .otherwise("generalAgent");

        assertEquals(List.of("medicalAgent"), planner.nextAgents(scope));
    }

    @Test
    void routesToSecondBranchWhenFirstFails() {
        AgenticScope scope = new AgenticScope();
        scope.put("category", "legal");

        ConditionalPlanner planner = new ConditionalPlanner()
                .when(s -> "medical".equals(s.get("category")), "medicalAgent")
                .when(s -> "legal".equals(s.get("category")), "legalAgent")
                .otherwise("generalAgent");

        assertEquals(List.of("legalAgent"), planner.nextAgents(scope));
    }

    @Test
    void fallsBackToOtherwise() {
        AgenticScope scope = new AgenticScope();
        scope.put("category", "auto");

        ConditionalPlanner planner = new ConditionalPlanner()
                .when(s -> "medical".equals(s.get("category")), "medicalAgent")
                .otherwise("generalAgent");

        assertEquals(List.of("generalAgent"), planner.nextAgents(scope));
    }

    @Test
    void isDoneWhenNoBranchAndNoOtherwise() {
        AgenticScope scope = new AgenticScope();
        ConditionalPlanner planner = new ConditionalPlanner()
                .when(s -> "medical".equals(s.get("category")), "medicalAgent");

        // No match, no otherwise — treated as done
        assertTrue(planner.isDone(scope));
    }

    @Test
    void selectionIsStable() {
        AgenticScope scope = new AgenticScope();
        scope.put("category", "medical");

        ConditionalPlanner planner = new ConditionalPlanner()
                .when(s -> "medical".equals(s.get("category")), "medicalAgent")
                .otherwise("generalAgent");

        planner.nextAgents(scope);
        // Change scope — selection should not change (already resolved)
        scope.put("category", "legal");
        assertTrue(planner.isDone(scope)); // medicalAgent was sequential with 1 agent, now done
    }

    @Test
    void resetClearsSelection() {
        AgenticScope scope = new AgenticScope();
        scope.put("category", "medical");

        ConditionalPlanner planner = new ConditionalPlanner()
                .when(s -> "medical".equals(s.get("category")), "medicalAgent")
                .otherwise("generalAgent");

        planner.nextAgents(scope); // selects medicalAgent
        planner.reset();

        scope.put("category", "other");
        assertEquals(List.of("generalAgent"), planner.nextAgents(scope));
    }

    @Test
    void routesToSubPlanner() {
        AgenticScope scope = new AgenticScope();
        scope.put("type", "complex");

        SequentialPlanner subPlanner = new SequentialPlanner("stepA", "stepB");
        ConditionalPlanner planner = new ConditionalPlanner()
                .when(s -> "complex".equals(s.get("type")), subPlanner)
                .otherwise("simpleAgent");

        assertEquals(List.of("stepA"), planner.nextAgents(scope));
        assertFalse(planner.isDone(scope));
        assertEquals(List.of("stepB"), planner.nextAgents(scope));
        assertTrue(planner.isDone(scope));
    }
}
