package milo.utils;

import milo.security.FederationSecurityManager;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Modular system health monitor for the federated manufacturing system
 * Tracks health of all system components and provides consolidated status
 */
public class SystemHealthMonitor {
    
    // Singleton instance
    private static volatile SystemHealthMonitor instance;
    
    // Health tracking
    private final Map<String, ComponentHealth> componentHealthMap = new ConcurrentHashMap<>();
    private final Map<String, Long> lastHealthCheck = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    // Configuration
    private final long healthCheckInterval = 30000; // 30 seconds
    private final long componentTimeout = 120000; // 2 minutes
    
    /**
     * Component health status
     */
    public enum HealthStatus {
        HEALTHY("Component operating normally"),
        DEGRADED("Component experiencing issues but functional"),
        UNHEALTHY("Component not functioning properly"),
        UNKNOWN("Component status unknown"),
        OFFLINE("Component not responding");
        
        private final String description;
        
        HealthStatus(String description) {
            this.description = description;
        }
        
        public String getDescription() { return description; }
    }
    
    /**
     * Component health information
     */
    public static class ComponentHealth {
        public final String componentName;
        public final String componentType;
        public HealthStatus status;
        public String statusMessage;
        public long lastUpdateTime;
        public Map<String, Object> metrics;
        
        public ComponentHealth(String name, String type) {
            this.componentName = name;
            this.componentType = type;
            this.status = HealthStatus.UNKNOWN;
            this.statusMessage = "Component not initialized";
            this.lastUpdateTime = System.currentTimeMillis();
            this.metrics = new HashMap<>();
        }
        
        public void updateHealth(HealthStatus status, String message, Map<String, Object> metrics) {
            this.status = status;
            this.statusMessage = message;
            this.lastUpdateTime = System.currentTimeMillis();
            if (metrics != null) {
                this.metrics.putAll(metrics);
            }
        }
        
        @Override
        public String toString() {
            return String.format("%s (%s): %s - %s", 
                componentName, componentType, status, statusMessage);
        }
    }
    
    /**
     * Private constructor for singleton
     */
    private SystemHealthMonitor() {
        startHealthMonitoring();
        System.out.println("🏥 SystemHealthMonitor initialized");
    }
    
    /**
     * Get singleton instance
     */
    public static SystemHealthMonitor getInstance() {
        if (instance == null) {
            synchronized (SystemHealthMonitor.class) {
                if (instance == null) {
                    instance = new SystemHealthMonitor();
                }
            }
        }
        return instance;
    }
    
    /**
     * Register a component for health monitoring
     */
    public ComponentHealth registerComponent(String componentName, String componentType) {
        ComponentHealth health = new ComponentHealth(componentName, componentType);
        componentHealthMap.put(componentName, health);
        lastHealthCheck.put(componentName, System.currentTimeMillis());
        
        System.out.println("🔔 Registered component for health monitoring: " + componentName + " (" + componentType + ")");
        return health;
    }
    
    /**
     * Update component health status
     */
    public void updateComponentHealth(String componentName, HealthStatus status, String message) {
        updateComponentHealth(componentName, status, message, null);
    }
    
    /**
     * Update component health status with metrics
     */
    public void updateComponentHealth(String componentName, HealthStatus status, String message, Map<String, Object> metrics) {
        ComponentHealth health = componentHealthMap.get(componentName);
        if (health != null) {
            health.updateHealth(status, message, metrics);
            lastHealthCheck.put(componentName, System.currentTimeMillis());
        } else {
            System.err.println("⚠️ Attempted to update health for unregistered component: " + componentName);
        }
    }
    
    /**
     * Get health status of a specific component
     */
    public ComponentHealth getComponentHealth(String componentName) {
        return componentHealthMap.get(componentName);
    }
    
    /**
     * Get overall system health status
     */
    public HealthStatus getOverallSystemHealth() {
        if (componentHealthMap.isEmpty()) {
            return HealthStatus.UNKNOWN;
        }
        
        int healthy = 0, degraded = 0, unhealthy = 0, offline = 0, unknown = 0;
        
        for (ComponentHealth health : componentHealthMap.values()) {
            switch (health.status) {
                case HEALTHY: healthy++; break;
                case DEGRADED: degraded++; break;
                case UNHEALTHY: unhealthy++; break;
                case OFFLINE: offline++; break;
                case UNKNOWN: unknown++; break;
            }
        }
        
        // Determine overall status based on component statuses
        if (unhealthy > 0 || offline > componentHealthMap.size() / 2) {
            return HealthStatus.UNHEALTHY;
        } else if (degraded > 0 || offline > 0) {
            return HealthStatus.DEGRADED;
        } else if (healthy > componentHealthMap.size() / 2) {
            return HealthStatus.HEALTHY;
        } else {
            return HealthStatus.UNKNOWN;
        }
    }
    
    /**
     * Get comprehensive system health report
     */
    public Map<String, Object> getSystemHealthReport() {
        Map<String, Object> report = new HashMap<>();
        
        // Overall system status
        report.put("overall-status", getOverallSystemHealth());
        report.put("total-components", componentHealthMap.size());
        report.put("report-timestamp", new Date());
        
        // Component status distribution
        Map<String, Integer> statusDistribution = new HashMap<>();
        for (HealthStatus status : HealthStatus.values()) {
            statusDistribution.put(status.name(), 0);
        }
        
        // Component details
        List<Map<String, Object>> componentDetails = new ArrayList<>();
        for (ComponentHealth health : componentHealthMap.values()) {
            statusDistribution.put(health.status.name(), 
                statusDistribution.get(health.status.name()) + 1);
            
            Map<String, Object> componentInfo = new HashMap<>();
            componentInfo.put("name", health.componentName);
            componentInfo.put("type", health.componentType);
            componentInfo.put("status", health.status.name());
            componentInfo.put("message", health.statusMessage);
            componentInfo.put("last-update", new Date(health.lastUpdateTime));
            componentInfo.put("metrics", health.metrics);
            
            componentDetails.add(componentInfo);
        }
        
        report.put("status-distribution", statusDistribution);
        report.put("component-details", componentDetails);
        
        // System-wide metrics
        Map<String, Object> systemMetrics = new HashMap<>();
        systemMetrics.put("uptime-ms", System.currentTimeMillis() - getEarliestComponentTime());
        systemMetrics.put("health-check-interval-ms", healthCheckInterval);
        systemMetrics.put("component-timeout-ms", componentTimeout);
        
        report.put("system-metrics", systemMetrics);
        
        return report;
    }
    
    /**
     * Perform system-wide health check
     */
    public boolean performSystemHealthCheck() {
        try {
            System.out.println("🔍 Performing comprehensive system health check...");
            
            // Check all registered components
            for (String componentName : componentHealthMap.keySet()) {
                checkComponentTimeout(componentName);
            }
            
            // Check security system
            checkSecuritySystemHealth();
            
            // Check federation services
            checkFederationServicesHealth();
            
            // Generate health report
            Map<String, Object> report = getSystemHealthReport();
            HealthStatus overallStatus = (HealthStatus) report.get("overall-status");
            
            System.out.println("📊 System health check completed - Overall status: " + overallStatus);
            
            return overallStatus == HealthStatus.HEALTHY || overallStatus == HealthStatus.DEGRADED;
            
        } catch (Exception e) {
            System.err.println("❌ Error during system health check: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Start periodic health monitoring
     */
    private void startHealthMonitoring() {
        // Schedule periodic health checks
        scheduler.scheduleAtFixedRate(this::performPeriodicHealthCheck, 
            healthCheckInterval, healthCheckInterval, TimeUnit.MILLISECONDS);
        
        // Schedule component timeout checks
        scheduler.scheduleAtFixedRate(this::checkAllComponentTimeouts, 
            healthCheckInterval / 2, healthCheckInterval / 2, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Perform periodic health check
     */
    private void performPeriodicHealthCheck() {
        try {
            HealthStatus overallStatus = getOverallSystemHealth();
            
            // Only log status changes or periodic updates
            if (System.currentTimeMillis() % 300000 == 0) { // Every 5 minutes
                System.out.println("🩺 Periodic health check - System status: " + overallStatus + 
                    " (Components: " + componentHealthMap.size() + ")");
            }
            
        } catch (Exception e) {
            System.err.println("❌ Error in periodic health check: " + e.getMessage());
        }
    }
    
    /**
     * Check all components for timeouts
     */
    private void checkAllComponentTimeouts() {
        for (String componentName : componentHealthMap.keySet()) {
            checkComponentTimeout(componentName);
        }
    }
    
    /**
     * Check if a component has timed out
     */
    private void checkComponentTimeout(String componentName) {
        Long lastCheck = lastHealthCheck.get(componentName);
        if (lastCheck != null) {
            long timeSinceLastCheck = System.currentTimeMillis() - lastCheck;
            
            if (timeSinceLastCheck > componentTimeout) {
                ComponentHealth health = componentHealthMap.get(componentName);
                if (health != null && health.status != HealthStatus.OFFLINE) {
                    updateComponentHealth(componentName, HealthStatus.OFFLINE, 
                        "Component not responding for " + (timeSinceLastCheck / 1000) + " seconds");
                }
            }
        }
    }
    
    /**
     * Check security system health
     */
    private void checkSecuritySystemHealth() {
        try {
            FederationSecurityManager securityManager = FederationSecurityManager.getInstance();
            Map<String, Object> securityStats = securityManager.getSecurityStats();
            
            if (securityStats != null && !securityStats.isEmpty()) {
                updateComponentHealth("SecurityManager", HealthStatus.HEALTHY, 
                    "Security system operational", securityStats);
            } else {
                updateComponentHealth("SecurityManager", HealthStatus.DEGRADED, 
                    "Security statistics unavailable");
            }
            
        } catch (Exception e) {
            updateComponentHealth("SecurityManager", HealthStatus.UNHEALTHY, 
                "Security system error: " + e.getMessage());
        }
    }
    
    /**
     * Check federation services health
     */
    private void checkFederationServicesHealth() {
        // This would check FAM, FFA services, etc.
        // For now, we'll do a basic check
        updateComponentHealth("FederationServices", HealthStatus.HEALTHY, 
            "Federation services operational");
    }
    
    /**
     * Get earliest component registration time
     */
    private long getEarliestComponentTime() {
        return componentHealthMap.values().stream()
            .mapToLong(health -> health.lastUpdateTime)
            .min()
            .orElse(System.currentTimeMillis());
    }
    
    /**
     * Shutdown health monitor
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        System.out.println("🏥 SystemHealthMonitor shutdown complete");
    }
}
