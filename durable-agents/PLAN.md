# Plan

## What Was Built

Insurance claims processing with a composable durable planner system:

- **SequentialPlanner, LoopPlanner, ConditionalPlanner, CompositePlanner** — orchestration patterns
- **DurableWorkflowEngine** — executes planner decisions, wraps each agent in `ctx.run()`
- **4 langchain4j AI Services** — triage, risk assessment, quality review, fraud analysis
- **ClaimProcessor** — Restate `@VirtualObject` with human-in-the-loop via `ctx.awakeable()`

## Background: Patterns Considered

### Pattern 1: Restate as Outer Shell (reference, not in this repo)

Each agent call hand-wired into its own `ctx.run()` directly in the handler. Simple but orchestration logic is imperative code in the handler — no composability.

### Pattern 2: Durable Planner (this repo)

Custom `DurablePlanner` interface + `DurableWorkflowEngine`. Composable planners that are insulated from Restate wiring. Changing workflow shape = swapping Planner objects.

### Pattern 3: Decomposed Restate Services (discussed, not built)

Each agent as its own Restate `@Service`. Orchestrator Virtual Object calls them via durable RPC. Cleanest separation but doesn't use langchain4j orchestration patterns at all.

---

## Next Steps

### Immediate

1. **docker-compose.yml** — start Restate Server + the Java service together with auto-registration. Removes the manual curl registration step.

3. **Integration tests** — use Restate's test utilities (`RestateRunner` / testcontainers):
   - Happy path: low-value claim auto-approves through all agents
   - Human review: high-value claim suspends, resolves on awakeable
   - Crash recovery: kill mid-workflow, restart, verify replay skips completed agents

### Short-Term Extensions

4. **SupervisorPlanner** — LLM-driven planning where the supervisor's decision is itself wrapped in `ctx.run()` so it's journaled and deterministically replayed.

5. **ParallelPlanner** — return multiple agents from a single planner step; engine uses `ctx.runAsync()` + `DurableFuture.all()` for concurrent durable execution.

6. **AgenticScopeStore backed by Restate K/V** — bridge Pattern 2's `AgenticScope` into langchain4j's SPI so langchain4j-agentic's built-in patterns can run with durable state.

### Medium-Term: Clara Rules Integration

7. Integrate **Clara Rules** (Clojure forward-chaining rules engine) as a deterministic policy layer:
   - Create a `ClaraRuleEngine` Java wrapper (via `clojure.java.api.Clojure`)
   - Add Clojure + Clara as Maven dependencies (Clojars)
   - Replace `needsHumanReview()` if/else with a Clara rule session
   - Wrap rule evaluation in `ctx.run()` for replay consistency

   The pattern: "AI proposes, rules dispose" — LLM agents generate candidate outputs, Clara enforces deterministic business policies (regulatory, compliance).

8. **Evaluate DataScript** — only if the claims model becomes genuinely relational (multi-claim fraud detection, entity relationship tracking). Use plain Java maps first; add DataScript when you need Datalog queries over agent outputs. Consider XTDB as a more production-oriented alternative if persistence is needed.

### Long-Term

9. **Monitor langchain4j Issue #4637** — if built-in durable execution lands in langchain4j, `DurableWorkflowEngine` may become unnecessary.

10. **Observability** — bridge Restate's built-in trace propagation with langchain4j's `AgentListener`/`AgentMonitor` for end-to-end traces across journaled steps + agent invocations.

11. **Saga-style compensation** — if fraud analysis fails after risk assessment succeeded, use Restate's error handling + langchain4j's `ErrorRecoveryResult` pattern for rollback.

---

## Open Questions

1. **Parallel durability in Restate** — verify exact API for `ctx.runAsync()` fan-out + join (`DurableFuture.all()`) in Java SDK 2.4.1.

2. **Clara Rules Clojure version compatibility** — verify Clara's required Clojure version range against JVM version and other dependencies.

3. **langchain4j-agentic API stability** — the module is explicitly experimental. Pattern 2's `DurablePlanner` is insulated (our own interface), but agents still use langchain4j AI Services which may change.
