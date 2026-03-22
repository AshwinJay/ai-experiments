package ai.durableagents.model;

public record ClaimRequest(
    String claimId,
    String customerId,
    String claimType,
    double claimAmount,
    String description,
    String incidentDate
) {}
