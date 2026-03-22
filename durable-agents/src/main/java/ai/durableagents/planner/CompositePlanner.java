package ai.durableagents.planner;

import java.util.List;

/**
 * Chains multiple planners as sequential stages. Runs each stage to completion
 * before advancing to the next. Done when all stages are done.
 */
public class CompositePlanner implements DurablePlanner {

    private final List<DurablePlanner> stages;
    private int stageIndex = 0;

    public CompositePlanner(List<DurablePlanner> stages) {
        this.stages = List.copyOf(stages);
    }

    public CompositePlanner(DurablePlanner... stages) {
        this(List.of(stages));
    }

    @Override
    public List<String> nextAgents(AgenticScope scope) {
        // Advance past any stages that are already done
        while (stageIndex < stages.size() && stages.get(stageIndex).isDone(scope)) {
            stageIndex++;
        }
        if (stageIndex >= stages.size()) return List.of();
        return stages.get(stageIndex).nextAgents(scope);
    }

    @Override
    public boolean isDone(AgenticScope scope) {
        // Flush any trailing done stages
        while (stageIndex < stages.size() && stages.get(stageIndex).isDone(scope)) {
            stageIndex++;
        }
        return stageIndex >= stages.size();
    }

    @Override
    public void reset() {
        stageIndex = 0;
        stages.forEach(DurablePlanner::reset);
    }
}
