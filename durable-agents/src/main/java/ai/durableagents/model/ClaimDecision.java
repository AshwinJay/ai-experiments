package ai.durableagents.model;

public record ClaimDecision(
    String claimId,
    String decision,      // APPROVED | DENIED | PENDING_REVIEW
    String rationale,
    String riskLevel,     // low | moderate | high
    boolean fraudFlag
) {}
