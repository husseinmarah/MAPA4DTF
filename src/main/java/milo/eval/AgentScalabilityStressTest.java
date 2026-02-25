package milo.eval;

import jade.core.AID;
import jade.core.Agent;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import milo.federation.FederationHelper;
import milo.security.FederationSecurityManager;
import milo.security.KeycloakClient;
import milo.security.OPAClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * True MAS Stress test for measuring the scalability and throughput of the
 * Governance Layer
 * using actual JADE agents exchanging FIPA-ACL Call-For-Proposals (CFP).
 */
public class AgentScalabilityStressTest {

    private KeycloakClient keycloak;
    private OPAClient opa;
    private MetricsLogService metrics;
    private AgentContainer mainContainer;
    public static volatile int currentConcurrency = 0;

    public static void main(String[] args) {
        AgentScalabilityStressTest test = new AgentScalabilityStressTest();
        test.setup();
        test.runStressTests();
        test.teardown();
    }

    public void setup() {
        System.out.println("Initializing Agent-based Scalability Stress Test...");
        keycloak = KeycloakClient.getInstance();
        opa = OPAClient.getInstance();
        metrics = MetricsLogService.getInstance();

        if (!keycloak.isAvailable() || !opa.isAvailable()) {
            System.err.println("WARNING: Keycloak or OPA not reachable. Tests may fail.");
        }

        System.out.println("Starting JADE Runtime...");
        Runtime runtime = Runtime.instance();
        Profile profile = new ProfileImpl(new jade.util.ExtendedProperties());
        profile.setParameter(Profile.GUI, "false");
        mainContainer = runtime.createMainContainer(profile);

        try {
            // Start the receiver agent
            AgentController responder = mainContainer.createNewAgent("ResponderAgent", ResponderAgent.class.getName(),
                    new Object[] {});
            responder.start();

            // Wait for JADE and agents to initialize
            Thread.sleep(2000);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void runStressTests() {
        System.out.println("Starting True Agent Scalability Stress Tests...");

        int[] concurrencyLevels = { 10, 50, 100, 200, 500 };
        int requestsPerAgent = 10;

        List<AgentController> currentSenders = new ArrayList<>();

        for (int numAgents : concurrencyLevels) {
            currentConcurrency = numAgents;
            try {
                System.out.println("\n--- Running MAS Concurrency Test with " + numAgents + " FIPA-ACL agents ---");

                // Spawn necessary agents up to numAgents
                while (currentSenders.size() < numAgents) {
                    int id = currentSenders.size() + 1;
                    String agentName = "SenderAgent" + id;

                    // Pre-populate Federation Helper Cache for OPA context bypassing discovery
                    // latency
                    FederationHelper.updateFFACache(agentName,
                            "EU.Manufacturing.Warehouse.A.Stakeholder1.Robot#" + agentName + "::Transport@High");
                    FederationHelper.updateFFACache("ResponderAgent",
                            "EU.Manufacturing.Warehouse.B.Stakeholder3.Conveyor#ResponderAgent::Transport@High");

                    AgentController sender = mainContainer.createNewAgent(agentName, SenderAgent.class.getName(),
                            new Object[] { "ResponderAgent", requestsPerAgent });
                    sender.start();
                    currentSenders.add(sender);
                }

                Thread.sleep(2000); // Wait for new agents to fully start

                CountDownLatch latch = new CountDownLatch(numAgents);
                SenderAgent.setLatch(latch);

                long startTime = System.currentTimeMillis();

                // Trigger all agents using the static shared flag
                SenderAgent.startBidding();

                latch.await();

                long endTime = System.currentTimeMillis();
                long totalDurationMs = endTime - startTime;
                int totalRequests = numAgents * requestsPerAgent;
                double throughput = (totalRequests * 1000.0) / totalDurationMs;

                System.out.printf("MAS Test finished. Agents: %d, Total Time: %d ms, Throughput: %.2f req/sec\n",
                        numAgents, totalDurationMs, throughput);

                // Stop the test and move to next iteration
                SenderAgent.resetBidding();

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        System.out.println("\nAgent Stress Tests Completed. Results logged to agent_stress_benchmark.csv.");
    }

    public void teardown() {
        try {
            if (mainContainer != null) {
                mainContainer.kill();
            }
        } catch (Exception e) {
        }
        keycloak.shutdown();
        opa.shutdown();
        metrics.close();
    }

    // =========================================================================
    // INNER CLASSES: JADE AGENTS FOR STRESS TESTING
    // =========================================================================

    public static class ResponderAgent extends Agent {
        private FederationSecurityManager securityManager;

        @Override
        protected void setup() {
            securityManager = FederationSecurityManager.getInstance();
            securityManager.registerSecureAgent(getLocalName(), "Stakeholder3", "StressTestContainer");
            System.out.println(getLocalName() + " ready to receive CFPs.");

            addBehaviour(new CyclicBehaviour(this) {
                @Override
                public void action() {
                    ACLMessage msg = receive(MessageTemplate.MatchPerformative(ACLMessage.CFP));
                    if (msg != null) {
                        long reqStart = System.nanoTime();

                        // Enforce PIP-PDP-PEP Governance Pipeline on received message
                        boolean allowed = securityManager.validateMessageWithOPA(msg, msg.getSender().getLocalName(),
                                getLocalName());

                        long duration = System.nanoTime() - reqStart;
                        MetricsLogService.getInstance().logLatency("agent_stress_benchmark.csv",
                                "MAS_OPA_STRESS_" + AgentScalabilityStressTest.currentConcurrency, duration,
                                "allowed=" + allowed);

                        // Send Reply
                        ACLMessage reply = msg.createReply();
                        if (allowed) {
                            reply.setPerformative(ACLMessage.PROPOSE);
                            reply.setContent("150.0"); // Dummy bidding score
                        } else {
                            reply.setPerformative(ACLMessage.REFUSE);
                            reply.setContent("Policy Denied");
                        }
                        send(reply);
                    } else {
                        block();
                    }
                }
            });
        }
    }

    public static class SenderAgent extends Agent {
        private static CountDownLatch globalLatch;
        private static volatile boolean active = false;

        private String targetAgent;
        private int requestsToSend;
        private int sentCount = 0;
        private int rcvdCount = 0;

        public static void setLatch(CountDownLatch latch) {
            globalLatch = latch;
        }

        public static void startBidding() {
            active = true;
        }

        public static void resetBidding() {
            active = false;
        }

        @Override
        protected void setup() {
            FederationSecurityManager.getInstance().registerSecureAgent(getLocalName(), "Stakeholder1",
                    "StressTestContainer");
            Object[] args = getArguments();
            targetAgent = (String) args[0];
            requestsToSend = (Integer) args[1];

            addBehaviour(new CyclicBehaviour(this) {
                @Override
                public void action() {
                    if (active && sentCount < requestsToSend) {
                        // Blast CFPs
                        for (int i = 0; i < requestsToSend; i++) {
                            ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
                            cfp.addReceiver(new AID(targetAgent, AID.ISLOCALNAME));
                            cfp.setContent("Task_Params");
                            send(cfp);
                            sentCount++;
                        }
                    } else if (active && rcvdCount < requestsToSend) {
                        // Wait for replies
                        MessageTemplate mt = MessageTemplate.or(
                                MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                                MessageTemplate.MatchPerformative(ACLMessage.REFUSE));
                        ACLMessage reply = receive(mt);
                        if (reply != null) {
                            rcvdCount++;
                            if (rcvdCount == requestsToSend) {
                                // Done for this cycle
                                if (globalLatch != null) {
                                    globalLatch.countDown();
                                }
                            }
                        } else {
                            block(50);
                        }
                    } else if (!active && sentCount > 0) {
                        // Reset for next test cycle
                        sentCount = 0;
                        rcvdCount = 0;
                    } else {
                        block(50);
                    }
                }
            });
        }
    }
}
