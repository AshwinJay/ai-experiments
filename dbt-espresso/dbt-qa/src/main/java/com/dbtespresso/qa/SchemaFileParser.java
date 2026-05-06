package com.dbtespresso.qa;

import com.dbtespresso.qa.SchemaFile.*;
import com.dbtespresso.qa.UnitTestDefinition.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;

/**
 * Parses {@code schema.yml} files into {@link SchemaFile} records containing
 * {@link GenericTest} and {@link UnitTestDefinition} instances.
 *
 * <h2>schema.yml shape</h2>
 * <pre>
 * version: 2
 * models:
 *   - name: orders
 *     columns:
 *       - name: order_id
 *         data_tests:
 *           - unique
 *           - not_null
 *       - name: status
 *         data_tests:
 *           - accepted_values:
 *               values: [placed, shipped]
 * sources:
 *   - name: raw
 *     tables:
 *       - name: orders
 *         columns:
 *           - name: id
 *             data_tests: [not_null]
 * unit_tests:
 *   - name: test_orders
 *     model: orders
 *     given:
 *       - input: ref('stg_orders')
 *         format: csv
 *         rows: |
 *           id,status
 *           1,placed
 *     expect:
 *       rows:
 *         - {order_id: 1, status: placed}
 * </pre>
 */
public final class SchemaFileParser {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private SchemaFileParser() {}

    /** Parse a {@code schema.yml} file at the given path. */
    public static SchemaFile parse(Path schemaYml) throws IOException {
        Map<String, Object> raw = YAML.readValue(schemaYml.toFile(), MAP_TYPE);
        return mapToSchemaFile(raw);
    }

    /** Parse {@code schema.yml} from an input stream (e.g. classpath resource). */
    public static SchemaFile parse(InputStream in) throws IOException {
        Map<String, Object> raw = YAML.readValue(in, MAP_TYPE);
        return mapToSchemaFile(raw);
    }

    /** Parse {@code schema.yml} from a YAML string (useful in tests). */
    public static SchemaFile parseString(String yaml) throws IOException {
        Map<String, Object> raw = YAML.readValue(yaml, MAP_TYPE);
        return mapToSchemaFile(raw);
    }

    // -----------------------------------------------------------------------
    // Internal mapping
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static SchemaFile mapToSchemaFile(Map<String, Object> raw) {
        List<ModelSchema> models = new ArrayList<>();
        List<SourceSchema> sources = new ArrayList<>();
        List<UnitTestDefinition> unitTests = new ArrayList<>();

        if (raw.get("models") instanceof List<?> modelList) {
            for (Object m : modelList) {
                if (m instanceof Map<?, ?> mMap) models.add(mapToModelSchema((Map<String, Object>) mMap));
            }
        }
        if (raw.get("sources") instanceof List<?> sourceList) {
            for (Object s : sourceList) {
                if (s instanceof Map<?, ?> sMap) sources.add(mapToSourceSchema((Map<String, Object>) sMap));
            }
        }
        if (raw.get("unit_tests") instanceof List<?> utList) {
            for (Object u : utList) {
                if (u instanceof Map<?, ?> uMap) unitTests.add(mapToUnitTest((Map<String, Object>) uMap));
            }
        }
        return new SchemaFile(models, sources, unitTests);
    }

    @SuppressWarnings("unchecked")
    private static ModelSchema mapToModelSchema(Map<String, Object> raw) {
        String name        = str(raw.get("name"));
        String description = str(raw.get("description"));
        Map<String, Object> config = toObjectMap(raw.get("config"));

        List<ColumnSchema> columns = new ArrayList<>();
        if (raw.get("columns") instanceof List<?> cols) {
            for (Object c : cols) {
                if (c instanceof Map<?, ?> cMap) columns.add(mapToColumn(name, (Map<String, Object>) cMap));
            }
        }

        List<GenericTest> modelTests = parseDataTests(name, null, raw.get("data_tests"));
        return new ModelSchema(name, description, config, columns, modelTests);
    }

    @SuppressWarnings("unchecked")
    private static SourceSchema mapToSourceSchema(Map<String, Object> raw) {
        String sourceName  = str(raw.get("name"));
        String description = str(raw.get("description"));

        List<SourceTableSchema> tables = new ArrayList<>();
        if (raw.get("tables") instanceof List<?> tableList) {
            for (Object t : tableList) {
                if (t instanceof Map<?, ?> tMap) {
                    tables.add(mapToSourceTable(sourceName, (Map<String, Object>) tMap));
                }
            }
        }
        return new SourceSchema(sourceName, description, tables);
    }

    @SuppressWarnings("unchecked")
    private static SourceTableSchema mapToSourceTable(String sourceName, Map<String, Object> raw) {
        String tableName   = str(raw.get("name"));
        String description = str(raw.get("description"));

        // For source columns, model name is "sourceName.tableName" by convention
        String qualifiedName = sourceName + "." + tableName;
        List<ColumnSchema> columns = new ArrayList<>();
        if (raw.get("columns") instanceof List<?> cols) {
            for (Object c : cols) {
                if (c instanceof Map<?, ?> cMap) columns.add(mapToColumn(qualifiedName, (Map<String, Object>) cMap));
            }
        }
        return new SourceTableSchema(sourceName, tableName, description, columns);
    }

    @SuppressWarnings("unchecked")
    private static ColumnSchema mapToColumn(String modelName, Map<String, Object> raw) {
        String colName     = str(raw.get("name"));
        String description = str(raw.get("description"));
        List<GenericTest> tests = parseDataTests(modelName, colName, raw.get("data_tests"));
        return new ColumnSchema(colName, description, tests);
    }

    // -----------------------------------------------------------------------
    // data_tests parsing
    // -----------------------------------------------------------------------

    /**
     * Parse a {@code data_tests:} list into {@link GenericTest} records.
     *
     * <p>Each entry is either:
     * <ul>
     *   <li>A plain string: {@code "not_null"}, {@code "unique"}</li>
     *   <li>A single-entry map: {@code {accepted_values: {values: [a, b]}}}</li>
     *   <li>A dotted name map: {@code {dbt_expectations.expect_foo: {arg: val}}}</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private static List<GenericTest> parseDataTests(String modelName, String colName, Object raw) {
        if (raw == null) return List.of();
        List<GenericTest> result = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return result;

        for (Object entry : list) {
            if (entry instanceof String s) {
                result.add(buildGenericTest(s, modelName, colName, Map.of()));
            } else if (entry instanceof Map<?, ?> m && m.size() == 1) {
                Map.Entry<?, ?> e = m.entrySet().iterator().next();
                String qualifiedName = String.valueOf(e.getKey());
                Map<String, Object> args = e.getValue() instanceof Map<?, ?> argMap
                        ? toObjectMap(argMap) : Map.of();
                result.add(buildGenericTest(qualifiedName, modelName, colName, args));
            }
        }
        return result;
    }

    private static GenericTest buildGenericTest(
            String qualifiedName, String modelName, String colName, Map<String, Object> args) {
        String pkg = null;
        String testName = qualifiedName;
        int dot = qualifiedName.indexOf('.');
        if (dot != -1) {
            pkg      = qualifiedName.substring(0, dot);
            testName = qualifiedName.substring(dot + 1);
        }
        GenericTest.TestSeverity severity = parseSeverity(args.get("severity"));
        @SuppressWarnings("unchecked")
        Map<String, Object> config = args.get("config") instanceof Map<?, ?> cfg
                ? toObjectMap(cfg) : Map.of();

        Map<String, Object> cleanArgs = new LinkedHashMap<>(args);
        cleanArgs.remove("severity");
        cleanArgs.remove("config");

        return new GenericTest(testName, pkg, modelName, colName, cleanArgs, severity, config);
    }

    // -----------------------------------------------------------------------
    // unit_tests parsing
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static UnitTestDefinition mapToUnitTest(Map<String, Object> raw) {
        String name      = str(raw.get("name"));
        String modelName = str(raw.get("model"));

        List<MockInput> given = new ArrayList<>();
        if (raw.get("given") instanceof List<?> givenList) {
            for (Object g : givenList) {
                if (g instanceof Map<?, ?> gMap) given.add(mapToMockInput((Map<String, Object>) gMap));
            }
        }

        ExpectedOutput expected = mapToExpectedOutput(raw.get("expect"));
        Map<String, Object> overrides = toObjectMap(raw.get("overrides"));
        return new UnitTestDefinition(name, modelName, given, expected, overrides);
    }

    @SuppressWarnings("unchecked")
    private static MockInput mapToMockInput(Map<String, Object> raw) {
        String input     = str(raw.get("input"));
        String fixtureName = str(raw.get("fixture"));
        MockInput.InputFormat format = parseInputFormat(raw.get("format"), raw.get("rows"));
        String rows = rowsToString(raw.get("rows"), format);
        return new MockInput(input, format, rows, fixtureName);
    }

    @SuppressWarnings("unchecked")
    private static ExpectedOutput mapToExpectedOutput(Object raw) {
        if (raw == null) return new ExpectedOutput(MockInput.InputFormat.DICT, "", null);
        if (!(raw instanceof Map<?, ?> m)) return new ExpectedOutput(MockInput.InputFormat.DICT, "", null);

        String fixtureName = str(m.get("fixture"));
        MockInput.InputFormat format = parseInputFormat(m.get("format"), m.get("rows"));
        String rows = rowsToString(m.get("rows"), format);
        return new ExpectedOutput(format, rows, fixtureName);
    }

    // -----------------------------------------------------------------------
    // rows / format helpers
    // -----------------------------------------------------------------------

    private static MockInput.InputFormat parseInputFormat(Object fmtRaw, Object rowsRaw) {
        if (fmtRaw != null) {
            String fmtStr = fmtRaw.toString().toUpperCase();
            return switch (fmtStr) {
                case "CSV"  -> MockInput.InputFormat.CSV;
                case "SQL"  -> MockInput.InputFormat.SQL;
                default     -> MockInput.InputFormat.DICT;
            };
        }
        // Infer from rows type: string → CSV, list → DICT
        if (rowsRaw instanceof String) return MockInput.InputFormat.CSV;
        return MockInput.InputFormat.DICT;
    }

    /** Serialise rows back to a string representation for downstream consumers. */
    @SuppressWarnings("unchecked")
    private static String rowsToString(Object rowsRaw, MockInput.InputFormat format) {
        if (rowsRaw == null) return "";
        if (rowsRaw instanceof String s) return s;
        if (rowsRaw instanceof List<?> list) {
            // Convert list-of-maps to a simple key=value representation stored as the raw rows string.
            // UnitTestCompiler's DICT path currently accepts this form.
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    sb.append('{');
                    boolean first = true;
                    for (var entry : ((Map<String, Object>) m).entrySet()) {
                        if (!first) sb.append(", ");
                        sb.append(entry.getKey()).append(": ").append(entry.getValue());
                        first = false;
                    }
                    sb.append("}\n");
                }
            }
            return sb.toString().strip();
        }
        return rowsRaw.toString();
    }

    // -----------------------------------------------------------------------
    // Utility helpers
    // -----------------------------------------------------------------------

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toObjectMap(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> result = new LinkedHashMap<>();
            m.forEach((k, val) -> result.put(String.valueOf(k), val));
            return result;
        }
        return Map.of();
    }

    private static GenericTest.TestSeverity parseSeverity(Object v) {
        if (v == null) return null;
        try { return GenericTest.TestSeverity.valueOf(v.toString().toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }
}
