# Architecture

## Module Overview

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
    └──→ dbt-qa  (unit tests, generic tests, meta-testing, adapter compliance)
              │
              └──→ dbt-cli  (entry point)

dbt-qa ──→ dbt-adapters  (PostgresAdapter + AdapterModelRunner — JDBC, Testcontainers compliance + end-to-end tests)
dbt-engine ──→ dbt-adapters
```

## Module Details

```
dbt-espresso/
├── pom.xml                          # Parent POM (Java 21, JUnit 5, AssertJ, JSQLParser)
│
├── dbt-jinja/                       # Depends on: Jinjava 2.7.2
│   ├── Dependency.java              # Sealed interface: ModelRef, SourceRef, MetricRef
│   ├── RefExtractor.java            # Static Jinja analysis — extracts ref()/source() via regex
│   ├── RenderContext.java           # Record: ref/source resolution maps, vars, isIncremental
│   ├── JinjaRenderer.java           # Renders raw Jinja+SQL → plain SQL (pre-process + Jinjava)
│   ├── RefExtractorTest.java        # 18 tests: quotes, dedup, comments, filters, incremental
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
│   └── ModelGraphTest.java          # 22 tests: jaffle shop, diamond, cycles, selection, wide
│
├── dbt-engine/                      # Depends on: dbt-jinja, dbt-parser, dbt-graph
│   ├── GraphExecutor.java           # Virtual-thread executor, level-by-level, semaphore control
│   ├── ModelRunner.java             # @FunctionalInterface — pluggable per-model execution
│   ├── ModelResult.java             # Record: SUCCESS/ERROR/SKIPPED + timing + rows
│   ├── ExecutionSummary.java        # Aggregated run stats
│   ├── ExecutionListener.java       # Observer for progress/logging
│   └── GraphExecutorTest.java       # 13 tests: ordering, parallelism, failure propagation,
│                                    #   concurrency limits, selected execution, listener
│
├── dbt-qa/                          # Depends on: all modules, jackson-dataformat-yaml
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
├── dbt-cli/                         # Depends on: all modules
│   └── Main.java                    # Dry-run CLI: scan → DAG → execute
│
└── dbt-adapters/                    # Depends on: dbt-qa, dbt-engine, dbt-jinja, postgresql JDBC, Testcontainers
    ├── PostgresAdapter.java         # AdapterContract impl: JDBC connection, DDL, schema introspection
    ├── AdapterModelRunner.java      # ModelRunner impl: renders Jinja, materializes via AdapterContract (one connection per model)
    ├── PostgresAdapterComplianceTest.java  # @Tag("integration") @TestFactory — 10 compliance checks as individual JUnit tests
    └── PostgresEndToEndTest.java    # @Tag("integration") — full pipeline scan→DAG→execute→verify against postgres:16-alpine
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
