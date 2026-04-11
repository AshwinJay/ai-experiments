package com.dbtespresso.graph;

import com.dbtespresso.jinja.Dependency;
import com.dbtespresso.parser.ParsedModel;

import java.util.*;
import java.util.stream.*;

/**
 * Directed acyclic graph of dbt models. Edges flow from dependency → dependent:
 * if model B refs model A, then the edge is A → B (A must run before B).
 *
 * <h2>Building the graph</h2>
 * Call {@link #fromModels(List)} with the output of {@link com.dbtespresso.parser.DbtProjectScanner}.
 * This wires up edges by matching each model's {@code ref('x')} to the model named 'x'.
 *
 * <h2>Execution levels</h2>
 * {@link #executionLevels()} returns models grouped by depth — all models in a level
 * can be executed in parallel because they have no mutual dependencies.
 *
 * <h2>No external graph library needed</h2>
 * The graph is small (hundreds to low thousands of nodes for even large dbt projects),
 * so a simple adjacency-list + Kahn's algorithm is sufficient.
 */
public final class ModelGraph {

    /** Adjacency list: node → set of nodes it points to (its dependents). */
    private final Map<String, Set<String>> edges;

    /** Reverse adjacency: node → set of nodes it depends on (its dependencies). */
    private final Map<String, Set<String>> reverseEdges;

    /** All models by name. */
    private final Map<String, ParsedModel> models;

    /** Source nodes (from source() calls) — they are roots with no SQL to execute. */
    private final Set<String> sourceNodes;

    private ModelGraph(
            Map<String, ParsedModel> models,
            Map<String, Set<String>> edges,
            Map<String, Set<String>> reverseEdges,
            Set<String> sourceNodes
    ) {
        this.models = Map.copyOf(models);
        this.edges = deepCopy(edges);
        this.reverseEdges = deepCopy(reverseEdges);
        this.sourceNodes = Set.copyOf(sourceNodes);
    }

    /**
     * Build the graph from parsed models.
     *
     * @throws CycleDetectedException if a circular dependency is found
     * @throws DanglingRefException if a ref points to a model that doesn't exist
     */
    public static ModelGraph fromModels(List<ParsedModel> parsedModels) {
        Map<String, ParsedModel> byName = new LinkedHashMap<>();
        for (var m : parsedModels) {
            if (byName.containsKey(m.name())) {
                throw new IllegalArgumentException("Duplicate model name: " + m.name()
                        + " at " + m.filePath() + " and " + byName.get(m.name()).filePath());
            }
            byName.put(m.name(), m);
        }

        Map<String, Set<String>> edges = new LinkedHashMap<>();
        Map<String, Set<String>> reverse = new LinkedHashMap<>();
        Set<String> sourceNodes = new LinkedHashSet<>();

        // Initialize all nodes
        for (String name : byName.keySet()) {
            edges.put(name, new LinkedHashSet<>());
            reverse.put(name, new LinkedHashSet<>());
        }

        // Wire edges from each ref
        for (var model : parsedModels) {
            for (var dep : model.dependencies()) {
                switch (dep) {
                    case Dependency.ModelRef ref -> {
                        String depName = ref.modelName();
                        if (!byName.containsKey(depName)) {
                            throw new DanglingRefException(
                                    "Model '" + model.name() + "' references '" + depName
                                            + "' which does not exist");
                        }
                        // Edge: depName → model.name() (dep runs first)
                        edges.get(depName).add(model.name());
                        reverse.get(model.name()).add(depName);
                    }
                    case Dependency.SourceRef src -> {
                        sourceNodes.add(src.qualifiedName());
                    }
                    default -> { /* metrics etc — tracked but don't affect execution order */ }
                }
            }
        }

        var graph = new ModelGraph(byName, edges, reverse, sourceNodes);
        graph.detectCycles();
        return graph;
    }

    /**
     * Topological sort using Kahn's algorithm.
     * Returns models in a valid execution order.
     */
    public List<String> topologicalSort() {
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        for (var entry : reverseEdges.entrySet()) {
            inDegree.put(entry.getKey(), entry.getValue().size());
        }

        Deque<String> queue = new ArrayDeque<>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        List<String> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String node = queue.poll();
            sorted.add(node);
            for (String dependent : edges.getOrDefault(node, Set.of())) {
                int remaining = inDegree.merge(dependent, -1, Integer::sum);
                if (remaining == 0) queue.add(dependent);
            }
        }

        if (sorted.size() != models.size()) {
            // Should never happen — detectCycles() is called during construction
            throw new CycleDetectedException("Cycle detected (topological sort incomplete)");
        }
        return sorted;
    }

    /**
     * Group models into execution levels. All models in a level can run in parallel
     * because none of them depend on each other.
     *
     * Level 0: models with no model dependencies (leaf nodes / sources)
     * Level 1: models that only depend on level 0
     * Level N: models that depend on level N-1 or lower
     *
     * @return ordered list of levels, each level is a list of model names
     */
    public List<List<String>> executionLevels() {
        Map<String, Integer> depth = new LinkedHashMap<>();
        List<String> sorted = topologicalSort();

        for (String node : sorted) {
            int maxParentDepth = reverseEdges.get(node).stream()
                    .mapToInt(parent -> depth.getOrDefault(parent, 0))
                    .max()
                    .orElse(-1);
            depth.put(node, maxParentDepth + 1);
        }

        int maxLevel = depth.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<List<String>> levels = new ArrayList<>();
        for (int i = 0; i <= maxLevel; i++) {
            final int level = i;
            levels.add(
                    depth.entrySet().stream()
                            .filter(e -> e.getValue() == level)
                            .map(Map.Entry::getKey)
                            .toList()
            );
        }
        return levels;
    }

    /**
     * Get all ancestors (transitive dependencies) of a model.
     * Useful for --select +model (run model and everything upstream).
     */
    public Set<String> ancestors(String modelName) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>(reverseEdges.getOrDefault(modelName, Set.of()));
        while (!stack.isEmpty()) {
            String node = stack.pop();
            if (visited.add(node)) {
                stack.addAll(reverseEdges.getOrDefault(node, Set.of()));
            }
        }
        return visited;
    }

    /**
     * Get all descendants (transitive dependents) of a model.
     * Useful for --select model+ (run model and everything downstream).
     */
    public Set<String> descendants(String modelName) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>(edges.getOrDefault(modelName, Set.of()));
        while (!stack.isEmpty()) {
            String node = stack.pop();
            if (visited.add(node)) {
                stack.addAll(edges.getOrDefault(node, Set.of()));
            }
        }
        return visited;
    }

    /**
     * Select a subgraph: the named models plus optionally their ancestors/descendants.
     * Mirrors dbt's --select flag behavior.
     *
     * @param names models to select
     * @param upstream if true, include all ancestors (+model syntax)
     * @param downstream if true, include all descendants (model+ syntax)
     * @return set of selected model names
     */
    public Set<String> select(Collection<String> names, boolean upstream, boolean downstream) {
        Set<String> selected = new LinkedHashSet<>(names);
        for (String name : names) {
            if (upstream) selected.addAll(ancestors(name));
            if (downstream) selected.addAll(descendants(name));
        }
        return selected;
    }

    /** Get a model by name. */
    public Optional<ParsedModel> model(String name) {
        return Optional.ofNullable(models.get(name));
    }

    /** All model names. */
    public Set<String> modelNames() { return models.keySet(); }

    /** All source node identifiers. */
    public Set<String> sourceNodes() { return sourceNodes; }

    /** Number of models in the graph. */
    public int size() { return models.size(); }

    /** Direct dependencies of a model (what it refs). */
    public Set<String> dependenciesOf(String name) {
        return Set.copyOf(reverseEdges.getOrDefault(name, Set.of()));
    }

    /** Direct dependents of a model (what refs it). */
    public Set<String> dependentsOf(String name) {
        return Set.copyOf(edges.getOrDefault(name, Set.of()));
    }

    // --- Cycle detection via DFS ---

    private void detectCycles() {
        Set<String> white = new LinkedHashSet<>(models.keySet()); // unvisited
        Set<String> gray = new LinkedHashSet<>(); // in current DFS path
        List<String> cyclePath = new ArrayList<>();

        for (String node : models.keySet()) {
            if (white.contains(node)) {
                if (dfs(node, white, gray, cyclePath)) {
                    throw new CycleDetectedException(
                            "Circular dependency detected: " + String.join(" → ", cyclePath));
                }
            }
        }
    }

    private boolean dfs(String node, Set<String> white, Set<String> gray, List<String> path) {
        white.remove(node);
        gray.add(node);
        path.add(node);

        for (String neighbor : edges.getOrDefault(node, Set.of())) {
            if (gray.contains(neighbor)) {
                path.add(neighbor); // close the cycle
                // Trim path to just the cycle
                int cycleStart = path.indexOf(neighbor);
                path.subList(0, cycleStart).clear();
                return true;
            }
            if (white.contains(neighbor) && dfs(neighbor, white, gray, path)) {
                return true;
            }
        }

        gray.remove(node);
        path.removeLast();
        return false;
    }

    private static Map<String, Set<String>> deepCopy(Map<String, Set<String>> original) {
        var copy = new LinkedHashMap<String, Set<String>>();
        original.forEach((k, v) -> copy.put(k, new LinkedHashSet<>(v)));
        return copy;
    }
}
