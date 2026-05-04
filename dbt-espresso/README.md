# dbt-espresso ☕

A Java 21 reimplementation of the core dbt engine pipeline: **scan → extract refs → build DAG → execute in parallel**.

Inspired by [dbt-fusion](https://github.com/dbt-labs/dbt-fusion) (Rust), reimagined for the Java ecosystem.

## Requirements

- Java 21+
- Maven 3.9+
- [Task](https://taskfile.dev) (`brew install go-task`)

## Quick start

```bash
task build        # compile all 7 modules
task test         # run all ~133 test cases
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

### Module Dependency Graph

```
dbt-jinja  (Jinjava 2.7.2 — static analysis + Jinja rendering)
    │
    ├──→ dbt-parser  (walks filesystem, produces ParsedModel)
    │        │
    │        ├──→ dbt-graph  (DAG construction, topo sort, selection)
    │        │        │
    │        │        └──→ dbt-engine  (virtual-thread executor)
    │        │
    ├──→ dbt-sql  (JSQLParser — SQL validation & table extraction)
    │
    └──→ dbt-testing  (unit tests, generic tests, meta-testing, adapter compliance)
              │
              └──→ dbt-cli  (entry point)
```

### Module Overview

```
dbt-espresso/
├── pom.xml                          # Parent POM (Java 21, JUnit 5, AssertJ, JSQLParser)
│
├── dbt-jinja/                       # Depends on: Jinjava 2.7.2
│   ├── Dependency.java              # Sealed interface: ModelRef, SourceRef, MetricRef
│   ├── RefExtractor.java            # Static Jinja analysis — extracts ref()/source() via regex
│   ├── RenderContext.java           # Record: ref/source resolution maps, vars, isIncremental
│   ├── JinjaRenderer.java           # Renders raw Jinja+SQL → plain SQL (pre-process + Jinjava)
│   ├── RefExtractorTest.java        # 15 tests: quotes, dedup, comments, filters, incremental
│   └── JinjaRendererTest.java       # 15 tests: ref/source/config/var/is_incremental/full models
│
├── dbt-parser/                      # Depends on: dbt-jinja, jackson-dataformat-yaml
│   ├── ParsedModel.java             # Record: name, resourceType, filePath, rawSql, deps, config
│   ├── ConfigExtractor.java         # Pulls config(materialized='table', ...) from Jinja
│   ├── DbtProjectScanner.java       # Walks models/ dir, produces ParsedModel list
│   ├── ProjectConfig.java           # Record: parsed dbt_project.yml (name, paths, vars, models config)
│   ├── ProfileConfig.java           # Record: parsed profiles.yml (profile name, target, outputs)
│   ├── DbtProjectYamlParser.java    # Parses dbt_project.yml + profiles.yml via Jackson YAML
│   ├── DbtProjectScannerTest.java   # Tests against sample_project/ fixture (5 .sql files)
│   ├── DbtProjectYamlParserTest.java# 16 tests: project name/paths/vars, profile outputs, threading
│   └── test/resources/sample_project/
│       ├── dbt_project.yml          # jaffle_shop project config with model path/var/materialization
│       ├── profiles.yml             # dev + prod Postgres outputs
│       └── models/
│           ├── staging/             # stg_orders, stg_customers, stg_payments (source deps)
│           └── marts/               # orders (3 staging refs), customer_orders (refs orders)
│
├── dbt-sql/                         # Depends on: dbt-jinja, JSQLParser 5.3
│   ├── SqlAnalyzer.java             # Validates rendered SQL, extracts table names
│   ├── SqlValidationException.java
│   └── SqlAnalyzerTest.java         # CTEs, subqueries, schema-qualified, Snowflake 3-part
│
├── dbt-graph/                       # Depends on: dbt-jinja, dbt-parser
│   ├── ModelGraph.java              # DAG: Kahn's topo sort, DFS cycle detection, leveling,
│   │                                #   ancestors/descendants, select(+model / model+)
│   ├── CycleDetectedException.java
│   ├── DanglingRefException.java
│   └── ModelGraphTest.java          # 20 tests: jaffle shop, diamond, cycles, selection, wide
│
├── dbt-engine/                      # Depends on: dbt-jinja, dbt-parser, dbt-graph
│   ├── GraphExecutor.java           # Virtual-thread executor, level-by-level, semaphore control
│   ├── ModelRunner.java             # @FunctionalInterface — pluggable per-model execution
│   ├── ModelResult.java             # Record: SUCCESS/ERROR/SKIPPED + timing + rows
│   ├── ExecutionSummary.java        # Aggregated run stats
│   ├── ExecutionListener.java       # Observer for progress/logging
│   └── GraphExecutorTest.java       # 12 tests: ordering, parallelism, failure propagation,
│                                    #   concurrency limits, selected execution, listener
│
├── dbt-testing/                     # Depends on: all modules, jackson-dataformat-yaml
│   ├── UnitTestDefinition.java      # Parsed unit_tests: YAML block (given/expect)
│   ├── UnitTestCompiler.java        # Rewrites model SQL: ref() → mock CTEs, EXCEPT diff
│   ├── GenericTest.java             # Record for not_null, unique, dbt_expectations.* tests
│   ├── GenericTestCompiler.java     # Compiles built-in + 8 dbt_expectations tests to SQL
│   ├── SchemaFile.java              # Record: parsed schema.yml (models, sources, unit_tests)
│   ├── SchemaFileParser.java        # Jackson YAML → SchemaFile, GenericTest, UnitTestDefinition
│   ├── MetaTestingValidator.java    # dbt_meta_testing: regex test coverage + doc coverage
│   ├── AdapterContract.java         # Interface that warehouse adapters must implement
│   ├── AdapterComplianceSuite.java  # 10-test harness for adapter verification
│   ├── UnsupportedTestException.java
│   └── TestingModuleTest.java       # 49 tests across all testing components
│
└── dbt-cli/                         # Depends on: all modules
    └── Main.java                    # Dry-run CLI: scan → DAG → execute
```

## The Pipeline

```
.sql files → RefExtractor → ParsedModel → ModelGraph → JinjaRenderer → GraphExecutor → Results
               (static)       (scanner)      (DAG)       (rendering)    (virtual threads)
```

1. **RefExtractor** statically analyzes `{{ ref('x') }}` / `{{ source('a','b') }}` calls
   in raw Jinja+SQL without rendering — because the DAG must exist before anything executes.

2. **DbtProjectScanner** walks `models/`, `tests/`, `snapshots/` and produces `ParsedModel`
   records with name, file path, dependencies, and `config()` settings.

3. **ModelGraph** wires edges from `ref()` targets, detects cycles (DFS), computes execution
   levels (Kahn's algorithm). Supports `select(names, upstream, downstream)` for `--select`.

4. **JinjaRenderer** resolves dbt function calls (`ref`, `source`, `config`, `var`,
   `is_incremental`) via a regex pre-processing pass, then delegates remaining Jinja2 constructs
   (`{% if %}`, `{% for %}`, `{% set %}`, filters) to Jinjava. Takes a `RenderContext` with
   per-run resolution maps and variable bindings.

5. **GraphExecutor** runs level-by-level on Java 21 virtual threads. If a model fails, all
   downstream dependents are automatically skipped. Concurrency is controllable via semaphore.

6. **SqlAnalyzer** (JSQLParser) validates rendered SQL post-Jinja and extracts physical table
   names for lineage tracking. JSQLParser supports Snowflake, BigQuery, Redshift, Databricks,
   Postgres, MySQL, and more from a single grammar.

## What Works

- **Compiles:** All modules compile on Java 21. `dbt-jinja` uses Jinjava; `dbt-sql` uses JSQLParser.
- **Static ref extraction:** `RefExtractor` finds `ref()`, `source()`, `metric()` in Jinja SQL without rendering.
- **Jinja rendering:** `JinjaRenderer` resolves dbt functions (`ref`, `source`, `config`, `var`, `is_incremental`) and renders full Jinja2 templates (conditionals, loops, set) to plain SQL.
- **Project scanning:** `DbtProjectScanner` walks `models/` and produces `ParsedModel` records with deps + config.
- **DAG construction:** `ModelGraph` builds the graph, detects cycles, computes execution levels, supports `--select +model` and `model+` ancestor/descendant selection.
- **Parallel execution:** `GraphExecutor` runs models level-by-level on virtual threads with optional concurrency limits. Failed models skip all downstream dependents.
- **SQL validation:** `SqlAnalyzer` wraps JSQLParser for post-Jinja-render SQL validation and table name extraction.
- **Unit test compilation:** `UnitTestCompiler` rewrites model SQL by replacing refs with mock CTEs, generates EXCEPT-based diff queries.
- **Generic test compilation:** `GenericTestCompiler` compiles `not_null`, `unique`, `accepted_values`, `relationships` + 8 `dbt_expectations` tests directly to SQL.
- **Meta-testing:** `MetaTestingValidator` checks test coverage against regex patterns and doc coverage against actual columns.
- **Adapter compliance:** `AdapterComplianceSuite` runs 10 tests against any `AdapterContract` implementation.
- **YAML schema parsing:** `DbtProjectYamlParser` parses `dbt_project.yml` → `ProjectConfig` and `profiles.yml` → `ProfileConfig`. `SchemaFileParser` parses `schema.yml` → `SchemaFile` with flattened `GenericTest` and `UnitTestDefinition` lists; handles string/map/dotted-package test syntax, CSV/DICT/SQL row formats, model-level and column-level tests, and source tables.

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **JSQLParser** over Calcite | Lighter weight, faster (7ms vs 85ms), supports all dbt warehouse dialects natively |
| **No external graph library** | dbt graphs are small (hundreds of nodes); adjacency list + Kahn's algorithm is simpler and faster than JGraphT |
| **Virtual threads** over async | Java 21 virtual threads are the idiomatic choice for I/O-bound warehouse calls; no reactive complexity |
| **Sealed interfaces** | `Dependency` is sealed → exhaustive pattern matching in `switch` |
| **Records everywhere** | Immutable data carriers for `ParsedModel`, `ModelResult`, `ExecutionSummary` |
| **Static ref extraction** | Regex-based extraction runs before Jinja rendering to break the chicken-and-egg DAG problem; `depends_on` YAML override handles edge cases |
| **Two-phase Jinja rendering** | dbt functions (ref, source, config, var, is_incremental) resolved via regex pre-processing; standard Jinja2 constructs delegated to Jinjava — avoids needing a custom EL function registry |
| **Generic test native compilation** | 8 most common `dbt_expectations` tests compiled directly to SQL for speed; others fall through to Jinja macro resolution |

## Testing Ecosystem Compatibility

| Tool | Status | Class |
|------|--------|-------|
| **dbt unit tests** (native) | ✅ Compiler done | `UnitTestCompiler` |
| **dbt_expectations** (62 tests) | ✅ 8 natively compiled, rest needs Jinja | `GenericTestCompiler` |
| **dbt_meta_testing** | ✅ Test + doc coverage validation done | `MetaTestingValidator` |
| **dbt_adapters** | ✅ Contract + 10-test compliance suite done | `AdapterComplianceSuite` |

## What's Next

Priority order for making this a usable dbt runner:

1. **Warehouse adapters** — Implement `AdapterContract` for Snowflake or Postgres (JDBC or ADBC); run `AdapterComplianceSuite` to verify.

2. **Picocli CLI** — Replace `Main.java` with proper subcommands (`parse`, `build`, `run`, `test`, `ls`, `compile`) and flags (`--select`, `--exclude`, `--threads`, `--target`, `--profiles-dir`).

3. **Incremental model support** — Wire `is_incremental()` to a real run-state check; implement merge/insert-overwrite strategies.

4. **LSP server** — LSP4J for VS Code / Cursor integration.

5. **GraalVM native image** — Single-binary distribution (no JVM needed at runtime).

6. **Integration tests against real databases** — Implement `AdapterContract` for Postgres, MySQL, and SQLite; spin up each engine in a Docker container (Testcontainers); run `AdapterComplianceSuite` against all three to verify SQL dialect handling, DDL execution, and query results end-to-end.
