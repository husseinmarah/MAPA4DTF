package milo.agents;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import milo.security.OPAClient;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the trust scores of all agents in the federation.
 * Scores are updated based on task outcomes by querying an OPA policy with
 * dynamic parameters.
 * Provides a service for querying agent trust scores.
 */
public class TrustManagerAgent extends Agent {

    private static double INITIAL_TRUST_SCORE = 0;

    private Map<String, Double> trustScores = new ConcurrentHashMap<>();
    private OPAClient opaClient;
    private milo.security.KeycloakClient keycloakClient;

    @Override
    protected void setup() {
        System.out.println("✅ " + getLocalName() + " ready (Dynamic Trust Management active)");
        opaClient = new OPAClient();
        keycloakClient = new milo.security.KeycloakClient();
        registerWithDF();

        addBehaviour(new TrustManagementBehaviour());
    }

    private void registerWithDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("TrustManagement");
        sd.setName(getLocalName() + "-dynamic-trust-service");
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
            System.out.println("✅ " + getLocalName() + " registered 'TrustManagement' service with DF.");
        } catch (FIPAException fe) {
            System.err.println("❌ " + getLocalName() + " DF registration failed: " + fe.getMessage());
        }
    }

    private class TrustManagementBehaviour extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.or(
                    MessageTemplate.or(
                            MessageTemplate.MatchProtocol("trust-update"),
                            MessageTemplate.MatchProtocol("query-trust-score")),
                    MessageTemplate.MatchProtocol("initial-trust-score"));

            ACLMessage msg = receive(mt);
            if (msg != null) {
                switch (msg.getProtocol()) {
                    case "initial-trust-score":
                        handleInitialTrustScore(msg);
                        break;
                    case "trust-update":
                        handleTrustUpdate(msg);
                        break;
                    case "query-trust-score":
                        handleTrustQuery(msg);
                        break;
                }
            } else {
                block();
            }
        }
    }

    private void handleTrustUpdate(ACLMessage msg) {
        try {
            String content = msg.getContent();
            String agentName = extractValue(content, ":agent-id");
            String outcome = extractValue(content, ":outcome");

            if (agentName == null || outcome == null) {
                return;
            }

            double currentScore = trustScores.getOrDefault(agentName, INITIAL_TRUST_SCORE);

            // Delegate trust calculation to OPA with dynamic parameters
            double newScore = opaClient.evaluateTrustScoreUpdate(currentScore, outcome, 0.10, 0.05);

            trustScores.put(agentName, newScore);

            // NEW: Update score in Keycloak
            boolean success = keycloakClient.updateUserTrustScore(agentName, newScore);
            if (success) {
                System.out.println("✅ Successfully propagated trust score to Keycloak for " + agentName);
            } else {
                System.err.println("❌ Failed to propagate trust score to Keycloak for " + agentName);
            }

            // NEW: Update shared service for UI
            milo.web.SharedTrustScoreService.updateTrustScore(agentName, newScore);

            System.out.println("┌─ TRUST SCORE UPDATE (via OPA) ───────────────────");
            System.out.println("│  ⚖️  AGENT: " + agentName);
            System.out.println("│  OUTCOME: " + outcome);
            System.out.println(String.format("│  SCORE:   %.3f -> %.3f", currentScore, newScore));
            System.out.println("└──────────────────────────────────────────────────");

        } catch (Exception e) {
            System.err.println("❌ " + getLocalName() + " - Error handling trust update: " + e.getMessage());
        }
    }

    private void handleTrustQuery(ACLMessage msg) {
        String agentName = msg.getContent();
        double score = trustScores.getOrDefault(agentName, INITIAL_TRUST_SCORE);

        ACLMessage reply = msg.createReply();
        reply.setPerformative(ACLMessage.INFORM);
        reply.setContent(String.valueOf(score));
        send(reply);
    }

    private void handleInitialTrustScore(ACLMessage msg) {
        try {
            String content = msg.getContent();
            String agentName = extractValue(content, ":agent-id");
            Double score = extractDoubleValue(content, ":score");
            INITIAL_TRUST_SCORE = score;

            if (agentName == null || score == null) {
                System.err
                        .println("❌ " + getLocalName() + " - Could not parse initial trust score message: " + content);
                return;
            }

            trustScores.put(agentName, score);

            // NEW: Update shared service for UI
            milo.web.SharedTrustScoreService.updateTrustScore(agentName, score);

            System.out.println("┌─ INITIAL TRUST SCORE SET ───────────────────────");
            System.out.println("│  AGENT: " + agentName);
            System.out.println(String.format("│  SCORE: %.3f", score));
            System.out.println("└──────────────────────────────────────────────────");

        } catch (Exception e) {
            System.err.println("❌ " + getLocalName() + " - Error handling initial trust score: " + e.getMessage());
        }
    }

    private String extractValue(String content, String key) {
        try {
            int startIndex = content.indexOf(key + " \"");
            if (startIndex != -1) {
                startIndex += key.length() + 2;
                int endIndex = content.indexOf("\"", startIndex);
                if (endIndex != -1) {
                    return content.substring(startIndex, endIndex);
                }
            }
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error extracting value: " + e.getMessage());
        }
        return null;
    }

    private Double extractDoubleValue(String content, String key) {
        try {
            int startIndex = content.indexOf(key + " ");
            if (startIndex != -1) {
                startIndex += key.length() + 1;
                int endIndex = content.indexOf(")", startIndex);
                if (endIndex == -1) {
                    endIndex = content.length(); // Go to end if no closing paren
                }
                String value = content.substring(startIndex, endIndex).trim();
                return Double.parseDouble(value);
            }
        } catch (Exception e) {
            System.err.println(
                    "[" + getLocalName() + "] Error extracting double value for key " + key + ": " + e.getMessage());
        }
        return null;
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
            if (opaClient != null) {
                opaClient.shutdown();
            }
        } catch (FIPAException e) {
            // ignore
        }
    }
}