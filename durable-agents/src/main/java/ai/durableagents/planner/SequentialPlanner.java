package ai.durableagents.planner;

import java.util.List;

/**
 * Runs a fixed list of agents in order, one at a time.
 * Done when all agents have been yielded.
 */
public class SequentialPlanner implements DurablePlanner {

    private final List<String> agentNames;
    private int index = 0;

    public SequentialPlanner(List<String> agentNames) {
        this.agentNames = List.copyOf(agentNames);
    }

    public SequentialPlanner(String... agentNames) {
        this(List.of(agentNames));
    }

    @Override
    public List<String> nextAgents(AgenticScope scope) {
        if (isDone(scope)) return List.of();
        return List.of(agentNames.get(index++));
    }

    @Override
    public boolean isDone(AgenticScope scope) {
        return index >= agentNames.size();
    }

    @Override
    public void reset() {
        index = 0;
    }
}
