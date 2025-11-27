package milo.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service to share trust scores between JADE agents (TrustManagerAgent) and
 * Spring components (RobotWebSocketHandler).
 * This acts as a bridge since they run in the same JVM but different contexts.
 */
public class SharedTrustScoreService {

    private static final Map<String, Double> trustScores = new ConcurrentHashMap<>();

    public static void updateTrustScore(String agentName, double score) {
        trustScores.put(agentName, score);
    }

    public static Map<String, Double> getTrustScores() {
        return new ConcurrentHashMap<>(trustScores);
    }
}
