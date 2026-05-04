package com.dbtespresso.parser;

import java.util.*;

/**
 * Parsed representation of dbt_project.yml.
 *
 * @param name          project name (required)
 * @param version       project version string
 * @param profile       profile name used to look up connections in profiles.yml
 * @param modelPaths    directories to scan for models (default: ["models"])
 * @param seedPaths     directories for seed CSV files (default: ["seeds"])
 * @param testPaths     directories for singular tests (default: ["tests"])
 * @param snapshotPaths directories for snapshots (default: ["snapshots"])
 * @param models        nested model config — keys are project/subdirectory names,
 *                      values are maps with "+materialized", "+schema", etc.
 * @param vars          project-level variable bindings
 */
public record ProjectConfig(
        String name,
        String version,
        String profile,
        List<String> modelPaths,
        List<String> seedPaths,
        List<String> testPaths,
        List<String> snapshotPaths,
        Map<String, Object> models,
        Map<String, Object> vars
) {
    public ProjectConfig {
        Objects.requireNonNull(name, "Project name is required in dbt_project.yml");
        modelPaths    = modelPaths    != null ? List.copyOf(modelPaths)    : List.of("models");
        seedPaths     = seedPaths     != null ? List.copyOf(seedPaths)     : List.of("seeds");
        testPaths     = testPaths     != null ? List.copyOf(testPaths)     : List.of("tests");
        snapshotPaths = snapshotPaths != null ? List.copyOf(snapshotPaths) : List.of("snapshots");
        models        = models        != null ? Map.copyOf(models)         : Map.of();
        vars          = vars          != null ? Map.copyOf(vars)           : Map.of();
    }

    /** Resolve the default materialization for a given model path component. */
    public String defaultMaterialization(String subdirectory) {
        if (models.containsKey(name)) {
            @SuppressWarnings("unchecked")
            var projectBlock = (Map<String, Object>) models.get(name);
            if (projectBlock.containsKey(subdirectory)) {
                @SuppressWarnings("unchecked")
                var subBlock = (Map<String, Object>) projectBlock.get(subdirectory);
                Object mat = subBlock.get("+materialized");
                if (mat != null) return mat.toString();
            }
            Object mat = projectBlock.get("+materialized");
            if (mat != null) return mat.toString();
        }
        return "view";
    }
}
