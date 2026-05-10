# dbt-espresso ☕

A Java 21 reimplementation of the core dbt engine pipeline: **scan → extract refs → build DAG → execute in parallel**.

Inspired by [dbt-fusion](https://github.com/dbt-labs/dbt-fusion) (Rust), reimagined for the Java ecosystem.

## Requirements

- Java 21+
- Maven 3.9+
- [Task](https://taskfile.dev) (`brew install go-task`)

## Quick start

```bash
task build        # compile all 8 modules
task test         # run all ~157 unit tests
task verify       # clean + compile + test
task run          # dry-run CLI against the bundled sample project
task run-custom -- path/to/your/dbt/project
```

Run `task` (no arguments) to list all targets.

<details>
<summary>Bare Maven equivalents</summary>

```bash
mvn compile
mvn test
mvn clean verify
mvn -pl dbt-cli exec:java -Dexec.mainClass="com.dbtespresso.cli.Main" \
    -Dexec.args="dbt-parser/src/test/resources/sample_project"
```

</details>

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md).

## Plan

See [PLAN.md](PLAN.md).
