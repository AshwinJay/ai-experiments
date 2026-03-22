package ai.durableagents.planner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompositePlannerTest {

    private static AgenticScope emptyScope() {
        return new AgenticScope();
    }

    @Test
    void runsStagesInOrder() {
        AgenticScope scope = emptyScope();
        CompositePlanner planner = new CompositePlanner(
                new SequentialPlanner("a", "b"),
                new SequentialPlanner("c")
        );

        assertEquals(List.of("a"), planner.nextAgents(scope));
        assertEquals(List.of("b"), planner.nextAgents(scope));
        assertEquals(List.of("c"), planner.nextAgents(scope));
        assertTrue(planner.isDone(scope));
    }

    @Test
    void isDoneWhenAllStagesDone() {
        AgenticScope scope = emptyScope();
        CompositePlanner planner = new CompositePlanner(
                new SequentialPlanner("a"),
                new SequentialPlanner("b")
        );

        assertFalse(planner.isDone(scope));
        planner.nextAgents(scope); // a
        assertFalse(planner.isDone(scope));
        planner.nextAgents(scope); // b
        assertTrue(planner.isDone(scope));
    }

    @Test
    void returnsEmptyAfterAllDone() {
        AgenticScope scope = emptyScope();
        CompositePlanner planner = new CompositePlanner(new SequentialPlanner("a"));
        planner.nextAgents(scope);
        assertTrue(planner.isDone(scope));
        assertEquals(List.of(), planner.nextAgents(scope));
    }

    @Test
    void resetRestoresAllStages() {
        AgenticScope scope = emptyScope();
        CompositePlanner planner = new CompositePlanner(
                new SequentialPlanner("a"),
                new SequentialPlanner("b")
        );

        planner.nextAgents(scope);
        planner.nextAgents(scope);
        assertTrue(planner.isDone(scope));

        planner.reset();
        assertFalse(planner.isDone(scope));
        assertEquals(List.of("a"), planner.nextAgents(scope));
    }

    @Test
    void threeStageWorkflow() {
        AgenticScope scope = new AgenticScope();

        // Simulates the actual claim-processing pipeline shape
        SequentialPlanner triageStage = new SequentialPlanner("triage");
        LoopPlanner reviewLoop = new LoopPlanner(
                s -> Double.parseDouble(s.getOrDefault("quality_score", "0")) >= 0.8,
                5,
                "riskAssess", "qualityReview"
        );
        SequentialPlanner fraudStage = new SequentialPlanner("fraudAnalysis");

        CompositePlanner pipeline = new CompositePlanner(triageStage, reviewLoop, fraudStage);

        // Stage 1
        assertEquals(List.of("triage"), pipeline.nextAgents(scope));

        // Stage 2 — loop until quality_score >= 0.8
        scope.put("quality_score", "0.6");
        assertEquals(List.of("riskAssess"), pipeline.nextAgents(scope));
        assertEquals(List.of("qualityReview"), pipeline.nextAgents(scope));
        // score 0.6 — continue
        scope.put("quality_score", "0.9");
        assertEquals(List.of("riskAssess"), pipeline.nextAgents(scope));
        assertEquals(List.of("qualityReview"), pipeline.nextAgents(scope));
        // score 0.9 — exit loop

        // Stage 3
        assertEquals(List.of("fraudAnalysis"), pipeline.nextAgents(scope));
        assertTrue(pipeline.isDone(scope));
    }

    @Test
    void skipsAlreadyDoneStages() {
        AgenticScope scope = emptyScope();
        // First stage is empty (done immediately), second has work
        CompositePlanner planner = new CompositePlanner(
                new SequentialPlanner(List.of()),
                new SequentialPlanner("b")
        );

        assertEquals(List.of("b"), planner.nextAgents(scope));
        assertTrue(planner.isDone(scope));
    }
}
