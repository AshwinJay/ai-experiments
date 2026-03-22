package ai.durableagents.restate;

import ai.durableagents.agents.AgentFactory;
import ai.durableagents.model.ClaimDecision;
import ai.durableagents.model.ClaimRequest;
import ai.durableagents.planner.*;
import dev.restate.sdk.ObjectContext;
import dev.restate.sdk.annotation.Handler;
import dev.restate.sdk.annotation.VirtualObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Function;

@VirtualObject
public class ClaimProcessor {

    private static final Logger log = LoggerFactory.getLogger(ClaimProcessor.class);

    // Quality threshold for the risk assessment loop
    private static final double QUALITY_THRESHOLD = 0.8;
    private static final int MAX_REVIEW_ITERATIONS = 5;

    @Handler
    public ClaimDecision processClaim(ObjectContext ctx, ClaimRequest req) {
        log.info("Processing claim {} for customer {}", req.claimId(), req.customerId());

        AgentFactory factory = new AgentFactory();

        // --- Build agent registry ---
        Map<String, Function<AgenticScope, String>> agents = Map.of(
            "triage", scope -> factory.triageAgent().triage(buildClaimContext(req, scope)),
            "riskAssess", scope -> factory.riskAssessmentAgent().assessRisk(buildClaimContext(req, scope)),
            "qualityReview", scope -> factory.qualityReviewerAgent().review(buildClaimContext(req, scope)),
            "fraudAnalysis", scope -> factory.fraudAnalysisAgent().analyzeFraud(buildClaimContext(req, scope))
        );

        // --- Build planner pipeline ---
        //
        // Stage 1: Triage (once)
        // Stage 2: Risk assessment loop — riskAssess + qualityReview until quality >= 0.8 or 5 iterations
        // Stage 3: Fraud analysis (once)

        DurablePlanner pipeline = new CompositePlanner(
            new SequentialPlanner("triage"),
            new LoopPlanner(
                scope -> parseQualityScore(scope) >= QUALITY_THRESHOLD,
                MAX_REVIEW_ITERATIONS,
                "riskAssess", "qualityReview"
            ),
            new SequentialPlanner("fraudAnalysis")
        );

        // --- Run the pipeline ---
        AgenticScope scope = new AgenticScope();
        DurableWorkflowEngine engine = new DurableWorkflowEngine();
        scope = engine.run(ctx, pipeline, agents, scope);

        // --- Human-in-the-loop for high-value or flagged claims ---
        boolean needsHumanReview = parseBoolean(scope.getOrDefault("triage_output", ""), "NEEDS_HUMAN_REVIEW")
                || parseBoolean(scope.getOrDefault("fraudAnalysis_output", ""), "FRAUD_FLAG");

        if (needsHumanReview) {
            log.info("Claim {} requires human review — suspending", req.claimId());
            var awakeable = ctx.awakeable(String.class);
            // The handler suspends here. Resume by calling:
            // POST /restate/awakeables/{id}/resolve  with body "APPROVED" or "DENIED"
            String humanDecision = awakeable.await();
            log.info("Claim {} human decision: {}", req.claimId(), humanDecision);
            return buildDecision(req.claimId(), humanDecision, scope, true);
        }

        return buildDecision(req.claimId(), deriveDecision(scope), scope, false);
    }

    // --- Helpers ---

    private String buildClaimContext(ClaimRequest req, AgenticScope scope) {
        return """
            CLAIM ID: %s
            CUSTOMER ID: %s
            CLAIM TYPE: %s
            CLAIM AMOUNT: $%.2f
            INCIDENT DATE: %s
            DESCRIPTION: %s

            PRIOR CONTEXT:
            %s
            """.formatted(
                req.claimId(),
                req.customerId(),
                req.claimType(),
                req.claimAmount(),
                req.incidentDate(),
                req.description(),
                scope.toContextString()
            );
    }

    private double parseQualityScore(AgenticScope scope) {
        String output = scope.getOrDefault("qualityReview_output", "");
        for (String line : output.split("\n")) {
            if (line.startsWith("QUALITY_SCORE:")) {
                try {
                    return Double.parseDouble(line.substring("QUALITY_SCORE:".length()).trim());
                } catch (NumberFormatException ignored) {}
            }
        }
        return 0.0;
    }

    private boolean parseBoolean(String output, String key) {
        for (String line : output.split("\n")) {
            if (line.startsWith(key + ":")) {
                return "true".equalsIgnoreCase(line.substring(key.length() + 1).trim());
            }
        }
        return false;
    }

    private String parseField(String output, String key) {
        for (String line : output.split("\n")) {
            if (line.startsWith(key + ":")) {
                return line.substring(key.length() + 1).trim();
            }
        }
        return "";
    }

    private String deriveDecision(AgenticScope scope) {
        // Fraud flag → deny
        if (parseBoolean(scope.getOrDefault("fraudAnalysis_output", ""), "FRAUD_FLAG")) {
            return "DENIED";
        }
        // Use risk agent's recommendation
        String rec = parseField(scope.getOrDefault("riskAssess_output", ""), "RECOMMENDATION");
        return switch (rec.toUpperCase()) {
            case "APPROVE" -> "APPROVED";
            case "DENY"    -> "DENIED";
            default        -> "PENDING_REVIEW";
        };
    }

    private ClaimDecision buildDecision(String claimId, String decision, AgenticScope scope, boolean humanReviewed) {
        String riskLevel = parseField(scope.getOrDefault("riskAssess_output", ""), "RISK_LEVEL");
        boolean fraudFlag = parseBoolean(scope.getOrDefault("fraudAnalysis_output", ""), "FRAUD_FLAG");
        String rationale = humanReviewed
            ? "Decision made by human reviewer."
            : parseField(scope.getOrDefault("riskAssess_output", ""), "RATIONALE");

        return new ClaimDecision(claimId, decision, rationale, riskLevel, fraudFlag);
    }
}
