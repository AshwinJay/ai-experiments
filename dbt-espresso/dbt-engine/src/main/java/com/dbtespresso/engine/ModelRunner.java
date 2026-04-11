package com.dbtespresso.engine;

import com.dbtespresso.parser.ParsedModel;

/**
 * Strategy interface for executing a single dbt model.
 * Implementations handle Jinja rendering, SQL submission, and materialization
 * for a specific warehouse adapter.
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>SnowflakeModelRunner — renders Jinja, wraps in CREATE TABLE AS / CREATE VIEW AS</li>
 *   <li>BigQueryModelRunner — same, but with BQ-specific DDL</li>
 *   <li>DryRunModelRunner — logs the SQL without executing (for testing/CI)</li>
 * </ul>
 */
@FunctionalInterface
public interface ModelRunner {

    /**
     * Execute a single model. Called from a virtual thread — implementations
     * can safely block on I/O (JDBC/ADBC calls).
     *
     * @param model the parsed model to execute
     * @return the result of execution
     */
    ModelResult run(ParsedModel model);
}
