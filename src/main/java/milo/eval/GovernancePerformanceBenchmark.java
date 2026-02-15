package milo.eval;

import milo.security.KeycloakClient;
import milo.security.OPAClient;
import milo.security.OPAClient.PolicyDecision;
import milo.federation.FederationHelper;

/**
 * Standalone benchmark for measuring the performance of the Governance Layer.
 * Tests Keycloak authentication and OPA policy evaluation latency.
 */
public class GovernancePerformanceBenchmark {

    private static final int WARMUP_ITERATIONS = 50;
    private static final int MEASURE_ITERATIONS = 1000;

    private KeycloakClient keycloak;
    private OPAClient opa;
    private MetricsLogService metrics;

    public static void main(String[] args) {
        GovernancePerformanceBenchmark benchmark = new GovernancePerformanceBenchmark();
        benchmark.setup();
        benchmark.runBenchmarks();
        benchmark.teardown();
    }

    public void setup() {
        System.out.println("Initializing Governance Components...");
        keycloak = KeycloakClient.getInstance();
        opa = OPAClient.getInstance();
        metrics = MetricsLogService.getInstance();

        // Ensure services are available
        if (!keycloak.isAvailable()) {
            System.err.println("WARNING: Keycloak is not reachable. Authentication tests may fail.");
        }
        if (!opa.isAvailable()) {
            System.err.println("WARNING: OPA is not reachable. Policy tests may fail.");
        }

        // Pre-populate FederationHelper cache with FFAs (needed because agents aren't
        // running)
        System.out.println("Pre-populating FederationHelper cache...");
        FederationHelper.updateFFACache("R1", "EU.Manufacturing.Warehouse.A.Stakeholder1.Robot#R1::Transport@High");
        FederationHelper.updateFFACache("C1", "EU.Manufacturing.Warehouse.B.Stakeholder3.Conveyor#C1::Transport@High");
        FederationHelper.updateFFACache("R_Bad",
                "EU.Manufacturing.Warehouse.A.Stakeholder1.Robot#R_Bad::Transport@High");
        FederationHelper.updateFFACache("RobotAgent1",
                "EU.Manufacturing.Warehouse.A.Stakeholder1.Robot#R1::Transport@High");
        FederationHelper.updateFFACache("ConveyorAgent1",
                "EU.Manufacturing.Warehouse.B.Stakeholder3.Conveyor#C1::Transport@High");
    }

    public void runBenchmarks() {
        System.out.println("Starting Benchmarks...");

        try {
            // Test 1: Keycloak Authentication
            testKeycloakAuthentication();

            // Test 2: OPA Policy Evaluation (Active Worker)
            testOPAPolicyEvaluation_ActiveWorker();

            // Test 3: OPA Policy Evaluation (Blocked Agent)
            testOPAPolicyEvaluation_BlockedAgent();

            // Test 4: Trust Dynamics Simulation
            testTrustDynamics();

            // Test 5: End-to-End Governance Check
            testEndToEndGovernance();

        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println("Benchmarks Completed. Results logged to experiment_results directory.");
    }

    private void testKeycloakAuthentication() {
        System.out.println("Running Keycloak Authentication Benchmark...");
        String username = "RobotAgent1"; // Matches keycloak-realm.json
        String password = "robot"; // Matches keycloak-realm.json

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            keycloak.authenticate(username, password);
        }

        // Measurement
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            long start = System.nanoTime();
            KeycloakClient.AuthToken token = keycloak.authenticate(username, password);
            long duration = System.nanoTime() - start;

            boolean success = (token != null);
            metrics.logLatency("keycloak_auth_benchmark.csv", "KEYCLOAK_AUTH", duration,
                    "success=" + success);
        }
    }

    private void testOPAPolicyEvaluation_ActiveWorker() {
        System.out.println("Running OPA Benchmark (Active Worker)...");

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            opa.evaluateCommunicationPolicy("R1", "S1", "worker", 1.0, "active", "C1", "S3", "worker", 1.0, "active",
                    "send");
        }

        // Measurement
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            long start = System.nanoTime();
            PolicyDecision decision = opa.evaluateCommunicationPolicy(
                    "R1", "Stakeholder1_RobotContainer", "worker", 1.0, "active",
                    "C1", "Stakeholder3_ConveyorContainer", "worker", 1.0, "active",
                    "send");
            long duration = System.nanoTime() - start;

            metrics.logLatency("opa_eval_benchmark.csv", "OPA_EVAL_ACTIVE", duration,
                    "allowed=" + decision.allowed);
        }
    }

    private void testOPAPolicyEvaluation_BlockedAgent() {
        System.out.println("Running OPA Benchmark (Blocked Agent)...");

        // Blocked agent attributes
        double lowTrust = 0.3;
        String status = "blocked";

        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            long start = System.nanoTime();
            PolicyDecision decision = opa.evaluateCommunicationPolicy(
                    "R_Bad", "Stakeholder1_RobotContainer", "worker", lowTrust, status,
                    "C1", "Stakeholder3_ConveyorContainer", "worker", 1.0, "active",
                    "send");
            long duration = System.nanoTime() - start;

            metrics.logLatency("opa_eval_benchmark.csv", "OPA_EVAL_BLOCKED", duration,
                    "allowed=" + decision.allowed);
        }
    }

    private void testTrustDynamics() {
        System.out.println("Running Trust Dynamics Simulation...");
        String agentName = "Robot_Learner";
        double trustScore = 0.5; // Initial Neutral Score
        double decayFactor = 0.95;
        double learningRate = 0.1;

        // Log initial state
        metrics.logTrustUpdate("trust_dynamics_log.csv", agentName, trustScore, "Initial");

        // Phase 1: 20 Successes (Growth)
        for (int i = 0; i < 20; i++) {
            trustScore = opa.evaluateTrustScoreUpdate(trustScore, "SUCCESS", decayFactor, learningRate);
            metrics.logTrustUpdate("trust_dynamics_log.csv", agentName, trustScore, "Success_Interaction");
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
            } // Small delay for timestamp diff
        }

        // Phase 2: 15 Failures (Decay)
        for (int i = 0; i < 15; i++) {
            trustScore = opa.evaluateTrustScoreUpdate(trustScore, "FAILURE", decayFactor, learningRate);
            metrics.logTrustUpdate("trust_dynamics_log.csv", agentName, trustScore, "Failure_Interaction");
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
            }
        }

        // Phase 3: 10 Successes (Recovery)
        for (int i = 0; i < 10; i++) {
            trustScore = opa.evaluateTrustScoreUpdate(trustScore, "SUCCESS", decayFactor, learningRate);
            metrics.logTrustUpdate("trust_dynamics_log.csv", agentName, trustScore, "Recovery_Interaction");
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
            }
        }
    }

    private void testEndToEndGovernance() {
        System.out.println("Running End-to-End Governance Benchmark...");
        // Simulates the flow: Auth -> Extract Attributes -> Check Policy
        String username = "RobotAgent1";
        String password = "robot";

        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            long start = System.nanoTime();

            // 1. Authenticate & Get Attributes
            KeycloakClient.AuthToken token = keycloak.authenticate(username, password);

            if (token != null) {
                // 2. Evaluate Policy using attributes
                // Simulating send to Conveyor (valid flow)
                PolicyDecision decision = opa.evaluateCommunicationPolicy(
                        token.userAttributes.username,
                        token.userAttributes.org,
                        token.userAttributes.role,
                        token.userAttributes.trustScore,
                        token.userAttributes.status,
                        "ConveyorAgent1", "Stakeholder3_ConveyorContainer", "worker", 1.0, "active",
                        "send");

                long duration = System.nanoTime() - start;
                metrics.logLatency("e2e_governance_benchmark.csv", "E2E_GOVERNANCE", duration,
                        "success=" + (decision != null && decision.allowed));
            } else {
                long duration = System.nanoTime() - start;
                metrics.logLatency("e2e_governance_benchmark.csv", "E2E_GOVERNANCE", duration,
                        "success=false_auth_failed");
            }
        }
    }

    public void teardown() {
        keycloak.shutdown();
        opa.shutdown();
        metrics.close();
    }
}
