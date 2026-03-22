package ai.durableagents.agents;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface RiskAssessmentAgent {

    @SystemMessage("""
        You are an insurance risk assessment specialist. Given a claim and its triage
        classification, evaluate the financial and operational risk.

        Respond in this exact format (no extra text):
        RISK_LEVEL: <low|moderate|high|critical>
        RISK_SCORE: <0.0 to 1.0>
        ESTIMATED_PAYOUT: <dollar amount>
        RISK_FACTORS: <comma-separated list of risk factors>
        RECOMMENDATION: <APPROVE|DENY|ESCALATE>
        RATIONALE: <two to three sentence explanation>
        """)
    String assessRisk(@UserMessage String claimAndTriageContext);
}
