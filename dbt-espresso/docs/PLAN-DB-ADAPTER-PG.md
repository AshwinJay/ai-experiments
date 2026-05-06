# Plan: Postgres Warehouse Adapter

## New module: `dbt-adapters`

```
dbt-adapters/
├── pom.xml
└── src/
    ├── main/java/com/dbtespresso/adapter/
    │   └── PostgresAdapter.java          ← implements AdapterContract via JDBC
    └── test/java/com/dbtespresso/adapter/
        └── PostgresAdapterComplianceTest.java  ← @Tag("integration"), runs AdapterComplianceSuite
```

## New dependencies

| Artifact | Scope | Purpose |
|---|---|---|
| `org.postgresql:postgresql:42.7.4` | compile | JDBC driver |
| `org.testcontainers:postgresql:1.20.4` | test | spins up Postgres container |
| `org.testcontainers:junit-jupiter:1.20.4` | test | `@Testcontainers` / `@Container` lifecycle |

Versions go into parent `pom.xml` `dependencyManagement`.

## `PostgresAdapter` design

- Constructor: `PostgresAdapter(String jdbcUrl, String user, String password)` — Testcontainers hands this directly
- Holds a single `Connection`; no pool (keeps scope tight)
- `execute()` → `Statement` + `ResultSet` → `List<LinkedHashMap<String, Object>>` per row
- `createTableAs(replace=true)` → `DROP TABLE IF EXISTS … CASCADE` then `CREATE TABLE … AS SELECT …`
- `createViewAs(replace=true)` → `CREATE OR REPLACE VIEW … AS SELECT …`
- `getColumns()` → `information_schema.columns` WHERE `table_schema` + `table_name`
- `listRelations()` → `information_schema.tables` (TABLE + VIEW) in given schema
- `renameRelation()` → `ALTER TABLE … RENAME TO …`
- `execute(sql, timeoutMs)` → `statement.setQueryTimeout(seconds)` (convert from ms)

## Integration test approach

```java
@Tag("integration")
@Testcontainers
class PostgresAdapterComplianceTest {
    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void compliance() {
        var adapter = new PostgresAdapter(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var results = AdapterComplianceSuite.runAll(adapter, "public");
        assertThat(results.failures()).isEmpty();
    }
}
```

`@Tag("integration")` is excluded from the default Surefire run. A dedicated Taskfile task runs only that tag.

## Files to create/modify

| File | Action |
|---|---|
| `dbt-adapters/pom.xml` | Create |
| `dbt-adapters/src/main/java/.../PostgresAdapter.java` | Create |
| `dbt-adapters/src/test/java/.../PostgresAdapterComplianceTest.java` | Create |
| `pom.xml` | Add `<module>dbt-adapters</module>` + Testcontainers in `dependencyManagement` |
| `Taskfile.yml` | Add `test-postgres` task (runs `mvn -pl dbt-adapters test -Dgroups=integration`) |

## Colima note

Testcontainers auto-detects Docker via `/var/run/docker.sock`. With Colima, that socket is live as long as `colima start` has been run before executing tests. The `test-postgres` task requires no special env vars if Colima is started with default settings.

## What's next: MySQL

Once Postgres passes all 10 compliance tests, add `MySqlAdapter` in the same module following the same pattern. Key differences: `CREATE TABLE … AS SELECT …` syntax is identical, but `CREATE OR REPLACE VIEW` is not supported in MySQL — use `DROP VIEW IF EXISTS` + `CREATE VIEW` instead. Use `mysql:8-debian` image.
