package ai.durableagents.agents;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

import java.time.Duration;

/**
 * Builds langchain4j AI Services backed by either OpenAI or Claude.
 *
 * Provider selection via LLM_PROVIDER env var ("openai" or "anthropic", default "openai").
 *
 * OpenAI:     requires OPENAI_API_KEY.    Model: gpt-4o-mini
 * Anthropic:  requires ANTHROPIC_API_KEY. Model: claude-haiku-4-5-20251001
 */
public class AgentFactory {

    private final ChatModel model;

    public AgentFactory() {
        String provider = System.getenv().getOrDefault("LLM_PROVIDER", "openai").toLowerCase();
        this.model = switch (provider) {
            case "anthropic" -> buildAnthropicModel();
            default          -> buildOpenAiModel();
        };
    }

    private static ChatModel buildOpenAiModel() {
        String apiKey = requireEnv("OPENAI_API_KEY");
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gpt-4o-mini")
                .temperature(0.2)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    private static ChatModel buildAnthropicModel() {
        String apiKey = requireEnv("ANTHROPIC_API_KEY");
        return AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName("claude-haiku-4-5-20251001")
                .temperature(0.2)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable not set: " + name);
        }
        return value;
    }

    public TriageAgent triageAgent() {
        return AiServices.builder(TriageAgent.class).chatModel(model).build();
    }

    public RiskAssessmentAgent riskAssessmentAgent() {
        return AiServices.builder(RiskAssessmentAgent.class).chatModel(model).build();
    }

    public QualityReviewerAgent qualityReviewerAgent() {
        return AiServices.builder(QualityReviewerAgent.class).chatModel(model).build();
    }

    public FraudAnalysisAgent fraudAnalysisAgent() {
        return AiServices.builder(FraudAnalysisAgent.class).chatModel(model).build();
    }
}
