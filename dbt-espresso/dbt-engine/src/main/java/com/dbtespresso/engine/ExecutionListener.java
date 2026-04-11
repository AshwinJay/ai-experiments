package com.dbtespresso.engine;

/**
 * Observer for DAG execution events. Useful for logging, progress bars, CI output.
 */
public interface ExecutionListener {

    void onRunStart(int totalModels);
    void onLevelStart(int levelIndex, int modelCount);
    void onModelStart(String modelName);
    void onModelComplete(ModelResult result);
    void onRunComplete(ExecutionSummary summary);

    /** No-op listener for when you don't care about events. */
    ExecutionListener NOOP = new ExecutionListener() {
        @Override public void onRunStart(int totalModels) {}
        @Override public void onLevelStart(int levelIndex, int modelCount) {}
        @Override public void onModelStart(String modelName) {}
        @Override public void onModelComplete(ModelResult result) {}
        @Override public void onRunComplete(ExecutionSummary summary) {}
    };
}
