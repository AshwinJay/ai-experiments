package com.dbtespresso.jinja;

import java.util.*;

/**
 * Resolution maps and variable bindings for Jinja template rendering.
 *
 * @param refResolutions    model name (or "pkg.model") → fully-qualified table name
 * @param sourceResolutions "sourceName.tableName" → fully-qualified table name
 * @param vars              dbt project variable bindings
 * @param isIncremental     whether the current run is an incremental materialization
 */
public record RenderContext(
        Map<String, String> refResolutions,
        Map<String, String> sourceResolutions,
        Map<String, Object> vars,
        boolean isIncremental
) {
    public RenderContext {
        refResolutions    = refResolutions    != null ? Map.copyOf(refResolutions)    : Map.of();
        sourceResolutions = sourceResolutions != null ? Map.copyOf(sourceResolutions) : Map.of();
        vars              = vars              != null ? Map.copyOf(vars)              : Map.of();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final Map<String, String> refs    = new LinkedHashMap<>();
        private final Map<String, String> sources = new LinkedHashMap<>();
        private final Map<String, Object> vars    = new LinkedHashMap<>();
        private boolean isIncremental = false;

        /** Register ref('modelName') → resolvedTable. */
        public Builder ref(String modelName, String resolvedTable) {
            refs.put(modelName, resolvedTable);
            return this;
        }

        /** Register ref('packageName', 'modelName') → resolvedTable. */
        public Builder ref(String packageName, String modelName, String resolvedTable) {
            refs.put(packageName + "." + modelName, resolvedTable);
            return this;
        }

        /** Register source('sourceName', 'tableName') → resolvedTable. */
        public Builder source(String sourceName, String tableName, String resolvedTable) {
            sources.put(sourceName + "." + tableName, resolvedTable);
            return this;
        }

        public Builder var(String name, Object value) {
            vars.put(name, value);
            return this;
        }

        public Builder vars(Map<String, Object> allVars) {
            vars.putAll(allVars);
            return this;
        }

        public Builder incremental(boolean value) {
            this.isIncremental = value;
            return this;
        }

        public RenderContext build() {
            return new RenderContext(refs, sources, vars, isIncremental);
        }
    }
}
