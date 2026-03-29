package ai.durableagents.integration;

import ai.durableagents.agents.AgentFactory;
import ai.durableagents.restate.ClaimProcessor;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.restate.sdk.endpoint.Endpoint;
import dev.restate.sdk.testing.RestateRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Happy path: low-value auto claim ($2,500) auto-approves without human review.
 */
class HappyPathIT {

    static final AgentFactory STUB_FACTORY = new AgentFactory(new RoutingStubChatModel());
    static RestateRunner RUNNER;

    @BeforeAll
    static void setUp() {
        RUNNER = RestateRunner.from(
                Endpoint.bind(new ClaimProcessor(STUB_FACTORY)).build()
        ).build();
        RUNNER.start();
    }

    @AfterAll
    static void tearDown() {
        if (RUNNER != null) RUNNER.stop();
    }

    @Test
    void lowValueClaimAutoApproves() throws Exception {
        String body = """
                {
                  "claimId": "it-claim-001",
                  "customerId": "cust-42",
                  "claimType": "auto",
                  "claimAmount": 2500.0,
                  "description": "Minor fender bender in parking lot.",
                  "incidentDate": "2026-03-01"
                }
                """;

        URI ingressURI = RUNNER.getRestateUrl().toURI();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(ingressURI.resolve("/ClaimProcessor/it-claim-001/processClaim"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "Expected HTTP 200, got: " + response.body());
        String responseBody = response.body();
        assertTrue(responseBody.contains("\"decision\":\"APPROVED\""),
                "Expected APPROVED in: " + responseBody);
        assertTrue(responseBody.contains("\"fraudFlag\":false"),
                "Expected fraudFlag:false in: " + responseBody);
    }

    /**
     * Routes on @SystemMessage keyword to return deterministic responses for each agent.
     */
    static class RoutingStubChatModel implements ChatModel {

        @Override
        public ChatResponse chat(ChatRequest request) {
            String systemText = request.messages().stream()
                    .filter(m -> m instanceof SystemMessage)
                    .map(m -> ((SystemMessage) m).text())
                    .findFirst()
                    .orElse("");

            String text;
            if (systemText.contains("triage specialist")) {
                text = "CATEGORY: auto_minor\nSEVERITY: low\nNEEDS_HUMAN_REVIEW: false\nSUMMARY: Minor auto claim.";
            } else if (systemText.contains("quality assurance reviewer")) {
                text = "QUALITY_SCORE: 0.9\nFEEDBACK: Assessment is thorough.";
            } else if (systemText.contains("fraud")) {
                text = "FRAUD_FLAG: false\nFRAUD_SCORE: 0.0\nINDICATORS: none\nFRAUD_RATIONALE: No indicators.";
            } else {
                // risk assessment
                text = "RISK_LEVEL: low\nRISK_SCORE: 0.1\nESTIMATED_PAYOUT: $2500\nRISK_FACTORS: none\nRECOMMENDATION: APPROVE\nRATIONALE: Low value claim.";
            }

            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(text))
                    .build();
        }
    }
}
