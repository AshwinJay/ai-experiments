package com.dbtespresso.jinja;

import com.hubspot.jinjava.Jinjava;
import com.hubspot.jinjava.JinjavaConfig;

import java.util.*;
import java.util.regex.*;

/**
 * Renders dbt Jinja+SQL templates to plain SQL.
 *
 * <h2>Strategy</h2>
 * dbt-specific function calls (ref, source, config, var, is_incremental) are resolved
 * in a pre-processing pass using the {@link RenderContext}.  The result is then passed
 * to Jinjava so that standard Jinja2 constructs — {@code {% if %}}, {@code {% for %}},
 * {@code {% set %}}, filters, etc. — are rendered normally.
 *
 * <h2>Limitations</h2>
 * <ul>
 *   <li>Dynamic refs ({@code ref(var('model_name'))}) are not supported; use
 *       {@code depends_on} in schema.yml to declare those edges explicitly.</li>
 *   <li>Nested parentheses inside {@code config()} values (e.g. {@code meta={'k':'v(x)'}})
 *       may cause the config-strip regex to mis-parse.  Use a YAML schema file for
 *       complex configs.</li>
 * </ul>
 */
public final class JinjaRenderer {

    // Strips {{ config(...) }} — config is already extracted by ConfigExtractor
    private static final Pattern CONFIG_BLOCK = Pattern.compile(
            "\\{\\{-?\\s*config\\s*\\([^)]*\\)\\s*-?\\}\\}");

    // {{ ref('pkg', 'model') }} — two-arg; matched before single-arg
    private static final Pattern REF2 = Pattern.compile(
            "\\{\\{\\s*ref\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*,\\s*['\"]([^'\"]+)['\"]\\s*\\)\\s*\\}\\}");

    // {{ ref('model') }}
    private static final Pattern REF1 = Pattern.compile(
            "\\{\\{\\s*ref\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)\\s*\\}\\}");

    // {{ source('sourceName', 'tableName') }}
    private static final Pattern SOURCE = Pattern.compile(
            "\\{\\{\\s*source\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*,\\s*['\"]([^'\"]+)['\"]\\s*\\)\\s*\\}\\}");

    // is_incremental() — anywhere ({{ }} expressions AND {% %} control tags)
    private static final Pattern IS_INCREMENTAL = Pattern.compile(
            "\\bis_incremental\\s*\\(\\s*\\)");

    // var('name', default) — two-arg; stops at first ')' (MVP: no nested parens in default)
    private static final Pattern VAR2 = Pattern.compile(
            "\\bvar\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*,\\s*([^)]+?)\\s*\\)");

    // var('name') — single-arg
    private static final Pattern VAR1 = Pattern.compile(
            "\\bvar\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");

    private final Jinjava jinjava;

    public JinjaRenderer() {
        this.jinjava = new Jinjava(
                JinjavaConfig.newBuilder()
                        .withFailOnUnknownTokens(false)
                        .build());
    }

    /**
     * Renders a raw dbt Jinja+SQL template to plain SQL.
     *
     * @param rawSql unrendered template from a .sql file
     * @param ctx    resolution maps and variable bindings
     * @return plain SQL with all dbt function calls resolved and Jinja2 control flow rendered
     */
    public String render(String rawSql, RenderContext ctx) {
        String preprocessed = preprocess(rawSql, ctx);
        Map<String, Object> jinjavaCtx = new HashMap<>(ctx.vars());
        return jinjava.render(preprocessed, jinjavaCtx).strip();
    }

    // ── pre-processing ────────────────────────────────────────────────────────

    private String preprocess(String sql, RenderContext ctx) {
        String r = sql;

        // 1. Strip config blocks — already parsed by ConfigExtractor
        r = CONFIG_BLOCK.matcher(r).replaceAll("");

        // 2. Resolve two-arg ref() before single-arg to avoid partial matches
        r = REF2.matcher(r).replaceAll(m -> {
            String key      = m.group(1) + "." + m.group(2);
            String resolved = ctx.refResolutions().getOrDefault(key,
                              ctx.refResolutions().getOrDefault(m.group(2), m.group(2)));
            return Matcher.quoteReplacement(resolved);
        });

        // 3. Resolve single-arg ref()
        r = REF1.matcher(r).replaceAll(m -> Matcher.quoteReplacement(
                ctx.refResolutions().getOrDefault(m.group(1), m.group(1))));

        // 4. Resolve source()
        r = SOURCE.matcher(r).replaceAll(m -> {
            String key      = m.group(1) + "." + m.group(2);
            String resolved = ctx.sourceResolutions().getOrDefault(key, key);
            return Matcher.quoteReplacement(resolved);
        });

        // 5. Inline is_incremental() everywhere (works in both {{ }} and {% %} blocks)
        r = IS_INCREMENTAL.matcher(r).replaceAll(ctx.isIncremental() ? "true" : "false");

        // 6. Inline var() — two-arg before single-arg
        r = VAR2.matcher(r).replaceAll(m -> {
            Object val = ctx.vars().get(m.group(1));
            String replacement = val != null ? jinjaLiteral(val) : m.group(2).trim();
            return Matcher.quoteReplacement(replacement);
        });

        r = VAR1.matcher(r).replaceAll(m -> {
            Object val = ctx.vars().get(m.group(1));
            return Matcher.quoteReplacement(val != null ? jinjaLiteral(val) : "''");
        });

        return r;
    }

    /**
     * Converts a Java value to its Jinja2 literal representation so it can be
     * spliced back into the template for subsequent rendering.
     *
     * Strings are single-quoted; booleans become {@code true}/{@code false};
     * numbers and other types are stringified as-is.
     */
    static String jinjaLiteral(Object value) {
        if (value instanceof String s)  return "'" + s.replace("'", "\\'") + "'";
        if (value instanceof Boolean b) return b ? "true" : "false";
        return String.valueOf(value);
    }
}
