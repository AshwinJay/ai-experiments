package com.dbtespresso.testing;

import java.util.*;

/**
 * The adapter contract that warehouse adapters must implement.
 * This is the dbt_adapters compatibility layer.
 *
 * <h2>How dbt_adapters works</h2>
 * dbt_adapters provides a test suite that verifies custom adapters implement
 * the required interface correctly. It tests connection handling, SQL execution,
 * schema introspection, materialization, and relation management.
 *
 * <h2>What dbt-espresso must provide</h2>
 * A clean adapter interface that can be:
 * <ol>
 *   <li>Implemented per warehouse (Snowflake, BigQuery, Databricks, Redshift)</li>
 *   <li>Tested with a standard compliance test suite</li>
 *   <li>Mocked for unit testing (e.g., DryRunAdapter)</li>
 * </ol>
 *
 * <h2>Mapping to dbt Core's adapter interface</h2>
 * dbt Core adapters implement: BaseAdapter, SQLAdapter, or one of the
 * warehouse-specific base classes. The key methods are below.
 */
public interface AdapterContract {

    // ==================== Connection ====================

    /** Open a connection to the warehouse. */
    void open();

    /** Close the connection. */
    void close();

    /** Test that the connection is alive. */
    boolean isConnected();

    /** Get the adapter type name (snowflake, bigquery, etc.) */
    String adapterType();

    // ==================== SQL Execution ====================

    /**
     * Execute a SQL statement and return results.
     * For DDL/DML, rows may be empty.
     */
    QueryResult execute(String sql);

    /**
     * Execute a SQL statement with a timeout.
     */
    QueryResult execute(String sql, long timeoutMs);

    // ==================== Schema Introspection ====================

    /** Get all column names and types for a table/view. */
    List<ColumnInfo> getColumns(String schema, String table);

    /** Check if a relation (table/view) exists. */
    boolean relationExists(String schema, String name);

    /** List all relations in a schema. */
    List<RelationInfo> listRelations(String schema);

    // ==================== Materialization ====================

    /** Create a table from a SELECT query (CREATE TABLE AS). */
    void createTableAs(String schema, String name, String selectSql, boolean replace);

    /** Create a view from a SELECT query (CREATE VIEW AS). */
    void createViewAs(String schema, String name, String selectSql, boolean replace);

    /** Drop a relation. */
    void dropRelation(String schema, String name, RelationType type);

    /** Rename a relation. */
    void renameRelation(String schema, String oldName, String newName);

    // ==================== Types ====================

    record ColumnInfo(String name, String dataType, boolean isNullable) {}

    record RelationInfo(String schema, String name, RelationType type) {}

    enum RelationType { TABLE, VIEW, MATERIALIZED_VIEW, EXTERNAL }

    record QueryResult(List<Map<String, Object>> rows, long rowsAffected) {
        public static QueryResult empty(long affected) {
            return new QueryResult(List.of(), affected);
        }
    }
}
