package ai.durableagents.planner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LoopPlannerTest {

    @Test
    void loopsUntilExitConditionMet() {
        AgenticScope scope = new AgenticScope();
        // Exit after "qualityReview" sets quality_score >= 0.8
        LoopPlanner planner = new LoopPlanner(
                s -> {
                    String val = s.getOrDefault("quality_score", "0");
                    return Double.parseDouble(val) >= 0.8;
                },
                5,
                "riskAssess", "qualityReview"
        );

        // Pass 1: score 0.65 — should not exit
        assertEquals(List.of("riskAssess"), planner.nextAgents(scope));
        assertFalse(planner.isDone(scope));
        scope.put("quality_score", "0.65");
        assertEquals(List.of("qualityReview"), planner.nextAgents(scope));
        assertFalse(planner.isDone(scope)); // exit checked after full pass — score is 0.65

        // Pass 2: score 0.85 — should exit after this pass
        assertEquals(List.of("riskAssess"), planner.nextAgents(scope));
        assertFalse(planner.isDone(scope));
        scope.put("quality_score", "0.85");
        assertEquals(List.of("qualityReview"), planner.nextAgents(scope));
        assertTrue(planner.isDone(scope));
    }

    @Test
    void stopsAtMaxIterations() {
        AgenticScope scope = new AgenticScope();
        // Exit condition never true
        LoopPlanner planner = new LoopPlanner(s -> false, 2, "agent");

        // Iteration 1
        assertEquals(List.of("agent"), planner.nextAgents(scope));
        assertFalse(planner.isDone(scope));

        // Iteration 2 — hits maxIterations
        assertEquals(List.of("agent"), planner.nextAgents(scope));
        assertTrue(planner.isDone(scope));

        // No more agents
        assertEquals(List.of(), planner.nextAgents(scope));
    }

    @Test
    void resetRestoresState() {
        AgenticScope scope = new AgenticScope();
        scope.put("quality_score", "0.9");

        LoopPlanner planner = new LoopPlanner(
                s -> Double.parseDouble(s.getOrDefault("quality_score", "0")) >= 0.8,
                5,
                "riskAssess", "qualityReview"
        );

        // Run to completion (1 pass, exit condition met)
        planner.nextAgents(scope); // riskAssess
        planner.nextAgents(scope); // qualityReview — done
        assertTrue(planner.isDone(scope));

        planner.reset();
        assertFalse(planner.isDone(scope));
        assertEquals(List.of("riskAssess"), planner.nextAgents(scope));
    }

    @Test
    void singleAgentLoop() {
        AgenticScope scope = new AgenticScope();
        LoopPlanner planner = new LoopPlanner(s -> s.containsKey("done"), 3, "worker");

        assertEquals(List.of("worker"), planner.nextAgents(scope));
        assertFalse(planner.isDone(scope));

        scope.put("done", "true");
        assertEquals(List.of("worker"), planner.nextAgents(scope));
        assertTrue(planner.isDone(scope));
    }
}
