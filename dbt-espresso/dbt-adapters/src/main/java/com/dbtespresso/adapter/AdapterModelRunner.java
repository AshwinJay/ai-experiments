package com.dbtespresso.adapter;

import com.dbtespresso.engine.ModelResult;
import com.dbtespresso.engine.ModelRunner;
import com.dbtespresso.jinja.JinjaRenderer;
import com.dbtespresso.jinja.RenderContext;
import com.dbtespresso.parser.ParsedModel;
import com.dbtespresso.qa.AdapterContract;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Connects {@link GraphExecutor} to a warehouse {@link AdapterContract}.
 *
 * Each call to {@link #run} opens its own connection via the factory so that
 * concurrent virtual threads in {@code GraphExecutor} never share a
 * {@link java.sql.Connection}.
 */
public final class AdapterModelRunner implements ModelRunner {

    private final Supplier<AdapterContract> adapterFactory;
    private final JinjaRenderer renderer;
    private final String schema;
    private final Set<String> allModelNames;

    /**
     * @param adapterFactory called once per model to produce a fresh, unopened adapter
     * @param renderer       Jinja renderer for resolving {@code ref()} and template logic
     * @param schema         target schema (e.g. "public")
     * @param allModelNames  all model names in this run — used to build the ref-resolution map
     */
    public AdapterModelRunner(Supplier<AdapterContract> adapterFactory,
                              JinjaRenderer renderer,
                              String schema,
                              Set<String> allModelNames) {
        this.adapterFactory = adapterFactory;
        this.renderer = renderer;
        this.schema = schema;
        this.allModelNames = Set.copyOf(allModelNames);
    }

    @Override
    public ModelResult run(ParsedModel model) {
        Instant start = Instant.now();
        AdapterContract adapter = adapterFactory.get();
        try {
            String sql = renderer.render(model.rawSql(), buildContext());
            adapter.open();
            try {
                if ("table".equalsIgnoreCase(model.materialized())) {
                    adapter.createTableAs(schema, model.name(), sql, true);
                } else {
                    adapter.createViewAs(schema, model.name(), sql, true);
                }
            } finally {
                adapter.close();
            }
            return ModelResult.success(model.name(), start, Duration.between(start, Instant.now()), -1);
        } catch (Exception e) {
            return ModelResult.error(model.name(), start, Duration.between(start, Instant.now()), e.getMessage());
        }
    }

    private RenderContext buildContext() {
        RenderContext.Builder builder = RenderContext.builder();
        allModelNames.forEach(name -> builder.ref(name, schema + "." + name));
        return builder.build();
    }
}
