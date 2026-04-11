package com.dbtespresso.sql;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.*;

/**
 * SQL analysis powered by JSQLParser. Used AFTER Jinja rendering to:
 * <ul>
 *   <li>Validate that rendered SQL is syntactically correct</li>
 *   <li>Extract physical table references from fully rendered SQL</li>
 *   <li>Detect column lineage (which columns come from which tables)</li>
 * </ul>
 *
 * <h2>Why both RefExtractor and SqlAnalyzer?</h2>
 * RefExtractor works on raw Jinja templates (pre-render) to build the DAG.
 * SqlAnalyzer works on rendered SQL (post-render) to validate and do deeper analysis.
 * They serve different phases of the pipeline.
 *
 * <h2>JSQLParser dialect support</h2>
 * JSQLParser handles BigQuery, Snowflake, Redshift, Databricks, Postgres, MySQL,
 * Oracle, SQL Server, DuckDB and more from a single unified grammar.
 */
public final class SqlAnalyzer {

    private SqlAnalyzer() {}

    /**
     * Parse and validate a SQL string. Returns the parsed AST.
     *
     * @throws SqlValidationException if the SQL is syntactically invalid
     */
    public static Statement parse(String sql) {
        try {
            return CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            throw new SqlValidationException("Invalid SQL: " + e.getMessage(), e);
        }
    }

    /**
     * Validate SQL without returning the AST.
     * @return true if valid
     */
    public static boolean isValid(String sql) {
        try {
            CCJSqlParserUtil.parse(sql);
            return true;
        } catch (JSQLParserException e) {
            return false;
        }
    }

    /**
     * Extract all table names referenced in a SQL statement.
     * Works on rendered SQL (after Jinja refs have been replaced with actual table names).
     *
     * Example: "SELECT a.x FROM schema1.orders a JOIN schema2.customers b ON ..."
     * Returns: ["schema1.orders", "schema2.customers"]
     */
    public static List<String> extractTableNames(String sql) {
        Statement stmt = parse(sql);
        var finder = new TablesNamesFinder();
        return List.copyOf(finder.getTables(stmt));
    }

    /**
     * Check if a SQL statement is a SELECT (read-only query).
     * Useful for validating that model SQL doesn't contain DML.
     */
    public static boolean isSelectStatement(String sql) {
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            return stmt instanceof Select;
        } catch (JSQLParserException e) {
            return false;
        }
    }

    /**
     * Attempt to parse with a timeout (useful for very complex SQL).
     *
     * @param sql the SQL string
     * @param timeoutMs max parse time in milliseconds
     * @return the parsed statement
     * @throws SqlValidationException if invalid or timeout exceeded
     */
    public static Statement parseWithTimeout(String sql, long timeoutMs) {
        try {
            return CCJSqlParserUtil.parse(sql, parser -> {
                parser.withTimeOut(timeoutMs);
            });
        } catch (JSQLParserException e) {
            throw new SqlValidationException("SQL parse failed: " + e.getMessage(), e);
        }
    }
}
