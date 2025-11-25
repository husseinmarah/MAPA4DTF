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
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.FileReader;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the trust scores of all agents in the federation.
 * Scores are updated based on task outcomes by querying an OPA policy with dynamic parameters.
 * Provides a service for querying agent trust scores.
 */
public class TrustManagerAgent extends Agent {

    private static final double INITIAL_TRUST_SCORE = 0.7;
    // Default values, to be overridden by config file
    private double decayFactor = 0.95;
    private double learningRate = 0.05;

    private Map<String, Double> trustScores = new ConcurrentHashMap<>();
    private OPAClient opaClient;

    @Override
    protected void setup() {
        System.out.println("✅ " + getLocalName() + " ready (Dynamic Trust Management active)");
        loadTrustConfig();
        opaClient = new OPAClient();
        registerWithDF();

        addBehaviour(new TrustManagementBehaviour());
    }

    private void loadTrustConfig() {
        JSONParser parser = new JSONParser();
        try (FileReader reader = new FileReader("configs/trust_config.json")) {
            JSONObject config = (JSONObject) parser.parse(reader);
            this.decayFactor = (Double) config.get("decay_factor");
            this.learningRate = (Double) config.get("learning_rate");
            System.out.println("✅ Trust parameters loaded from config: decay=" + decayFactor + ", learning=" + learningRate);
        } catch (IOException | ParseException e) {
            System.err.println("⚠️ Could not load 'configs/trust_config.json'. Using default trust parameters. Error: " + e.getMessage());
        }
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
            MessageTemplate mt =
                    MessageTemplate.or(
                            MessageTemplate.or(
                                    MessageTemplate.MatchProtocol("trust-update"),
                                    MessageTemplate.MatchProtocol("query-trust-score")
                            ),
                            MessageTemplate.MatchProtocol("initial-trust-score")
                    );

            ACLMessage msg = receive(mt);
            if (msg != null) {
                System.out.println("<UNK> " + getLocalName() + " received a message: " + msg);

                switch (msg.getProtocol()) {
                    case "trust-update":
                        handleTrustUpdate(msg);
                        break;
                    case "initial-trust-score":
                        handleInitialTrustScore(msg);
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
            double newScore = opaClient.evaluateTrustScoreUpdate(currentScore, outcome, this.decayFactor, this.learningRate);

            trustScores.put(agentName, newScore);

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

            if (agentName == null || score == null) {
                System.err.println("❌ " + getLocalName() + " - Could not parse initial trust score message: " + content);
                return;
            }

            trustScores.put(agentName, score);

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
            System.err.println("[" + getLocalName() + "] Error extracting double value for key " + key + ": " + e.getMessage());
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