# Implementation Plan

## 1. Taskfile.yml (DONE — already written, needs update)
- Tasks: `build`, `test`, `test:it`, `test:all`, `up`, `register`, `down`
- `test:it` uses `mvn verify -Dsurefire.skip=true` (failsafe only)
- `test:all` uses `mvn verify` (surefire + failsafe)

### Container runtime targets (new)
Add `runtime:start` and `runtime:stop` targets that wrap `colima start` / `colima stop`.
Wire them into the IT tasks so the runtime is managed automatically:

```yaml
runtime:start:
  desc: Start the container runtime (Colima)
  cmds:
    - colima start

runtime:stop:
  desc: Stop the container runtime (Colima)
  cmds:
    - colima stop

test:it:
  desc: Run integration tests (starts/stops container runtime automatically)
  env:
    DOCKER_HOST: "unix://{{.HOME}}/.colima/default/docker.sock"
  cmds:
    - task: runtime:start
    - mvn verify -Dsurefire.skip=true
    - task: runtime:stop

test:all:
  desc: Run unit + integration tests (starts/stops container runtime automatically)
  env:
    DOCKER_HOST: "unix://{{.HOME}}/.colima/default/docker.sock"
  cmds:
    - task: runtime:start
    - mvn verify
    - task: runtime:stop
```

Note: if `mvn` fails, `runtime:stop` is still in the `cmds` list so it runs sequentially —
but Taskfile stops on first error by default. Add `ignore_error: true` on the mvn step if
always-stop behaviour is needed.

---

## 2. docker-compose.yml
Single `restate` service for local dev and manual testing.
The integration tests manage their own Restate container via testcontainers — docker-compose is only for manual runs.

```yaml
services:
  restate:
    image: docker.restate.dev/restatedev/restate:latest
    ports: [8080, 9070, 9071]
    extra_hosts: [host.docker.internal:host-gateway]
```

No `app` service — the Java process runs locally (`java -jar`).
`task register` does the curl to register it.
`task up` / `task down` work via the container runtime; assumes `runtime:start` has been called first.

---

## 3. Dockerfile
Only needed if someone wants to run the app fully containerized (not required for integration tests).

```dockerfile
FROM eclipse-temurin:21-jre
COPY target/durable-agents-1.0-SNAPSHOT.jar /app/app.jar
EXPOSE 9080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

---

## 4. pom.xml — new dependencies + failsafe plugin

### New test-scope dependencies
- `dev.restate:sdk-testing:2.4.1` — RestateRunner JUnit 5 extension
- `org.testcontainers:testcontainers:1.19.8` — container lifecycle
- `org.testcontainers:junit-jupiter:1.19.8` — JUnit 5 integration

### New plugin
```xml
<plugin>maven-failsafe-plugin 3.2.5</plugin>
<!-- goals: integration-test + verify -->
<!-- picks up **/*IT.java by default -->
```

---

## 5. AgentFactory.java — add ChatModel constructor

Add a second public constructor for test injection:
```java
public AgentFactory(ChatModel model) {
    this.model = model;
}
```
The existing no-arg constructor (reads env vars) is unchanged and used by App.java.

---

## 6. ClaimProcessor.java — inject AgentFactory via field

Currently: `AgentFactory factory = new AgentFactory();` is a local variable inside `processClaim`.

Change to:
- `private final AgentFactory factory;` field
- Default no-arg constructor: `this.factory = new AgentFactory();`
- Second public constructor: `public ClaimProcessor(AgentFactory factory)`

This lets tests pass a stub factory without touching production code paths.

---

## 7. HappyPathIT.java — first integration test

**Scenario:** Low-value auto claim ($2,500) with no fraud indicators auto-approves without human review.

**Stub model (`RoutingStubChatModel`):**
Routes on `@SystemMessage` text keyword to return deterministic structured responses:
- `"triage specialist"` → `NEEDS_HUMAN_REVIEW: false`
- `"quality assurance reviewer"` → `QUALITY_SCORE: 0.9` (above 0.8 threshold → loop exits after 1 iteration)
- `"fraud"` → `FRAUD_FLAG: false`
- else (risk assessment) → `RECOMMENDATION: APPROVE\nRISK_LEVEL: low`

**Test flow:**
1. `RestateRunner` starts Restate container (testcontainers/Docker)
2. Service starts in-process with stub factory
3. POST to `ingress/ClaimProcessor/it-claim-001/processClaim`
4. Assert HTTP 200 + `"decision":"APPROVED"` + `"fraudFlag":false`

**File location:** `src/test/java/ai/durableagents/integration/HappyPathIT.java`

---

## Order of execution
1. Write `docker-compose.yml` and `Dockerfile`
2. Update `pom.xml`
3. Update `AgentFactory.java`
4. Update `ClaimProcessor.java`
5. Write `HappyPathIT.java`
6. Run `mvn test` to confirm unit tests still pass
7. Run `task test:it` to run the integration test (starts/stops the container runtime automatically)
