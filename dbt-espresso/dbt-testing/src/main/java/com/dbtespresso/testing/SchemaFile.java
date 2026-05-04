package com.dbtespresso.testing;

import java.util.*;

/**
 * Parsed representation of one {@code schema.yml} file.
 *
 * <p>A schema file declares model metadata (columns, descriptions, generic tests)
 * and unit test definitions. After parsing, call {@link #genericTests()} to get
 * all {@link GenericTest} instances ready for compilation and execution.
 *
 * @param models    model blocks, each with columns and data tests
 * @param sources   source blocks, each with tables and column tests
 * @param unitTests unit test definitions (compile via {@link UnitTestCompiler})
 */
public record SchemaFile(
        List<ModelSchema> models,
        List<SourceSchema> sources,
        List<UnitTestDefinition> unitTests
) {
    public SchemaFile {
        models    = models    != null ? List.copyOf(models)    : List.of();
        sources   = sources   != null ? List.copyOf(sources)   : List.of();
        unitTests = unitTests != null ? List.copyOf(unitTests) : List.of();
    }

    /** Flatten all generic tests from all models and sources into a single list. */
    public List<GenericTest> genericTests() {
        List<GenericTest> all = new ArrayList<>();
        for (ModelSchema m : models) all.addAll(m.allTests());
        for (SourceSchema s : sources) all.addAll(s.allTests());
        return List.copyOf(all);
    }

    // -----------------------------------------------------------------------
    // Nested schema types
    // -----------------------------------------------------------------------

    /**
     * One {@code models:} block entry in schema.yml.
     *
     * @param name        model name (matches the .sql filename without extension)
     * @param description optional human-readable description
     * @param config      inline config overrides (materialized, schema, etc.)
     * @param columns     per-column metadata with associated tests
     * @param modelTests  model-level generic tests (no column name)
     */
    public record ModelSchema(
            String name,
            String description,
            Map<String, Object> config,
            List<ColumnSchema> columns,
            List<GenericTest> modelTests
    ) {
        public ModelSchema {
            Objects.requireNonNull(name);
            config     = config     != null ? Map.copyOf(config)     : Map.of();
            columns    = columns    != null ? List.copyOf(columns)   : List.of();
            modelTests = modelTests != null ? List.copyOf(modelTests) : List.of();
        }

        /** All generic tests for this model (model-level + all column-level). */
        public List<GenericTest> allTests() {
            List<GenericTest> all = new ArrayList<>(modelTests);
            for (ColumnSchema col : columns) all.addAll(col.tests());
            return List.copyOf(all);
        }
    }

    /**
     * One {@code sources:} block entry.
     *
     * @param name        source name (first arg to {@code source('name', 'table')})
     * @param description optional description
     * @param tables      table-level entries with their column tests
     */
    public record SourceSchema(
            String name,
            String description,
            List<SourceTableSchema> tables
    ) {
        public SourceSchema {
            Objects.requireNonNull(name);
            tables = tables != null ? List.copyOf(tables) : List.of();
        }

        public List<GenericTest> allTests() {
            List<GenericTest> all = new ArrayList<>();
            for (SourceTableSchema t : tables) all.addAll(t.allTests());
            return List.copyOf(all);
        }
    }

    /** One table entry inside a source block. */
    public record SourceTableSchema(
            String sourceName,
            String tableName,
            String description,
            List<ColumnSchema> columns
    ) {
        public SourceTableSchema {
            Objects.requireNonNull(sourceName);
            Objects.requireNonNull(tableName);
            columns = columns != null ? List.copyOf(columns) : List.of();
        }

        public List<GenericTest> allTests() {
            List<GenericTest> all = new ArrayList<>();
            for (ColumnSchema col : columns) all.addAll(col.tests());
            return List.copyOf(all);
        }
    }

    /**
     * One column entry with its associated generic tests.
     *
     * @param name        column name
     * @param description optional description
     * @param tests       compiled {@link GenericTest} instances for this column
     */
    public record ColumnSchema(
            String name,
            String description,
            List<GenericTest> tests
    ) {
        public ColumnSchema {
            Objects.requireNonNull(name);
            tests = tests != null ? List.copyOf(tests) : List.of();
        }
    }
}
