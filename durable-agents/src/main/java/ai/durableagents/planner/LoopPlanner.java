package ai.durableagents.planner;

import java.util.List;
import java.util.function.Predicate;

/**
 * Repeats a fixed set of agents in order until an exit condition is met
 * or the maximum iteration count is reached.
 *
 * Each full pass through the agent list counts as one iteration.
 * The exit condition is checked after each full pass.
 */
public class LoopPlanner implements DurablePlanner {

    private final Predicate<AgenticScope> exitCondition;
    private final int maxIterations;
    private final List<String> agentNames;

    private int iteration = 0;
    private int indexInPass = 0;
    private boolean done = false;

    public LoopPlanner(Predicate<AgenticScope> exitCondition, int maxIterations, List<String> agentNames) {
        this.exitCondition = exitCondition;
        this.maxIterations = maxIterations;
        this.agentNames = List.copyOf(agentNames);
    }

    public LoopPlanner(Predicate<AgenticScope> exitCondition, int maxIterations, String... agentNames) {
        this(exitCondition, maxIterations, List.of(agentNames));
    }

    @Override
    public List<String> nextAgents(AgenticScope scope) {
        if (isDone(scope)) return List.of();

        String next = agentNames.get(indexInPass++);

        // End of this pass
        if (indexInPass >= agentNames.size()) {
            indexInPass = 0;
            iteration++;
            // Check exit condition after completing a full pass
            if (exitCondition.test(scope) || iteration >= maxIterations) {
                done = true;
            }
        }

        return List.of(next);
    }

    @Override
    public boolean isDone(AgenticScope scope) {
        return done;
    }

    @Override
    public void reset() {
        iteration = 0;
        indexInPass = 0;
        done = false;
    }
}
