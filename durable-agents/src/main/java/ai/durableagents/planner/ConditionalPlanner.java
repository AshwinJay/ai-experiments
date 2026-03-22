package ai.durableagents.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Routes to one of several sub-planners based on predicates evaluated against
 * the scope. The first matching predicate wins. Falls back to an otherwise
 * planner if no predicate matches.
 *
 * The routing decision is made once (on first call to nextAgents) and then
 * delegates entirely to the selected sub-planner for the rest of its lifetime.
 */
public class ConditionalPlanner implements DurablePlanner {

    private record Branch(Predicate<AgenticScope> condition, DurablePlanner planner) {}

    private final List<Branch> branches = new ArrayList<>();
    private DurablePlanner otherwise = null;
    private DurablePlanner selected = null;

    public ConditionalPlanner when(Predicate<AgenticScope> condition, DurablePlanner planner) {
        branches.add(new Branch(condition, planner));
        return this;
    }

    /** Convenience: route to a single agent (wrapped in SequentialPlanner). */
    public ConditionalPlanner when(Predicate<AgenticScope> condition, String agentName) {
        return when(condition, new SequentialPlanner(agentName));
    }

    public ConditionalPlanner otherwise(DurablePlanner planner) {
        this.otherwise = planner;
        return this;
    }

    public ConditionalPlanner otherwise(String agentName) {
        return otherwise(new SequentialPlanner(agentName));
    }

    private DurablePlanner resolve(AgenticScope scope) {
        if (selected != null) return selected;
        for (Branch branch : branches) {
            if (branch.condition().test(scope)) {
                selected = branch.planner();
                return selected;
            }
        }
        if (otherwise != null) {
            selected = otherwise;
            return selected;
        }
        // No branch matched and no otherwise — treat as done
        selected = new SequentialPlanner(List.of());
        return selected;
    }

    @Override
    public List<String> nextAgents(AgenticScope scope) {
        return resolve(scope).nextAgents(scope);
    }

    @Override
    public boolean isDone(AgenticScope scope) {
        return resolve(scope).isDone(scope);
    }

    @Override
    public void reset() {
        selected = null;
        branches.forEach(b -> b.planner().reset());
        if (otherwise != null) otherwise.reset();
    }
}
