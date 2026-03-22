package ai.durableagents.planner;

import java.util.List;

/**
 * Decides which agents to run next given the current scope state.
 *
 * Implementations must be deterministic: the same scope state must always
 * produce the same agent sequence. This is required for Restate replay
 * correctness — on crash recovery the planner re-runs and must request the
 * same ctx.run() steps in the same order so journal entries line up.
 *
 * Safe basis for decisions: scope state, static config, step counters.
 * Unsafe (require special handling): current time, LLM output, external state.
 */
public interface DurablePlanner {

    /**
     * Returns the next batch of agent names to execute.
     * An empty list means this planner has no more work right now (check isDone).
     */
    List<String> nextAgents(AgenticScope scope);

    /** Returns true when this planner's work is complete. */
    boolean isDone(AgenticScope scope);

    /** Resets internal state so the planner can be reused. */
    void reset();
}
