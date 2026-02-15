package milo.eval;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for logging performance metrics to CSV files.
 * Used for reducing I/O overhead during experiments.
 */
public class MetricsLogService {

    private static MetricsLogService instance;
    private final Map<String, PrintWriter> writers = new ConcurrentHashMap<>();
    private final String outputDir = "experiment_results";

    public static synchronized MetricsLogService getInstance() {
        if (instance == null) {
            instance = new MetricsLogService();
        }
        return instance;
    }

    private MetricsLogService() {
        try {
            Files.createDirectories(Paths.get(outputDir));
        } catch (IOException e) {
            System.err.println("Failed to create experiment results directory: " + e.getMessage());
        }
    }

    /**
     * Log a latency event.
     * 
     * @param fileName   The CSV file to write to (e.g., "latency_log.csv")
     * @param eventType  type of event (e.g., "OPA_EVAL", "KEYCLOAK_AUTH")
     * @param durationNs duration in nanoseconds
     * @param metadata   additional metadata (e.g., "agent1, success")
     */
    public void logLatency(String fileName, String eventType, long durationNs, String metadata) {
        PrintWriter writer = getWriter(fileName,
                "timestamp_iso,timestamp_ms,event_type,duration_ns,duration_ms,metadata");
        long now = System.currentTimeMillis();
        double durationMs = durationNs / 1_000_000.0;

        synchronized (writer) {
            writer.printf("%s,%d,%s,%d,%.4f,%s%n",
                    Instant.now().toString(),
                    now,
                    eventType,
                    durationNs,
                    durationMs,
                    metadata);
            writer.flush();
        }
    }

    /**
     * Log a trust event.
     * 
     * @param fileName   The CSV file to write to (e.g., "trust_log.csv")
     * @param agentName  Name of the agent
     * @param trustScore New trust score
     * @param reason     Reason for update
     */
    public void logTrustUpdate(String fileName, String agentName, double trustScore, String reason) {
        PrintWriter writer = getWriter(fileName, "timestamp_iso,timestamp_ms,agent_name,trust_score,reason");
        long now = System.currentTimeMillis();

        synchronized (writer) {
            writer.printf("%s,%d,%s,%.4f,%s%n",
                    Instant.now().toString(),
                    now,
                    agentName,
                    trustScore,
                    reason);
            writer.flush();
        }
    }

    private PrintWriter getWriter(String fileName, String header) {
        return writers.computeIfAbsent(fileName, fn -> {
            try {
                boolean newFile = !Files.exists(Paths.get(outputDir, fn));
                FileWriter fw = new FileWriter(Paths.get(outputDir, fn).toString(), true);
                BufferedWriter bw = new BufferedWriter(fw);
                PrintWriter pw = new PrintWriter(bw);

                if (newFile) {
                    pw.println(header);
                    pw.flush();
                }
                return pw;
            } catch (IOException e) {
                System.err.println("Failed to open log file " + fn + ": " + e.getMessage());
                return new PrintWriter(System.out); // Fallback to stdout
            }
        });
    }

    public void close() {
        writers.values().forEach(PrintWriter::close);
        writers.clear();
    }
}
