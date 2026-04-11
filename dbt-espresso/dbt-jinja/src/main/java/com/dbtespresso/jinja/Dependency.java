package com.dbtespresso.jinja;

/**
 * A dependency extracted from a dbt SQL template.
 * Discovered by static analysis of Jinja function calls.
 */
public sealed interface Dependency {

    /** ref('model') or ref('package', 'model') */
    record ModelRef(String packageName, String modelName) implements Dependency {
        public ModelRef {
            if (modelName == null || modelName.isBlank())
                throw new IllegalArgumentException("modelName must not be blank");
        }
        public ModelRef(String modelName) { this(null, modelName); }

        public String qualifiedName() {
            return packageName != null ? packageName + "." + modelName : modelName;
        }
    }

    /** source('source_name', 'table_name') */
    record SourceRef(String sourceName, String tableName) implements Dependency {
        public SourceRef {
            if (sourceName == null || sourceName.isBlank())
                throw new IllegalArgumentException("sourceName must not be blank");
            if (tableName == null || tableName.isBlank())
                throw new IllegalArgumentException("tableName must not be blank");
        }
        public String qualifiedName() { return "source:" + sourceName + "." + tableName; }
    }

    /** metric('name') */
    record MetricRef(String metricName) implements Dependency {
        public MetricRef {
            if (metricName == null || metricName.isBlank())
                throw new IllegalArgumentException("metricName must not be blank");
        }
        public String qualifiedName() { return "metric:" + metricName; }
    }

    String qualifiedName();
}
