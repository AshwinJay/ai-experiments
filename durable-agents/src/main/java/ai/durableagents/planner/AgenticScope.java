package ai.durableagents.planner;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared state container passed between agents. Each agent reads prior outputs
 * from the scope and writes its own output back. Plain Map wrapper — no
 * dependency on langchain4j-agentic's experimental AgenticScope.
 *
 * Must be JSON-serializable (Jackson) for Restate K/V storage if needed.
 */
public class AgenticScope {

    private final Map<String, String> data;

    public AgenticScope() {
        this.data = new HashMap<>();
    }

    @JsonCreator
    public AgenticScope(@JsonProperty("data") Map<String, String> data) {
        this.data = new HashMap<>(data);
    }

    public void put(String key, String value) {
        data.put(key, value);
    }

    public String get(String key) {
        return data.get(key);
    }

    public String getOrDefault(String key, String defaultValue) {
        return data.getOrDefault(key, defaultValue);
    }

    public boolean containsKey(String key) {
        return data.containsKey(key);
    }

    @JsonProperty("data")
    public Map<String, String> getData() {
        return Map.copyOf(data);
    }

    /** Renders all scope entries as a readable context string for agent prompts. */
    public String toContextString() {
        if (data.isEmpty()) return "(no prior context)";
        StringBuilder sb = new StringBuilder();
        data.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\n"));
        return sb.toString().trim();
    }

    @Override
    public String toString() {
        return "AgenticScope" + data;
    }
}
