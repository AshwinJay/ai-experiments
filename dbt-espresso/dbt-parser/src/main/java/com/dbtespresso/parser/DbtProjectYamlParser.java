package com.dbtespresso.parser;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;

/**
 * Parses {@code dbt_project.yml} and {@code profiles.yml} using Jackson YAML.
 *
 * <p>Both files use hyphenated keys (e.g. {@code model-paths}) which are read
 * as-is into raw maps and then mapped manually to avoid binding to the exact
 * field naming conventions Jackson expects for records.
 */
public final class DbtProjectYamlParser {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private DbtProjectYamlParser() {}

    /** Parse a {@code dbt_project.yml} file at the given path. */
    public static ProjectConfig parseProject(Path dbtProjectYml) throws IOException {
        Map<String, Object> raw = YAML.readValue(dbtProjectYml.toFile(), MAP_TYPE);
        return mapToProjectConfig(raw);
    }

    /** Parse a {@code dbt_project.yml} from an input stream (e.g. classpath resource). */
    public static ProjectConfig parseProject(InputStream in) throws IOException {
        Map<String, Object> raw = YAML.readValue(in, MAP_TYPE);
        return mapToProjectConfig(raw);
    }

    /**
     * Parse a {@code profiles.yml} file and return one {@link ProfileConfig} per
     * top-level profile block.
     */
    public static List<ProfileConfig> parseProfiles(Path profilesYml) throws IOException {
        Map<String, Object> raw = YAML.readValue(profilesYml.toFile(), MAP_TYPE);
        return mapToProfileConfigs(raw);
    }

    /** Parse {@code profiles.yml} from an input stream. */
    public static List<ProfileConfig> parseProfiles(InputStream in) throws IOException {
        Map<String, Object> raw = YAML.readValue(in, MAP_TYPE);
        return mapToProfileConfigs(raw);
    }

    // -------------------------------------------------------------------------
    // Internal mapping helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static ProjectConfig mapToProjectConfig(Map<String, Object> raw) {
        String name    = str(raw.get("name"));
        String version = raw.containsKey("version") ? String.valueOf(raw.get("version")) : null;
        String profile = str(raw.get("profile"));

        List<String> modelPaths    = toStringList(raw.get("model-paths"));
        List<String> seedPaths     = toStringList(raw.get("seed-paths"));
        List<String> testPaths     = toStringList(raw.get("test-paths"));
        List<String> snapshotPaths = toStringList(raw.get("snapshot-paths"));

        Map<String, Object> models = toObjectMap(raw.get("models"));
        Map<String, Object> vars   = toObjectMap(raw.get("vars"));

        return new ProjectConfig(name, version, profile,
                modelPaths, seedPaths, testPaths, snapshotPaths, models, vars);
    }

    @SuppressWarnings("unchecked")
    private static List<ProfileConfig> mapToProfileConfigs(Map<String, Object> raw) {
        List<ProfileConfig> result = new ArrayList<>();
        for (var entry : raw.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> profileMap)) continue;

            String profileName    = entry.getKey();
            String defaultTarget  = str(profileMap.get("target"));
            Object outputsRaw     = profileMap.get("outputs");
            Map<String, ProfileConfig.OutputConfig> outputs = new LinkedHashMap<>();

            if (outputsRaw instanceof Map<?, ?> outputMap) {
                for (var outEntry : outputMap.entrySet()) {
                    if (outEntry.getValue() instanceof Map<?, ?> outConf) {
                        outputs.put(String.valueOf(outEntry.getKey()),
                                mapToOutputConfig(outConf));
                    }
                }
            }
            result.add(new ProfileConfig(profileName, defaultTarget, outputs));
        }
        return result;
    }

    private static ProfileConfig.OutputConfig mapToOutputConfig(Map<?, ?> raw) {
        String type     = str(raw.get("type"));
        String host     = str(raw.get("host"));
        Integer port    = intOrNull(raw.get("port"));
        String user     = str(raw.get("user"));
        String password = str(raw.get("password"));
        // BigQuery uses "project", Snowflake uses "account"; fall back to "database"
        String dbname   = firstStr(raw, "dbname", "database", "project", "account");
        String schema   = firstStr(raw, "schema", "dataset");
        int threads     = intOrDefault(raw.get("threads"), 1);
        return new ProfileConfig.OutputConfig(type, host, port, user, password, dbname, schema, threads);
    }

    // -------------------------------------------------------------------------
    // Utility helpers
    // -------------------------------------------------------------------------

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static String firstStr(Map<?, ?> map, String... keys) {
        for (String k : keys) {
            Object v = map.get(k);
            if (v != null) return v.toString();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object v) {
        if (v == null) return null;
        if (v instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of(v.toString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toObjectMap(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> result = new LinkedHashMap<>();
            m.forEach((k, val) -> result.put(String.valueOf(k), val));
            return result;
        }
        return null;
    }

    private static Integer intOrNull(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static int intOrDefault(Object v, int defaultValue) {
        Integer i = intOrNull(v);
        return i != null ? i : defaultValue;
    }
}
