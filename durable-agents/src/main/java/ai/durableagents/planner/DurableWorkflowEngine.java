package ai.durableagents.planner;

import dev.restate.sdk.ObjectContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Executes a DurablePlanner pipeline inside a Restate handler.
 *
 * Each agent invocation is wrapped in ctx.run("step-N-agentName", ...) so
 * that completed steps are journaled and skipped on crash recovery (replay).
 *
 * The step counter is a plain int — deterministic because the planner always
 * produces the same sequence given the same scope state.
 */
public class DurableWorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(DurableWorkflowEngine.class);

    /**
     * Runs the planner pipeline to completion.
     *
     * @param ctx        Restate ObjectContext (provides ctx.run journaling)
     * @param planner    The root planner (typically a CompositePlanner)
     * @param agents     Registry mapping agent name → function(scope) → output string
     * @param scope      Initial scope (may already contain claim data)
     * @return           The final scope after all agents have run
     */
    public AgenticScope run(
            ObjectContext ctx,
            DurablePlanner planner,
            Map<String, Function<AgenticScope, String>> agents,
            AgenticScope scope) {

        int stepCounter = 1;

        while (!planner.isDone(scope)) {
            List<String> nextAgents = planner.nextAgents(scope);

            for (String agentName : nextAgents) {
                Function<AgenticScope, String> agent = agents.get(agentName);
                if (agent == null) {
                    throw new IllegalArgumentException("No agent registered for name: " + agentName);
                }

                final String stepName = "step-" + stepCounter + "-" + agentName;
                final AgenticScope scopeSnapshot = scope;
                stepCounter++;

                log.info("Executing {}", stepName);

                String result = ctx.run(stepName, String.class, () -> agent.apply(scopeSnapshot));

                scope.put(agentName + "_output", result);
                log.info("{} completed: {}", stepName, result.substring(0, Math.min(80, result.length())));
            }
        }

        return scope;
    }
}
