# dbt-espresso Plan

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
- **Postgres adapter:** `PostgresAdapter` implements `AdapterContract` via JDBC; passes all 10 compliance tests against a live `postgres:16-alpine` container (Testcontainers, `@Tag("integration")`).
- **End-to-end pipeline execution:** `AdapterModelRunner` wires `GraphExecutor` → `JinjaRenderer` → `AdapterContract` (one fresh connection per model via factory). `PostgresEndToEndTest` runs a 3-model project (with `{{ ref() }}` joins) against a live Postgres container, verifying views are created in topological order with correct data.
- **YAML schema parsing:** `DbtProjectYamlParser` parses `dbt_project.yml` → `ProjectConfig` and `profiles.yml` → `ProfileConfig`. `SchemaFileParser` parses `schema.yml` → `SchemaFile` with flattened `GenericTest` and `UnitTestDefinition` lists; handles string/map/dotted-package test syntax, CSV/DICT/SQL row formats, model-level and column-level tests, and source tables.

## What Tests Exist (including Ecosystem Compatibility)

| Tool | Status | Class |
|------|--------|-------|
| **dbt unit tests** (native) | ✅ Compiler done | `UnitTestCompiler` |
| **dbt_expectations** (62 tests) | ✅ 8 natively compiled, rest needs Jinja | `GenericTestCompiler` |
| **dbt_meta_testing** | ✅ Test + doc coverage validation done | `MetaTestingValidator` |
| **dbt_adapters** | ✅ Contract + 10-test compliance suite done | `AdapterComplianceSuite` |

## What's Next

Priority order for making this a usable dbt runner:

1. **Picocli CLI** — Replace `Main.java` with proper subcommands (`parse`, `build`, `run`, `test`, `ls`, `compile`) and flags (`--select`, `--exclude`, `--threads`, `--target`, `--profiles-dir`).

3. **Incremental model support** — Wire `is_incremental()` to a real run-state check; implement merge/insert-overwrite strategies.

4. **LSP server** — LSP4J for VS Code / Cursor integration.

5. **GraalVM native image** — Single-binary distribution (no JVM needed at runtime).

6. **More database adapters** — `AdapterComplianceSuite` and `PostgresEndToEndTest` cover Postgres. Extend to MySQL and SQLite by implementing `AdapterContract` for each and spinning up Testcontainers.
