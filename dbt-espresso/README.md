# dbt-espresso ☕

A Java 21 reimplementation of the core dbt engine pipeline: **scan → extract refs → build DAG → execute in parallel**.

Inspired by [dbt-fusion](https://github.com/dbt-labs/dbt-fusion) (Rust), reimagined for the Java ecosystem.

## Requirements

- Java 21+
- Maven 3.9+

## Build & Test

```bash
mvn clean compile   # compile all 7 modules
mvn test            # run all ~80 test cases
```

## Run (dry-run against sample project)

```bash
mvn -pl dbt-cli exec:java -Dexec.mainClass="Main" -Dexec.args="path/to/your/dbt/project"
```

## Architecture

### Module Overview

```
dbt-espresso/
├── pom.xml                          # Parent POM (Java 21, JUnit 5, AssertJ, JSQLParser)
│
├── dbt-jinja/                       # ZERO external deps
│   ├── Dependency.java              # Sealed interface: ModelRef, SourceRef, MetricRef
│   ├── RefExtractor.java            # Static Jinja analysis — extracts ref()/source() via regex
│   └── RefExtractorTest.java        # 15 tests: quotes, dedup, comments, filters, incremental
│
├── dbt-parser/                      # Depends on: dbt-jinja
│   ├── ParsedModel.java             # Record: name, resourceType, filePath, rawSql, deps, config
│   ├── ConfigExtractor.java         # Pulls config(materialized='table', ...) from Jinja
│   ├── DbtProjectScanner.java       # Walks models/ dir, produces ParsedModel list
│   ├── DbtProjectScannerTest.java   # Tests against sample_project/ fixture (5 .sql files)
│   └── test/resources/sample_project/
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
├── dbt-testing/                     # Depends on: all modules
│   ├── UnitTestDefinition.java      # Parsed unit_tests: YAML block (given/expect)
│   ├── UnitTestCompiler.java        # Rewrites model SQL: ref() → mock CTEs, EXCEPT diff
│   ├── GenericTest.java             # Record for not_null, unique, dbt_expectations.* tests
│   ├── GenericTestCompiler.java     # Compiles built-in + 8 dbt_expectations tests to SQL
│   ├── MetaTestingValidator.java    # dbt_meta_testing: regex test coverage + doc coverage
│   ├── AdapterContract.java         # Interface that warehouse adapters must implement
│   ├── AdapterComplianceSuite.java  # 10-test harness for adapter verification
│   ├── UnsupportedTestException.java
│   └── TestingModuleTest.java       # 20 tests across all testing components
│
└── dbt-cli/                         # Depends on: all modules
    └── Main.java                    # Dry-run CLI: scan → DAG → execute
```

### Module Dependency Graph

```
dbt-jinja  (no deps — pure Java regex/records)
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

## The Pipeline

```
.sql files → RefExtractor → ParsedModel → ModelGraph → GraphExecutor → Results
               (Jinja)       (scanner)      (DAG)     (virtual threads)
```

1. **RefExtractor** statically analyzes `{{ ref('x') }}` / `{{ source('a','b') }}` calls
   in raw Jinja+SQL without rendering — because the DAG must exist before anything executes.

2. **DbtProjectScanner** walks `models/`, `tests/`, `snapshots/` and produces `ParsedModel`
   records with name, file path, dependencies, and `config()` settings.

3. **ModelGraph** wires edges from `ref()` targets, detects cycles (DFS), computes execution
   levels (Kahn's algorithm). Supports `select(names, upstream, downstream)` for `--select`.

4. **GraphExecutor** runs level-by-level on Java 21 virtual threads. If a model fails, all
   downstream dependents are automatically skipped. Concurrency is controllable via semaphore.

5. **SqlAnalyzer** (JSQLParser) validates rendered SQL post-Jinja and extracts physical table
   names for lineage tracking. JSQLParser supports Snowflake, BigQuery, Redshift, Databricks,
   Postgres, MySQL, and more from a single grammar.

## What Works

- **Compiles:** All modules compile on Java 21. Only `dbt-sql` needs Maven for JSQLParser.
- **Static ref extraction:** `RefExtractor` finds `ref()`, `source()`, `metric()` in Jinja SQL without rendering.
- **Project scanning:** `DbtProjectScanner` walks `models/` and produces `ParsedModel` records with deps + config.
- **DAG construction:** `ModelGraph` builds the graph, detects cycles, computes execution levels, supports `--select +model` and `model+` ancestor/descendant selection.
- **Parallel execution:** `GraphExecutor` runs models level-by-level on virtual threads with optional concurrency limits. Failed models skip all downstream dependents.
- **SQL validation:** `SqlAnalyzer` wraps JSQLParser for post-Jinja-render SQL validation and table name extraction.
- **Unit test compilation:** `UnitTestCompiler` rewrites model SQL by replacing refs with mock CTEs, generates EXCEPT-based diff queries.
- **Generic test compilation:** `GenericTestCompiler` compiles `not_null`, `unique`, `accepted_values`, `relationships` + 8 `dbt_expectations` tests directly to SQL.
- **Meta-testing:** `MetaTestingValidator` checks test coverage against regex patterns and doc coverage against actual columns.
- **Adapter compliance:** `AdapterComplianceSuite` runs 10 tests against any `AdapterContract` implementation.

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **JSQLParser** over Calcite | Lighter weight, faster (7ms vs 85ms), supports all dbt warehouse dialects natively |
| **No external graph library** | dbt graphs are small (hundreds of nodes); adjacency list + Kahn's algorithm is simpler and faster than JGraphT |
| **Virtual threads** over async | Java 21 virtual threads are the idiomatic choice for I/O-bound warehouse calls; no reactive complexity |
| **Sealed interfaces** | `Dependency` is sealed → exhaustive pattern matching in `switch` |
| **Records everywhere** | Immutable data carriers for `ParsedModel`, `ModelResult`, `ExecutionSummary` |
| **Static ref extraction** | Regex-based extraction runs before Jinja rendering to break the chicken-and-egg DAG problem; `depends_on` YAML override handles edge cases |
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

1. **Jinja rendering engine** — Add [Jinjava](https://github.com/HubSpot/jinjava) to `dbt-jinja/pom.xml`; implement custom `ref()`, `source()`, `config()`, `var()`, `is_incremental()` functions; renderer takes a `ParsedModel` + resolved graph and outputs plain SQL.

2. **YAML schema parsing** — Parse `schema.yml` / `dbt_project.yml` / `profiles.yml` with Jackson (`jackson-dataformat-yaml`); map to `UnitTestDefinition`, `GenericTest`, and config records.

3. **Warehouse adapters** — Implement `AdapterContract` for Snowflake or Postgres (JDBC or ADBC); run `AdapterComplianceSuite` to verify.

4. **Picocli CLI** — Replace `Main.java` with proper subcommands (`parse`, `build`, `run`, `test`, `ls`, `compile`) and flags (`--select`, `--exclude`, `--threads`, `--target`, `--profiles-dir`).

5. **Incremental model support** — `is_incremental()`, merge statements.

6. **LSP server** — LSP4J for VS Code / Cursor integration.

7. **GraalVM native image** — Single-binary distribution (no JVM needed at runtime).
