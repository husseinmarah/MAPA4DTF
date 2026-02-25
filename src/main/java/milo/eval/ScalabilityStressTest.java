package milo.eval;

import milo.security.KeycloakClient;
import milo.security.OPAClient;
import milo.security.OPAClient.PolicyDecision;
import milo.federation.FederationHelper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stress test for measuring the scalability and throughput of the Governance
 * Layer.
 * Evaluates performance with an increasing number of concurrent
 * agents/requests.
 */
public class ScalabilityStressTest {

    private KeycloakClient keycloak;
    private OPAClient opa;
    private MetricsLogService metrics;

    public static void main(String[] args) {
        ScalabilityStressTest test = new ScalabilityStressTest();
        test.setup();
        test.runStressTests();
        test.teardown();
    }

    public void setup() {
        System.out.println("Initializing Scalability Stress Test...");
        keycloak = KeycloakClient.getInstance();
        opa = OPAClient.getInstance();
        metrics = MetricsLogService.getInstance();

        if (!keycloak.isAvailable() || !opa.isAvailable()) {
            System.err.println("WARNING: Keycloak or OPA not reachable. Tests may fail.");
        }

        System.out.println("Pre-populating FederationHelper cache...");
        FederationHelper.updateFFACache("RobotAgent1",
                "EU.Manufacturing.Warehouse.A.Stakeholder1.Robot#R1::Transport@High");
        FederationHelper.updateFFACache("ConveyorAgent1",
                "EU.Manufacturing.Warehouse.B.Stakeholder3.Conveyor#C1::Transport@High");
    }

    public void runStressTests() {
        System.out.println("Starting Scalability Stress Tests...");

        int[] concurrencyLevels = { 10, 50, 100, 200, 500 };
        int requestsPerThread = 10;

        for (int threads : concurrencyLevels) {
            runConcurrentOPATest(threads, requestsPerThread);
        }

        System.out.println("Stress Tests Completed. Results logged.");
    }

    private void runConcurrentOPATest(int numThreads, int requestsPerThread) {
        System.out.println("Running OPA Concurrency Test with " + numThreads + " threads...");

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < requestsPerThread; j++) {
                        long reqStart = System.nanoTime();
                        PolicyDecision decision = opa.evaluateCommunicationPolicy(
                                "R1", "Stakeholder1_RobotContainer", "worker", 1.0, "active",
                                "C1", "Stakeholder3_ConveyorContainer", "worker", 1.0, "active",
                                "send");
                        long duration = System.nanoTime() - reqStart;

                        metrics.logLatency("opa_stress_benchmark.csv", "OPA_STRESS_" + numThreads, duration,
                                "allowed=" + decision.allowed);

                        if (decision.allowed) {
                            successCount.incrementAndGet();
                        } else {
                            failCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        long endTime = System.currentTimeMillis();
        long totalDurationMs = endTime - startTime;
        int totalRequests = numThreads * requestsPerThread;
        double throughput = (totalRequests * 1000.0) / totalDurationMs;

        System.out.printf(
                "Test finished. Threads: %d, Total Time: %d ms, Throughput: %.2f req/sec, Success: %d, Fail: %d\n",
                numThreads, totalDurationMs, throughput, successCount.get(), failCount.get());

        executor.shutdown();
    }

    public void teardown() {
        keycloak.shutdown();
        opa.shutdown();
        metrics.close();
    }
}
