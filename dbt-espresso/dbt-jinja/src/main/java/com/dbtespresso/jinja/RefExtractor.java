package com.dbtespresso.jinja;

import java.util.*;
import java.util.regex.*;

/**
 * Extracts dbt dependency references (ref, source, metric) from Jinja-templated SQL
 * via static pattern matching. This runs BEFORE any SQL is executed — the DAG
 * must be known before anything can be sent to the warehouse.
 *
 * <h2>Why not full Jinja rendering?</h2>
 * Full rendering needs database context (adapter type, vars, env). But the DAG
 * must exist first to know execution order. Static extraction breaks this cycle.
 *
 * <h2>Known limitations</h2>
 * <ul>
 *   <li>Cannot resolve dynamic refs: ref(var('model_name'))</li>
 *   <li>Cannot evaluate conditional refs inside {% if %} accurately</li>
 *   <li>Workaround: explicit depends_on in schema.yml</li>
 * </ul>
 */
public final class RefExtractor {

    private static final String Q = "['\"]";
    private static final String N = "([a-zA-Z_][a-zA-Z0-9_]*)";

    // ref('model') — single arg
    private static final Pattern REF1 = Pattern.compile(
            "\\{\\{[^}]*\\bref\\s*\\(\\s*" + Q + N + Q + "\\s*\\)[^}]*\\}\\}");

    // ref('package', 'model') — two args
    private static final Pattern REF2 = Pattern.compile(
            "\\{\\{[^}]*\\bref\\s*\\(\\s*" + Q + N + Q + "\\s*,\\s*" + Q + N + Q + "\\s*\\)[^}]*\\}\\}");

    // source('source', 'table')
    private static final Pattern SRC = Pattern.compile(
            "\\{\\{[^}]*\\bsource\\s*\\(\\s*" + Q + N + Q + "\\s*,\\s*" + Q + N + Q + "\\s*\\)[^}]*\\}\\}");

    // metric('name')
    private static final Pattern MET = Pattern.compile(
            "\\{\\{[^}]*\\bmetric\\s*\\(\\s*" + Q + N + Q + "\\s*\\)[^}]*\\}\\}");

    // Jinja comments {# ... #}
    private static final Pattern COMMENT = Pattern.compile("\\{#.*?#\\}", Pattern.DOTALL);

    private RefExtractor() {}

    /** Extract all dependencies from raw Jinja+SQL content. */
    public static List<Dependency> extract(String sql) {
        if (sql == null || sql.isBlank()) return List.of();

        String cleaned = COMMENT.matcher(sql).replaceAll("");
        Set<Dependency> deps = new LinkedHashSet<>();

        for (var m = REF2.matcher(cleaned); m.find();)
            deps.add(new Dependency.ModelRef(m.group(1), m.group(2)));

        for (var m = REF1.matcher(cleaned); m.find();)
            deps.add(new Dependency.ModelRef(m.group(1)));

        for (var m = SRC.matcher(cleaned); m.find();)
            deps.add(new Dependency.SourceRef(m.group(1), m.group(2)));

        for (var m = MET.matcher(cleaned); m.find();)
            deps.add(new Dependency.MetricRef(m.group(1)));

        return new ArrayList<>(deps);
    }

    /** Extract only model refs. */
    public static List<Dependency.ModelRef> extractModelRefs(String sql) {
        return extract(sql).stream()
                .filter(Dependency.ModelRef.class::isInstance)
                .map(Dependency.ModelRef.class::cast)
                .toList();
    }

    /** Extract only source refs. */
    public static List<Dependency.SourceRef> extractSourceRefs(String sql) {
        return extract(sql).stream()
                .filter(Dependency.SourceRef.class::isInstance)
                .map(Dependency.SourceRef.class::cast)
                .toList();
    }
}
