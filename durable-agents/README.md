# Durable Agentic AI in Java

Insurance claims processing demo using **langchain4j** AI Services + **Restate** durable execution.

Each sub-agent invocation is individually journaled via `ctx.run()`. On crash/restart, completed agent steps replay from the journal (no LLM call); execution resumes from the failure point.

## Architecture

```
Restate Handler (Virtual Object keyed by claim-id)
│
├── DurableWorkflowEngine
│     │
│     ├── CompositePlanner (decides what runs next)
│     │     ├── SequentialPlanner: [triage]
│     │     ├── LoopPlanner: [riskAssess, qualityReview] until score ≥ 0.8
│     │     └── SequentialPlanner: [fraudAnalysis]
│     │
│     └── For each agent the planner selects:
│           ctx.run("step-N-agentName", () -> agent.invoke(...))
│           │
│           ├── FRESH RUN: executes lambda, calls LLM, journals result
│           └── REPLAY:    returns journaled result, no LLM call
│
└── Post-workflow: human review via ctx.awakeable() (if needed)
```

### Composable Planners

```java
// Sequential: agents run in order once
SequentialPlanner seq = new SequentialPlanner("triage");

// Loop: repeats until condition met
LoopPlanner loop = new LoopPlanner(
    scope -> parseScore(scope) >= 0.8,  // exit condition
    5,                                    // max iterations
    "riskAssess", "qualityReview"
);

// Conditional: routes based on scope state
ConditionalPlanner cond = new ConditionalPlanner()
    .when(scope -> "medical".equals(scope.get("category")), "medicalAgent")
    .otherwise("generalAgent");

// Composite: chain planners as stages
CompositePlanner pipeline = new CompositePlanner(triageStage, reviewLoop, fraudStage);
```

### How Replay Works

```
FRESH RUN:
  step-1-triage        → LLM call → journals "CATEGORY: minor_damage..."
  step-2-riskAssess    → LLM call → journals "RISK_LEVEL: moderate..."
  step-3-qualityReview → LLM call → journals "0.65"
  step-4-riskAssess    → LLM call → journals "RISK_LEVEL: moderate..." (refined)
  step-5-qualityReview → CRASH

REPLAY (after restart):
  step-1-triage        → journal hit → returns instantly, no LLM
  step-2-riskAssess    → journal hit → returns instantly, no LLM
  step-3-qualityReview → journal hit → returns instantly, no LLM
  step-4-riskAssess    → journal hit → returns instantly, no LLM
  step-5-qualityReview → no journal → LIVE EXECUTION → calls LLM
  step-6-fraudAnalysis → LIVE EXECUTION → calls LLM
```

### Determinism Invariant

Planners must produce the same agent sequence on replay as on the original run (same scope state → same decisions). Unsafe sources require special handling:

- Current time → journal via `ctx.run()`
- LLM-driven planning (supervisor) → wrap the LLM call in `ctx.run()` too
- External state → fetch via `ctx.run()` so the fetch is journaled

## Project Structure

```
src/main/java/ai/durableagents/
├── App.java                              # Entry point (RestateHttpServer on :9080)
├── model/
│   ├── ClaimRequest.java                 # Input record
│   └── ClaimDecision.java                # Output record (decision, riskLevel, fraudFlag)
├── agents/
│   ├── AgentFactory.java                 # Builds langchain4j AI Services; selects provider via LLM_PROVIDER
│   ├── TriageAgent.java                  # Classifies claim category + severity
│   ├── RiskAssessmentAgent.java          # Evaluates risk level and score
│   ├── FraudAnalysisAgent.java           # Detects fraud indicators
│   └── QualityReviewerAgent.java         # Scores assessment quality (0.0–1.0)
├── planner/
│   ├── AgenticScope.java                 # Shared state: Map<String,String> with toContextString()
│   ├── DurablePlanner.java               # Interface: nextAgents(), isDone(), reset()
│   ├── SequentialPlanner.java
│   ├── ConditionalPlanner.java
│   ├── LoopPlanner.java
│   ├── CompositePlanner.java
│   └── DurableWorkflowEngine.java        # ctx.run("step-N-agentName") per agent invocation
└── restate/
    └── ClaimProcessor.java               # @VirtualObject: 3-stage pipeline + awakeable
```

## Build & Test

```bash
mvn clean package -DskipTests       # build fat JAR
mvn test                            # run all tests
mvn test -Dtest=SequentialPlannerTest                  # single test class
mvn test -Dtest=LoopPlannerTest#testExitCondition      # single test method
```

## Running

```bash
# 1. Start Restate
docker run --rm -p 8080:8080 -p 9070:9070 -p 9071:9071 \
  --add-host=host.docker.internal:host-gateway \
  docker.restate.dev/restatedev/restate:latest

# 2. Build
mvn clean package -DskipTests

# 3. Run — choose provider via LLM_PROVIDER

# Option A: OpenAI
export LLM_PROVIDER=openai
export OPENAI_API_KEY="sk-..."
java -jar target/durable-agents-1.0-SNAPSHOT.jar

# Option B: Anthropic (Claude)
export LLM_PROVIDER=anthropic
export ANTHROPIC_API_KEY="sk-ant-..."
java -jar target/durable-agents-1.0-SNAPSHOT.jar

# 4. Register with Restate
curl http://localhost:9070/deployments \
  -H 'content-type: application/json' \
  -d '{"uri": "http://host.docker.internal:9080/"}'

# 5. Increase inactivity timeout for LLM calls
curl -X PATCH http://localhost:9070/services/ClaimProcessor \
  -H 'content-type: application/json' \
  -d '{"inactivity_timeout": "5m"}'

# 6. Submit a claim
curl -X POST http://localhost:8080/ClaimProcessor/claim-001/processClaim \
  -H 'content-type: application/json' \
  -d '{
    "claimId": "claim-001",
    "customerId": "cust-42",
    "claimType": "auto",
    "claimAmount": 4500,
    "description": "Rear-ended at a red light. Police report filed. Bumper and tail light damage. Drivable. No injuries.",
    "incidentDate": "2026-03-10"
  }'

# 7. Test crash recovery: submit, wait for logs to show step-3, kill, restart
```

## API Notes (SDK 2.4.1 / langchain4j 1.12.2)

- **`ctx.run` serde:** Use `String.class` directly — `ctx.run("name", String.class, () -> ...)`. `CoreSerdes` does not exist in 2.4.1.
- **`ctx.awakeable`:** Same — `ctx.awakeable(String.class)`.
- **Endpoint builder:** `RestateHttpEndpointBuilder` is deprecated. Use `RestateHttpServer.listen(Endpoint.bind(service), port)`.
- **AiServices:** `.chatModel(model)` — the old `.chatLanguageModel()` was renamed in 1.0.0.
- **Claude provider:** Uses `langchain4j-anthropic` 1.12.2 with `AnthropicChatModel` directly — no OpenAI-compat workaround needed.
