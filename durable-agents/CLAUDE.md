# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Where to read

- **Architecture, running instructions, API gotchas:** `README.md`
- **Design rationale, patterns considered, roadmap:** `PLAN.md`
- **Orchestration logic:** `src/main/java/ai/durableagents/planner/`
- **Agent definitions and LLM provider wiring:** `src/main/java/ai/durableagents/agents/`
- **Restate handler (entry point for claims):** `src/main/java/ai/durableagents/restate/ClaimProcessor.java`
- **Unit tests:** `src/test/java/ai/durableagents/planner/`

## Consistency rules

- Keep `README.md`, `PLAN.md`, and `CLAUDE.md` consistent with the code. If you change behavior, update the relevant docs in the same pass.
- After any code change, run `mvn test` and fix any failures before considering the task done.
