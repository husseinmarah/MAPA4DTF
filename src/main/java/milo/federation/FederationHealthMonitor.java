package milo.federation;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.TickerBehaviour;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Federation Health Monitor and Recovery System
 * 
 * Provides:
 * - Real-time federation health monitoring
 * - Automatic failure detection and recovery
 * - Performance metrics collection
 * - Federation topology health assessment
 * - Proactive maintenance and optimization
 * - FAP integration for address monitoring
 */
public class FederationHealthMonitor {
    
    /**
     * Health status levels
     */
    public enum HealthStatus {
        HEALTHY,        // All systems operational
        DEGRADED,       // Some issues but functional
        CRITICAL,       // Major issues, limited functionality
        FAILED,         // System failure, non-functional
        RECOVERING,     // In recovery process
        MAINTENANCE     // Under maintenance
    }
    
    /**
     * Health metrics for federation entities
     */
    public static class HealthMetrics {
        public AID entityId;
        public String ffa;
        public HealthStatus status;
        public long lastSeen;
        public long responseTime;
        public int failureCount;
        public int successCount;
        public double availabilityRatio;
        public Map<String, Object> customMetrics;
        public List<String> healthIssues;
        
        public HealthMetrics(AID entityId, String ffa) {
            this.entityId = entityId;
            this.ffa = ffa;
            this.status = HealthStatus.HEALTHY;
            this.lastSeen = System.currentTimeMillis();
            this.responseTime = 0;
            this.failureCount = 0;
            this.successCount = 0;
            this.availabilityRatio = 1.0;
            this.customMetrics = new HashMap<>();
            this.healthIssues = new ArrayList<>();
        }
        
        public void recordSuccess(long responseTime) {
            this.successCount++;
            this.responseTime = responseTime;
            this.lastSeen = System.currentTimeMillis();
            updateAvailabilityRatio();
        }
        
        public void recordFailure(String issue) {
            this.failureCount++;
            this.healthIssues.add(System.currentTimeMillis() + ": " + issue);
            updateAvailabilityRatio();
            updateHealthStatus();
        }
        
        private void updateAvailabilityRatio() {
            int total = successCount + failureCount;
            this.availabilityRatio = total > 0 ? (double) successCount / total : 1.0;
        }
        
        private void updateHealthStatus() {
            if (availabilityRatio >= 0.95) {
                status = HealthStatus.HEALTHY;
            } else if (availabilityRatio >= 0.80) {
                status = HealthStatus.DEGRADED;
            } else if (availabilityRatio >= 0.50) {
                status = HealthStatus.CRITICAL;
            } else {
                status = HealthStatus.FAILED;
            }
        }
        
        public boolean isStale(long timeoutMs) {
            return (System.currentTimeMillis() - lastSeen) > timeoutMs;
        }
        
        public void addCustomMetric(String key, Object value) {
            customMetrics.put(key, value);
        }
    }
    
    // Health monitoring data
    private final Map<AID, HealthMetrics> healthMetrics = new ConcurrentHashMap<>();
    private final FAPProtocol fapProtocol;
    
    // FAP-specific tracking maps
    private final Map<String, Long> fapAllocationTimes = new ConcurrentHashMap<>();
    private final Map<String, Long> fapResolutionTimes = new ConcurrentHashMap<>();
    private final Map<String, Object> fapSystemHealth = new ConcurrentHashMap<>();
    
    // Statistics
    private long totalFAPAllocations = 0;
    private long totalFAPResolutions = 0;
    private long totalHealthChecks = 0;
    private long lastFAPHealthCheck = System.currentTimeMillis();
    
    // Configuration
    private static final long HEALTH_CHECK_INTERVAL = 30000; // 30 seconds
    private static final long ENTITY_TIMEOUT = 60000; // 1 minute
    
    public FederationHealthMonitor(FAPProtocol fapProtocol) {
        this.fapProtocol = fapProtocol != null ? fapProtocol : new FAPProtocol();
    }
    
    // ========== FAP INTEGRATION METHODS ==========
    
    /**
     * Report FAP address allocation
     */
    public void reportFAPAllocation(FAPProtocol.FAPRecord record) {
        if (record != null && record.aid != null && record.ffa != null) {
            String aidKey = record.aid.getName();
            String ffaKey = record.ffa.toString();
            
            // Track allocation timing
            fapAllocationTimes.put(ffaKey, record.allocationTime);
            totalFAPAllocations++;
            
            // Update or create health metrics for the agent
            HealthMetrics metrics = healthMetrics.computeIfAbsent(record.aid, aid -> new HealthMetrics(aid, ffaKey));
            metrics.lastSeen = record.allocationTime;
            metrics.ffa = ffaKey;
            metrics.addCustomMetric("last-fap-allocation", record.allocationTime);
            metrics.addCustomMetric("allocated-ffa", ffaKey);
            metrics.addCustomMetric("lease-expiry", record.leaseExpiry);
            metrics.addCustomMetric("remaining-lease-time", record.getRemainingLeaseTime());
            
            // Record as successful operation
            metrics.recordSuccess(0); // Allocation doesn't have response time
            
            System.out.println("🏥 [HealthMonitor] FAP Allocation: " + ffaKey + " -> " + aidKey + 
                " (Lease expires in " + record.getRemainingLeaseTime() + "ms)");
                
        } else {
            System.err.println("🏥 [HealthMonitor] Invalid FAP allocation record provided");
        }
    }
    
    /**
     * Report FAP address resolution
     */
    public void reportFAPResolution(FAPProtocol.FAPRecord record) {
        if (record != null && record.aid != null && record.ffa != null) {
            String aidKey = record.aid.getName();
            String ffaKey = record.ffa.toString();
            long currentTime = System.currentTimeMillis();
            
            // Track resolution timing
            fapResolutionTimes.put(ffaKey, currentTime);
            totalFAPResolutions++;
            
            // Update health metrics
            HealthMetrics metrics = healthMetrics.get(record.aid);
            if (metrics != null) {
                metrics.lastSeen = currentTime;
                metrics.addCustomMetric("last-fap-resolution", currentTime);
                metrics.addCustomMetric("resolved-ffa", ffaKey);
                metrics.addCustomMetric("lease-expiry", record.leaseExpiry);
                metrics.addCustomMetric("remaining-lease-time", record.getRemainingLeaseTime());
                
                // Calculate resolution time if allocation time is available
                Long allocationTime = fapAllocationTimes.get(ffaKey);
                if (allocationTime != null) {
                    long resolutionTime = currentTime - allocationTime;
                    metrics.recordSuccess(resolutionTime);
                } else {
                    metrics.recordSuccess(0);
                }
            }
            
            System.out.println("🏥 [HealthMonitor] FAP Resolution: " + ffaKey + " -> " + aidKey + 
                " (Lease remaining: " + record.getRemainingLeaseTime() + "ms)");
                
        } else {
            System.err.println("🏥 [HealthMonitor] Invalid FAP resolution record provided");
        }
    }
    
    /**
     * Report FAP health check results
     */
    public void reportFAPHealthCheck(Map<String, Object> healthStatus) {
        if (healthStatus != null) {
            lastFAPHealthCheck = System.currentTimeMillis();
            totalHealthChecks++;
            
            // Store FAP system health data
            fapSystemHealth.putAll(healthStatus);
            fapSystemHealth.put("last-health-check", lastFAPHealthCheck);
            fapSystemHealth.put("health-check-count", totalHealthChecks);
            
            // Extract key metrics
            Object activeRecords = healthStatus.get("active-records");
            Object totalAllocations = healthStatus.get("total-allocations");
            Object totalResolutions = healthStatus.get("total-resolutions");
            Object expiredLeases = healthStatus.get("expired-leases");
            Object registrySize = healthStatus.get("registry-size-ffa");
            
            System.out.println("🏥 [HealthMonitor] FAP Health Check #" + totalHealthChecks + ":");
            System.out.println("  📊 Active Records: " + activeRecords);
            System.out.println("  📈 Total Allocations: " + totalAllocations);
            System.out.println("  🔍 Total Resolutions: " + totalResolutions);
            System.out.println("  🗑️ Expired Leases: " + expiredLeases);
            System.out.println("  💾 Registry Size: " + registrySize);
            
            // Analyze FAP system health
            analyzeFAPSystemHealth(healthStatus);
            
        } else {
            System.err.println("🏥 [HealthMonitor] Null health status provided to reportFAPHealthCheck");
        }
    }
    
    /**
     * Analyze FAP system health and detect issues
     */
    private void analyzeFAPSystemHealth(Map<String, Object> healthStatus) {
        // Check for potential issues
        Object activeRecords = healthStatus.get("active-records");
        Object expiredLeases = healthStatus.get("expired-leases");
        Object totalAllocations = healthStatus.get("total-allocations");
        
        if (activeRecords instanceof Number && ((Number) activeRecords).intValue() == 0) {
            System.out.println("⚠️ [HealthMonitor] WARNING: No active FAP records found");
        }
        
        if (expiredLeases instanceof Number && ((Number) expiredLeases).longValue() > 0) {
            System.out.println("🧹 [HealthMonitor] INFO: Cleaned up " + expiredLeases + " expired leases");
        }
        
        // Check allocation rate
        if (totalAllocations instanceof Number) {
            long currentAllocations = ((Number) totalAllocations).longValue();
            Object lastTotalAllocations = fapSystemHealth.get("total-allocations");
            if (lastTotalAllocations instanceof Number) {
                long previousAllocations = ((Number) lastTotalAllocations).longValue();
                long allocationRate = currentAllocations - previousAllocations;
                if (allocationRate > 0) {
                    System.out.println("📈 [HealthMonitor] Allocation rate: " + allocationRate + " new allocations since last check");
                }
            }
        }
    }
    
    // ========== ORIGINAL METHODS ==========
    
    /**
     * Start health monitoring for an agent
     */
    public void startHealthMonitoring(Agent agent) {
        agent.addBehaviour(new TickerBehaviour(agent, HEALTH_CHECK_INTERVAL) {
            @Override
            protected void onTick() {
                performHealthCheck();
            }
        });
        
        System.out.println("🏥 Started federation health monitoring for " + agent.getLocalName());
    }
    
    /**
     * Register an entity for health monitoring
     */
    public void registerEntity(AID entityId, String ffa) {
        HealthMetrics metrics = new HealthMetrics(entityId, ffa);
        healthMetrics.put(entityId, metrics);
        
        System.out.println("📋 Registered entity for health monitoring: " + entityId.getLocalName() + " (FFA: " + ffa + ")");
    }
    
    /**
     * Record successful interaction with entity
     */
    public void recordSuccess(AID entityId, long responseTime) {
        HealthMetrics metrics = healthMetrics.get(entityId);
        if (metrics != null) {
            metrics.recordSuccess(responseTime);
        }
    }
    
    /**
     * Record failed interaction with entity
     */
    public void recordFailure(AID entityId, String issue) {
        HealthMetrics metrics = healthMetrics.get(entityId);
        if (metrics != null) {
            metrics.recordFailure(issue);
            
            // Check if recovery is needed
            if (metrics.status == HealthStatus.CRITICAL || metrics.status == HealthStatus.FAILED) {
                System.out.println("⚠️ Entity requires attention: " + entityId.getLocalName() + " (Status: " + metrics.status + ")");
            }
        }
    }
    
    /**
     * Perform comprehensive health check
     */
    private void performHealthCheck() {
        List<AID> staleEntities = new ArrayList<>();
        List<AID> degradedEntities = new ArrayList<>();
        
        for (Map.Entry<AID, HealthMetrics> entry : healthMetrics.entrySet()) {
            AID entityId = entry.getKey();
            HealthMetrics metrics = entry.getValue();
            
            // Check for stale entities
            if (metrics.isStale(ENTITY_TIMEOUT)) {
                staleEntities.add(entityId);
                metrics.recordFailure("Entity timeout - no response for " + ENTITY_TIMEOUT + "ms");
            }
            
            // Check for degraded entities
            if (metrics.status == HealthStatus.DEGRADED || metrics.status == HealthStatus.CRITICAL) {
                degradedEntities.add(entityId);
            }
        }
        
        // Report findings
        if (!staleEntities.isEmpty()) {
            System.out.println("⚠️ Found " + staleEntities.size() + " stale entities");
        }
        
        if (!degradedEntities.isEmpty()) {
            System.out.println("⚠️ Found " + degradedEntities.size() + " degraded entities");
        }
        
        // Update overall federation health
        updateFederationHealth();
        
        // Trigger FAP health check if available
        if (fapProtocol != null) {
            int cleanedUp = fapProtocol.performHealthCheck();
            if (cleanedUp > 0) {
                System.out.println("🧹 [HealthMonitor] FAP cleanup removed " + cleanedUp + " expired records");
            }
        }
    }
    
    /**
     * Update overall federation health status
     */
    private void updateFederationHealth() {
        if (healthMetrics.isEmpty()) return;
        
        Map<HealthStatus, Integer> statusCount = new HashMap<>();
        for (HealthMetrics metrics : healthMetrics.values()) {
            statusCount.merge(metrics.status, 1, Integer::sum);
        }
        
        int total = healthMetrics.size();
        int healthy = statusCount.getOrDefault(HealthStatus.HEALTHY, 0);
        
        double healthRatio = (double) healthy / total;
        
        if (healthRatio >= 0.90) {
            System.out.println("💚 Federation Health: EXCELLENT (" + (int)(healthRatio * 100) + "% healthy)");
        } else if (healthRatio >= 0.75) {
            System.out.println("💛 Federation Health: GOOD (" + (int)(healthRatio * 100) + "% healthy)");
        } else if (healthRatio >= 0.50) {
            System.out.println("🧡 Federation Health: DEGRADED (" + (int)(healthRatio * 100) + "% healthy)");
        } else {
            System.out.println("❤️ Federation Health: CRITICAL (" + (int)(healthRatio * 100) + "% healthy)");
        }
    }
    
    /**
     * Get health status for specific entity
     */
    public HealthMetrics getHealthMetrics(AID entityId) {
        return healthMetrics.get(entityId);
    }
    
    /**
     * Get overall federation health statistics
     */
    public Map<String, Object> getFederationHealthStats() {
        Map<String, Object> stats = new HashMap<>();
        
        if (healthMetrics.isEmpty()) {
            stats.put("status", "NO_ENTITIES");
            return stats;
        }
        
        Map<HealthStatus, Integer> statusCount = new HashMap<>();
        long totalResponseTime = 0;
        int responseCount = 0;
        
        for (HealthMetrics metrics : healthMetrics.values()) {
            statusCount.merge(metrics.status, 1, Integer::sum);
            if (metrics.responseTime > 0) {
                totalResponseTime += metrics.responseTime;
                responseCount++;
            }
        }
        
        int total = healthMetrics.size();
        double avgResponseTime = responseCount > 0 ? (double) totalResponseTime / responseCount : 0;
        
        stats.put("total-entities", total);
        stats.put("status-distribution", statusCount);
        stats.put("avg-response-time-ms", avgResponseTime);
        stats.put("health-check-interval-ms", HEALTH_CHECK_INTERVAL);
        
        // Add FAP-specific statistics
        stats.put("fap-total-allocations", totalFAPAllocations);
        stats.put("fap-total-resolutions", totalFAPResolutions);
        stats.put("fap-total-health-checks", totalHealthChecks);
        stats.put("fap-last-health-check", lastFAPHealthCheck);
        stats.putAll(fapSystemHealth);
        
        return stats;
    }
    
    /**
     * Get all entities with specific health status
     */
    public List<AID> getEntitiesByStatus(HealthStatus status) {
        List<AID> entities = new ArrayList<>();
        
        for (Map.Entry<AID, HealthMetrics> entry : healthMetrics.entrySet()) {
            if (entry.getValue().status == status) {
                entities.add(entry.getKey());
            }
        }
        
        return entities;
    }
    
    /**
     * Get FAP-specific metrics
     */
    public Map<String, Object> getFAPMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        long currentTime = System.currentTimeMillis();
        
        metrics.put("total-allocations", totalFAPAllocations);
        metrics.put("total-resolutions", totalFAPResolutions);
        metrics.put("total-health-checks", totalHealthChecks);
        metrics.put("last-health-check", lastFAPHealthCheck);
        
        // Calculate recent activity (last 5 minutes)
        long fiveMinutesAgo = currentTime - (5 * 60 * 1000);
        long recentAllocations = fapAllocationTimes.values().stream()
            .mapToLong(Long::longValue)
            .filter(time -> time > fiveMinutesAgo)
            .count();
        long recentResolutions = fapResolutionTimes.values().stream()
            .mapToLong(Long::longValue)
            .filter(time -> time > fiveMinutesAgo)
            .count();
            
        metrics.put("recent-allocations-5min", recentAllocations);
        metrics.put("recent-resolutions-5min", recentResolutions);
        metrics.put("activity-level", recentAllocations + recentResolutions);
        
        // Add current FAP system health data
        metrics.putAll(fapSystemHealth);
        
        return metrics;
    }
    
    /**
     * Report agent heartbeat (additional utility method)
     */
    public void reportAgentHeartbeat(AID agentId) {
        if (agentId != null) {
            HealthMetrics metrics = healthMetrics.get(agentId);
            if (metrics != null) {
                metrics.lastSeen = System.currentTimeMillis();
                metrics.addCustomMetric("last-heartbeat", metrics.lastSeen);
                System.out.println("💓 [HealthMonitor] Heartbeat from agent: " + agentId.getLocalName());
            }
        }
    }
    
    /**
     * Report service registration health
     */
    public void reportServiceRegistration(AID agentId, String serviceName, String ffa) {
        if (agentId != null && serviceName != null) {
            long currentTime = System.currentTimeMillis();
            
            HealthMetrics metrics = healthMetrics.get(agentId);
            if (metrics != null) {
                metrics.lastSeen = currentTime;
                metrics.addCustomMetric("last-service-registration", currentTime);
                metrics.addCustomMetric("registered-service", serviceName);
                if (ffa != null) {
                    metrics.addCustomMetric("service-ffa", ffa);
                    metrics.ffa = ffa; // Update FFA if provided
                }
                metrics.recordSuccess(0); // Service registration is considered a success
            }
            
            System.out.println("📋 [HealthMonitor] Service registration: " + serviceName + 
                " by agent: " + agentId.getLocalName() + (ffa != null ? " with FFA: " + ffa : ""));
        }
    }
}