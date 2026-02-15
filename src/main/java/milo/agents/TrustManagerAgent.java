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
    private static final double TRUST_THRESHOLD = 0.5; // Threshold below which agents are blocked
    private static final double UNBLOCK_THRESHOLD = 0.5; // Threshold above which blocked agents are unblocked

    private Map<String, Double> trustScores = new ConcurrentHashMap<>();
    private Map<String, String> agentStatuses = new ConcurrentHashMap<>(); // Track current status
    private OPAClient opaClient;
    private milo.security.KeycloakClient keycloakClient;
    private milo.eval.MetricsLogService metrics;

    @Override
    protected void setup() {
        System.out.println("✅ " + getLocalName() + " ready (Dynamic Trust Management active)");
        opaClient = new OPAClient();
        keycloakClient = new milo.security.KeycloakClient();
        metrics = milo.eval.MetricsLogService.getInstance();
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
            double newScore;

            // Check if this is a manual update from dashboard (contains :new-score)
            String manualScoreStr = extractValue(content, ":new-score");
            if (manualScoreStr != null && "MANUAL_UPDATE".equals(outcome)) {
                // Manual update from dashboard - use provided score directly
                try {
                    newScore = Double.parseDouble(manualScoreStr.trim());
                    System.out.println("📊 Manual trust score update from dashboard: " + agentName + " = " + newScore);
                } catch (NumberFormatException e) {
                    System.err.println("❌ Invalid manual score format: " + manualScoreStr);
                    return;
                }
            } else {
                // Automatic update from agent behavior - delegate to OPA
                newScore = opaClient.evaluateTrustScoreUpdate(currentScore, outcome, 0.10, 0.05);
            }

            trustScores.put(agentName, newScore);

            // Log trust update for evaluation
            metrics.logTrustUpdate("trust_dynamics_log.csv", agentName, newScore, outcome);

            // NEW: Update score in Keycloak
            boolean success = keycloakClient.updateUserTrustScore(agentName, newScore);
            if (success) {
                System.out.println("✅ Successfully propagated trust score to Keycloak for " + agentName);
            } else {
                System.err.println("❌ Failed to propagate trust score to Keycloak for " + agentName);
            }

            // NEW: Update shared service for UI
            milo.web.SharedTrustScoreService.updateTrustScore(agentName, newScore);

            // NEW: Dynamically update agent status based on trust threshold
            updateAgentStatusBasedOnTrust(agentName, currentScore, newScore);

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

            // Initialize status based on trust score to match threshold logic
            String initialStatus = (score < TRUST_THRESHOLD) ? "blocked" : "active";
            agentStatuses.put(agentName, initialStatus);

            System.out.println("┌─ INITIAL TRUST SCORE SET ───────────────────────");
            System.out.println("│  AGENT: " + agentName);
            System.out.println(String.format("│  SCORE: %.3f", score));
            System.out.println("│  STATUS: " + initialStatus);
            System.out.println("└──────────────────────────────────────────────────");

        } catch (Exception e) {
            System.err.println("❌ " + getLocalName() + " - Error handling initial trust score: " + e.getMessage());
        }
    }

    /**
     * Dynamically update agent status based on trust score thresholds
     * 
     * @param agentName Agent to update
     * @param oldScore  Previous trust score
     * @param newScore  New trust score
     */
    private void updateAgentStatusBasedOnTrust(String agentName, double oldScore, double newScore) {
        try {
            String currentStatus = agentStatuses.getOrDefault(agentName, "active");
            String newStatus = currentStatus;

            // Check if agent should be blocked (trust dropped below threshold)
            if (newScore < TRUST_THRESHOLD && "active".equals(currentStatus)) {
                newStatus = "blocked";
                boolean success = keycloakClient.updateUserStatus(agentName, newStatus);

                if (success) {
                    agentStatuses.put(agentName, newStatus);
                    System.out.println("┌─ AGENT STATUS CHANGED ────────────────────────────");
                    System.out.println("│  🚫 AGENT: " + agentName);
                    System.out.println("│  STATUS: active -> blocked");
                    System.out.println(
                            String.format("│  REASON: Trust score %.3f < threshold %.3f", newScore, TRUST_THRESHOLD));
                    System.out.println("└──────────────────────────────────────────────────");
                } else {
                    System.err.println("❌ Failed to update status for " + agentName + " in Keycloak");
                }
            }
            // Check if blocked agent should be unblocked (trust recovered)
            else if (newScore >= UNBLOCK_THRESHOLD && "blocked".equals(currentStatus)) {
                newStatus = "active";
                boolean success = keycloakClient.updateUserStatus(agentName, newStatus);

                if (success) {
                    agentStatuses.put(agentName, newStatus);
                    System.out.println("┌─ AGENT STATUS CHANGED ────────────────────────────");
                    System.out.println("│  ✅ AGENT: " + agentName);
                    System.out.println("│  STATUS: blocked -> active");
                    System.out.println(String.format("│  REASON: Trust score %.3f >= threshold %.3f", newScore,
                            UNBLOCK_THRESHOLD));
                    System.out.println("└──────────────────────────────────────────────────");
                } else {
                    System.err.println("❌ Failed to update status for " + agentName + " in Keycloak");
                }
            }

        } catch (Exception e) {
            System.err.println("❌ Error updating agent status: " + e.getMessage());
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