package milo.federation;

import jade.core.AID;
import jade.lang.acl.ACLMessage;
import milo.utils.ACLUtil;
import milo.utils.SL;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Federation Address Protocol (FAP) Implementation (a.k.a. Federation Context Protocol - FCP-Protocol)
 *
 * Provides core FAP (a.k.a. FCP) functionality separated from agent implementation:
 * - FAP-ALLOC: Address allocation with unique instance numbering
 * - FAP-RESOLVE: Address resolution from FFA to AID
 * - FAP-UPDATE: Lease renewal and maintenance
 * - Lease management with TTL expiry
 * - Registry management with concurrent access
 *
 * Integration capabilities for FDS, ACL messaging, and health monitoring
 */
public class FAPProtocol {

    /**
     * Record structure for FAP registry entries
     */
    public static class FAPRecord {
        public AID aid;
        public FFA ffa;
        public long leaseExpiry;
        public long allocationTime;

        public FAPRecord(AID aid, FFA ffa, long leaseExpiry) {
            this.aid = aid;
            this.ffa = ffa;
            this.leaseExpiry = leaseExpiry;
            this.allocationTime = System.currentTimeMillis();
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > leaseExpiry;
        }

        public long getRemainingLeaseTime() {
            return Math.max(0, leaseExpiry - System.currentTimeMillis());
        }
    }

    /**
     * FAP operation result types
     */
    public enum FAPResult {
        SUCCESS, FAILURE, NOT_FOUND, EXPIRED, INVALID_REQUEST
    }

    /**
     * FAP response structure
     */
    public static class FAPResponse {
        public FAPResult result;
        public String content;
        public FAPRecord record;

        public FAPResponse(FAPResult result, String content) {
            this.result = result;
            this.content = content;
        }

        public FAPResponse(FAPResult result, String content, FAPRecord record) {
            this.result = result;
            this.content = content;
            this.record = record;
        }

        // Convenience methods for accessing data
        public String getFfa() {
            return record != null ? record.ffa.toString() : null;
        }

        public AID getAid() {
            return record != null ? record.aid : null;
        }

        public String getMessage() {
            return content;
        }
    }

    /**
     * Event listener interface for FAP operations
     */
    public interface FAPEventListener {
        void onAddressAllocated(FAPRecord record);

        void onAddressResolved(FAPRecord record);

        void onLeaseExpired(FAPRecord record);

        void onLeaseUpdated(FAPRecord record);
    }

    // Registry storage
    private final Map<String, FAPRecord> byAid = new ConcurrentHashMap<>();
    private final Map<String, FAPRecord> byFFA = new ConcurrentHashMap<>();
    private final Map<String, Integer> instanceCounters = new ConcurrentHashMap<>();

    // Configuration
    private static final long DEFAULT_TTL_MS = 5 * 60 * 1000; // 5 minutes
    private final long ttlMillis;

    // Statistics
    private long totalAllocations = 0;
    private long totalResolutions = 0;
    private long totalUpdates = 0;
    private long expiredLeases = 0;

    // Integration components
    private FederatedDirectoryService fdsIntegration;
    private FederationHealthMonitor healthMonitor;
    private List<FAPEventListener> eventListeners = new ArrayList<>();
    private milo.eval.MetricsLogService metrics;

    /**
     * Constructor with default TTL
     */
    public FAPProtocol() {
        this(DEFAULT_TTL_MS);
        this.metrics = milo.eval.MetricsLogService.getInstance();
    }

    /**
     * Constructor with custom TTL
     */
    public FAPProtocol(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    /**
     * Set FDS integration
     */
    public void setFDSIntegration(FederatedDirectoryService fds) {
        this.fdsIntegration = fds;
    }

    /**
     * Set health monitor integration
     */
    public void setHealthMonitor(FederationHealthMonitor monitor) {
        this.healthMonitor = monitor;
    }

    /**
     * Add event listener
     */
    public void addEventListener(FAPEventListener listener) {
        eventListeners.add(listener);
    }

    /**
     * FAP-ALLOC: Allocate a new Federation Fractal Address
     */
    public FAPResponse allocateAddress(AID requesterAid, String geo, String domain,
            String level, String system, String component,
            String capability, String qos) {
        try {
            // Validate input parameters
            if (requesterAid == null || geo == null || domain == null || capability == null) {
                return new FAPResponse(FAPResult.INVALID_REQUEST,
                        "(Failure :reason \"missing-required-parameters\")");
            }

            // Generate unique instance number
            // Try to extract agent number from agent name (e.g., RobotAgent3 -> 3)
            String agentName = requesterAid.getLocalName();
            int instance = extractAgentNumber(agentName);

            // If no number found in agent name, use global counter
            if (instance == -1) {
                String counterKey = (system == null ? "SYS" : system) +
                        (component == null ? "" : ("." + component));
                instance = instanceCounters.merge(counterKey, 1, Integer::sum);
            }

            // Build FFA string
            StringBuilder ffaBuilder = new StringBuilder();
            ffaBuilder.append(geo).append('.').append(domain).append('.').append(level);

            if (system != null && !system.isEmpty()) {
                ffaBuilder.append('.').append(system);
            } else {
                ffaBuilder.append(".SYS");
            }

            if (component != null && !component.isEmpty()) {
                ffaBuilder.append('.').append(component);
            }

            ffaBuilder.append('#').append(instance)
                    .append("::").append(capability)
                    .append('@').append(qos);

            String ffaString = ffaBuilder.toString();
            FFA ffa = FFA.fromString(ffaString);

            // Create record with lease
            long expiry = System.currentTimeMillis() + ttlMillis;
            FAPRecord record = new FAPRecord(requesterAid, ffa, expiry);

            // Store in registries
            String aidKey = requesterAid.getName();

            // Remove any existing allocation for this agent
            FAPRecord existingRecord = byAid.get(aidKey);
            if (existingRecord != null) {
                byFFA.remove(existingRecord.ffa.key());
            }

            byAid.put(aidKey, record);
            byFFA.put(ffa.key(), record);

            totalAllocations++;

            // Notify listeners and integrations
            notifyAddressAllocated(record);

            // Report to health monitor (FIXED)
            if (healthMonitor != null) {
                healthMonitor.reportFAPAllocation(record);
            }

            return new FAPResponse(FAPResult.SUCCESS,
                    "(Assigned-FFA :ffa \"" + ffa + "\" :lease-expiry " + expiry + ")", record);

        } catch (Exception e) {
            return new FAPResponse(FAPResult.FAILURE,
                    "(Failure :reason \"allocation-error\" :details \"" + e.getMessage() + "\")");
        }
    }

    /**
     * FAP-RESOLVE: Resolve FFA string to agent AID
     */
    public FAPResponse resolveAddress(String ffaString) {
        long start = System.nanoTime();
        try {
            if (ffaString == null || ffaString.trim().isEmpty()) {
                return new FAPResponse(FAPResult.INVALID_REQUEST,
                        "(Failure :reason \"empty-ffa-string\")");
            }

            FFA ffa = FFA.fromString(ffaString);
            FAPRecord record = byFFA.get(ffa.key());

            totalResolutions++;

            if (record == null) {
                return new FAPResponse(FAPResult.NOT_FOUND,
                        "(Not-Found :ffa \"" + ffaString + "\")");
            }

            if (record.isExpired()) {
                // Clean up expired record
                removeExpiredRecord(record);
                return new FAPResponse(FAPResult.EXPIRED,
                        "(Expired :ffa \"" + ffaString + "\")");
            }

            // Notify listeners and integrations
            notifyAddressResolved(record);

            // Report to health monitor
            if (healthMonitor != null) {
                healthMonitor.reportFAPResolution(record);
            }

            FAPResponse response = new FAPResponse(FAPResult.SUCCESS,
                    "(Resolved :ffa \"" + ffaString + "\" :aid \"" + record.aid.getName() + "\")", record);

            long duration = System.nanoTime() - start;
            if (metrics != null)
                metrics.logLatency("discovery_latency_log.csv", "FFA_RESOLVE", duration, "ffa=" + ffaString);

            return response;

        } catch (Exception e) {
            return new FAPResponse(FAPResult.FAILURE,
                    "(Failure :reason \"resolution-error\" :details \"" + e.getMessage() + "\")");
        }
    }

    /**
     * FAP-UPDATE: Update lease for an agent
     */
    public FAPResponse updateLease(AID requesterAid) {
        try {
            if (requesterAid == null) {
                return new FAPResponse(FAPResult.INVALID_REQUEST,
                        "(Failure :reason \"missing-aid\")");
            }

            String aidKey = requesterAid.getName();
            FAPRecord record = byAid.get(aidKey);

            totalUpdates++;

            if (record == null) {
                return new FAPResponse(FAPResult.NOT_FOUND,
                        "(Not-Found :aid \"" + aidKey + "\")");
            }

            // Update lease expiry
            record.leaseExpiry = System.currentTimeMillis() + ttlMillis;

            // Notify listeners
            notifyLeaseUpdated(record);

            return new FAPResponse(FAPResult.SUCCESS,
                    "(Updated :ffa \"" + record.ffa + "\" :new-expiry " + record.leaseExpiry + ")", record);

        } catch (Exception e) {
            return new FAPResponse(FAPResult.FAILURE,
                    "(Failure :reason \"update-error\" :details \"" + e.getMessage() + "\")");
        }
    }

    /**
     * Pattern matching for complex FFA patterns
     */
    public List<FAPRecord> searchByFFAPattern(String pattern) {
        List<FAPRecord> matches = new ArrayList<>();

        if (pattern == null || pattern.isEmpty()) {
            return matches;
        }

        // Handle wildcard patterns
        if ("*".equals(pattern)) {
            return byFFA.values().stream()
                    .filter(record -> !record.isExpired())
                    .collect(Collectors.toList());
        }

        // Convert pattern to regex
        String regexPattern = convertPatternToRegex(pattern);

        for (FAPRecord record : byFFA.values()) {
            if (!record.isExpired() && record.ffa.toString().matches(regexPattern)) {
                matches.add(record);
            }
        }

        return matches;
    }

    /**
     * Convert FFA pattern to regex
     */
    private String convertPatternToRegex(String pattern) {
        return pattern.replace(".", "\\.")
                .replace("*", ".*")
                .replace("#", "\\#")
                .replace("::", "\\:\\:")
                .replace("@", "\\@");
    }

    /**
     * Process ACL message for FAP operations
     */
    public FAPResponse processACLMessage(ACLMessage message) {
        if (message == null || message.getContent() == null) {
            return new FAPResponse(FAPResult.INVALID_REQUEST,
                    "(Failure :reason \"empty-message\")");
        }

        try {
            String content = message.getContent();

            // Parse FAP operation from content
            if (content.contains("fap-alloc")) {
                return handleAllocRequest(message);
            } else if (content.contains("fap-resolve")) {
                return handleResolveRequest(message);
            } else if (content.contains("fap-update")) {
                return handleUpdateRequest(message);
            } else {
                return new FAPResponse(FAPResult.INVALID_REQUEST,
                        "(Failure :reason \"unknown-operation\")");
            }

        } catch (Exception e) {
            return new FAPResponse(FAPResult.FAILURE,
                    "(Failure :reason \"message-processing-error\" :details \"" + e.getMessage() + "\")");
        }
    }

    /**
     * Handle allocation request from ACL message
     */
    private FAPResponse handleAllocRequest(ACLMessage message) {
        try {
            // Parse parameters from SL content
            Map<String, String> params = SL.parseParameters(message.getContent());

            return allocateAddress(
                    message.getSender(),
                    params.get("geo"),
                    params.get("domain"),
                    params.get("level"),
                    params.get("system"),
                    params.get("component"),
                    params.get("capability"),
                    params.get("qos"));

        } catch (Exception e) {
            return new FAPResponse(FAPResult.INVALID_REQUEST,
                    "(Failure :reason \"invalid-alloc-parameters\")");
        }
    }

    /**
     * Handle resolve request from ACL message
     */
    private FAPResponse handleResolveRequest(ACLMessage message) {
        try {
            Map<String, String> params = SL.parseParameters(message.getContent());
            String ffaString = params.get("ffa");

            return resolveAddress(ffaString);

        } catch (Exception e) {
            return new FAPResponse(FAPResult.INVALID_REQUEST,
                    "(Failure :reason \"invalid-resolve-parameters\")");
        }
    }

    /**
     * Handle update request from ACL message
     */
    private FAPResponse handleUpdateRequest(ACLMessage message) {
        return updateLease(message.getSender());
    }

    /**
     * Get matching FFAs by pattern (wildcard support)
     */
    public List<String> getMatchingFFAs(String pattern) {
        return searchByFFAPattern(pattern).stream()
                .map(record -> record.ffa.toString())
                .collect(Collectors.toList());
    }

    /**
     * Get all active allocations (non-expired)
     */
    public List<FAPRecord> getActiveAllocations() {
        return byFFA.values().stream()
                .filter(record -> !record.isExpired())
                .collect(Collectors.toList());
    }

    /**
     * Cleanup expired leases
     */
    public int performCleanup() {
        List<FAPRecord> expiredRecords = new ArrayList<>();

        // Find expired records
        for (FAPRecord record : byFFA.values()) {
            if (record.isExpired()) {
                expiredRecords.add(record);
            }
        }

        // Remove expired records
        for (FAPRecord record : expiredRecords) {
            removeExpiredRecord(record);
        }

        expiredLeases += expiredRecords.size();

        return expiredRecords.size();
    }

    /**
     * Enhanced cleanup with event notification
     */
    private void removeExpiredRecord(FAPRecord record) {
        byAid.remove(record.aid.getName());
        byFFA.remove(record.ffa.key());
        notifyLeaseExpired(record);
    }

    /**
     * Get health status for monitoring
     */
    public Map<String, Object> getHealthStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("total-allocations", totalAllocations);
        status.put("total-resolutions", totalResolutions);
        status.put("total-updates", totalUpdates);
        status.put("expired-leases", expiredLeases);
        status.put("active-records", getActiveAllocations().size());
        status.put("registry-size-aid", byAid.size());
        status.put("registry-size-ffa", byFFA.size());
        status.put("ttl-milliseconds", ttlMillis);

        return status;
    }

    /**
     * Perform health check and cleanup
     */
    public int performHealthCheck() {
        int cleanedUp = performCleanup();

        // Report to health monitor if available
        if (healthMonitor != null) {
            healthMonitor.reportFAPHealthCheck(getHealthStatus());
        }

        return cleanedUp;
    }

    /**
     * Get statistics
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalAllocations", totalAllocations);
        stats.put("totalResolutions", totalResolutions);
        stats.put("totalUpdates", totalUpdates);
        stats.put("expiredLeases", expiredLeases);
        stats.put("activeRecords", getActiveAllocations().size());
        stats.put("registrySize", byFFA.size());
        stats.put("ttlMillis", ttlMillis);

        return stats;
    }

    // Event notification methods
    private void notifyAddressAllocated(FAPRecord record) {
        for (FAPEventListener listener : eventListeners) {
            try {
                listener.onAddressAllocated(record);
            } catch (Exception e) {
                System.err.println("Error notifying FAP event listener: " + e.getMessage());
            }
        }
    }

    private void notifyAddressResolved(FAPRecord record) {
        for (FAPEventListener listener : eventListeners) {
            try {
                listener.onAddressResolved(record);
            } catch (Exception e) {
                System.err.println("Error notifying FAP event listener: " + e.getMessage());
            }
        }
    }

    private void notifyLeaseExpired(FAPRecord record) {
        for (FAPEventListener listener : eventListeners) {
            try {
                listener.onLeaseExpired(record);
            } catch (Exception e) {
                System.err.println("Error notifying FAP event listener: " + e.getMessage());
            }
        }
    }

    /**
     * Extract numeric suffix from agent name (e.g., RobotAgent3 -> 3,
     * ConveyorAgent1 -> 1)
     * 
     * @param agentName The agent name
     * @return The numeric suffix, or -1 if not found
     */
    private int extractAgentNumber(String agentName) {
        if (agentName == null || agentName.isEmpty()) {
            return -1;
        }

        // Find last sequence of digits in the name
        String digits = "";
        for (int i = agentName.length() - 1; i >= 0; i--) {
            char c = agentName.charAt(i);
            if (Character.isDigit(c)) {
                digits = c + digits;
            } else if (!digits.isEmpty()) {
                // Found non-digit after digits, stop
                break;
            }
        }

        if (digits.isEmpty()) {
            return -1;
        }

        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void notifyLeaseUpdated(FAPRecord record) {
        for (FAPEventListener listener : eventListeners) {
            try {
                listener.onLeaseUpdated(record);
            } catch (Exception e) {
                System.err.println("Error notifying FAP event listener: " + e.getMessage());
            }
        }
    }
}