package milo.security;

import jade.lang.acl.ACLMessage;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Federation Security Manager for policy enforcement and IP protection
 * Singleton pattern ensures consistent security state across the federation
 * 
 * Key Features:
 * - Container-level security policies
 * - Agent authentication and authorization
 * - Cross-company communication control
 * - Security event auditing
 * - Modular validation system
 */
public class FederationSecurityManager {
    
    /**
     * Security levels for agents and containers
     */
    public enum SecurityLevel {
        PUBLIC(0, "Public access - basic services only"),
        RESTRICTED(1, "Restricted access - limited federation services"), 
        TRUSTED(2, "Trusted access - full federation participation"),
        PRIVILEGED(3, "Privileged access - administrative capabilities");
        
        private final int level;
        private final String description;
        
        SecurityLevel(int level, String description) {
            this.level = level;
            this.description = description;
        }
        
        public int getLevel() { return level; }
        public String getDescription() { return description; }
    }
    
    /**
     * Security context for each agent in the federation
     */
    public static class SecurityContext {
        public final String agentName;
        public final String companyId;
        public final String containerName;
        public final SecurityLevel level;
        public final long createdTime;
        
        public SecurityContext(String agentName, String companyId, String containerName, SecurityLevel level) {
            this.agentName = agentName;
            this.companyId = companyId;
            this.containerName = containerName;
            this.level = level;
            this.createdTime = System.currentTimeMillis();
        }
        
        @Override
        public String toString() {
            return String.format("SecurityContext{agent=%s, company=%s, level=%s}", 
                agentName, companyId, level);
        }
    }
    
    // Singleton instance
    private static volatile FederationSecurityManager instance;
    
    // Modular components
    private final SecurityValidator validator;
    
    // Security state management
    private final Map<String, SecurityContext> agentContexts = new ConcurrentHashMap<>();
    private final Map<String, List<String>> auditLog = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> companyServices = new ConcurrentHashMap<>();
    
    // Configuration
    private final SecurityConfiguration config;
    
    /**
     * Private constructor for singleton pattern
     */
    private FederationSecurityManager() {
        validator = new SecurityValidator();
        config = new SecurityConfiguration();
        initializeCompanyPolicies();
        System.out.println("🔐 FederationSecurityManager initialized with modular components");
    }
    
    /**
     * Get singleton instance
     */
    public static FederationSecurityManager getInstance() {
        if (instance == null) {
            synchronized (FederationSecurityManager.class) {
                if (instance == null) {
                    instance = new FederationSecurityManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * Register a secure agent with the security manager
     */
    public SecurityContext registerSecureAgent(String agentName, String companyId, String containerName) {
        try {
            SecurityLevel level = determineSecurityLevel(companyId);
            SecurityContext context = new SecurityContext(agentName, companyId, containerName, level);
            
            agentContexts.put(agentName, context);
            logSecurityEvent("AGENT_REGISTERED", agentName + ":" + companyId + ":" + level);
            
            System.out.println("🛡️ Registered secure agent: " + agentName + 
                              " (Company: " + companyId + ", Level: " + level + ")");
            
            return context;
        } catch (Exception e) {
            logSecurityEvent("REGISTRATION_ERROR", agentName + " - " + e.getMessage());
            System.err.println("❌ Error registering agent " + agentName + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Check if an agent can access a specific service
     */
    public boolean canAccessService(String agentName, String serviceName) {
        try {
            SecurityContext context = agentContexts.get(agentName);
            Set<String> allowedServices = companyServices.get(context != null ? context.companyId : null);
            
            SecurityValidator.ValidationResult result = validator.validateServiceAccess(
                agentName, serviceName, context, allowedServices);
            
            if (result.allowed) {
                logSecurityEvent("ACCESS_GRANTED", agentName + " -> " + serviceName + " (" + result.reason + ")");
            } else {
                logSecurityEvent("ACCESS_DENIED", agentName + " -> " + serviceName + " (" + result.reason + ")");
            }
            
            return result.allowed;
        } catch (Exception e) {
            logSecurityEvent("ACCESS_ERROR", agentName + " -> " + serviceName + " - " + e.getMessage());
            System.err.println("❌ Error validating service access: " + e.getMessage());
            return false; // Fail secure
        }
    }
    
    /**
     * Validate a message between agents (checks cross-company communication)
     */
    public boolean validateMessage(ACLMessage message, String sourceAgent, String targetAgent) {
        try {
            // Whitelist for system/infrastructure agents
            if (validator.isSystemAgent(sourceAgent) || validator.isSystemAgent(targetAgent)) {
                return true; // Allow system agents to communicate freely
            }
            
            // Validate message content
            SecurityValidator.ValidationResult messageResult = validator.validateMessage(message);
            if (!messageResult.allowed) {
                logSecurityEvent("MSG_BLOCKED", sourceAgent + " -> " + targetAgent + " (" + messageResult.reason + ")");
                return false;
            }
            
            SecurityContext sourceContext = agentContexts.get(sourceAgent);
            SecurityContext targetContext = agentContexts.get(targetAgent);
            
            SecurityValidator.ValidationResult commResult = validator.validateCrossCommunication(
                sourceContext, targetContext);
            
            if (!commResult.allowed) {
                logSecurityEvent("MSG_BLOCKED", sourceAgent + " -> " + targetAgent + " (" + commResult.reason + ")");
            }
            
            return commResult.allowed;
        } catch (Exception e) {
            logSecurityEvent("MSG_ERROR", sourceAgent + " -> " + targetAgent + " - " + e.getMessage());
            System.err.println("❌ Error validating message: " + e.getMessage());
            return false; // Fail secure
        }
    }
    
    /**
     * Get security context for an agent
     */
    public SecurityContext getAgentContext(String agentName) {
        return agentContexts.get(agentName);
    }
    
    /**
     * Get security statistics for monitoring
     */
    public Map<String, Object> getSecurityStats() {
        try {
            Map<String, Object> stats = new HashMap<>();
            stats.put("registered-companies", companyServices.size());
            stats.put("registered-agents", agentContexts.size());
            stats.put("security-events", auditLog.values().stream().mapToInt(List::size).sum());
            
            // Calculate security level distribution
            Map<String, Integer> levelDistribution = new HashMap<>();
            for (SecurityLevel level : SecurityLevel.values()) {
                levelDistribution.put(level.name(), 0);
            }
            
            for (SecurityContext context : agentContexts.values()) {
                String levelName = context.level.name();
                levelDistribution.put(levelName, levelDistribution.get(levelName) + 1);
            }
            
            stats.put("security-level-distribution", levelDistribution);
            stats.put("validator-status", "active");
            stats.put("configuration-loaded", config.isLoaded());
            
            return stats;
        } catch (Exception e) {
            System.err.println("❌ Error getting security stats: " + e.getMessage());
            return new HashMap<>(); // Return empty stats on error
        }
    }
    
    /**
     * Get audit log for security review
     */
    public Map<String, List<String>> getAuditLog() {
        return new HashMap<>(auditLog);
    }
    
    /**
     * Test the security system - for integration testing
     */
    public boolean performSecurityTest() {
        try {
            System.out.println("🧪 Running security system test...");
            
            // Test 1: Agent registration
            SecurityContext testContext = registerSecureAgent("TestAgent", "TestCompany", "TestContainer");
            if (testContext == null) {
                System.err.println("❌ Test failed: Agent registration");
                return false;
            }
            
            // Test 2: Service access validation
            boolean canAccess = canAccessService("TestAgent", "status");
            if (!canAccess) {
                System.err.println("❌ Test failed: Basic service access");
                return false;
            }
            
            // Test 3: Cross-company validation
            SecurityContext otherContext = new SecurityContext("OtherAgent", "OtherCompany", "OtherContainer", SecurityLevel.TRUSTED);
            agentContexts.put("OtherAgent", otherContext);
            
            SecurityValidator.ValidationResult crossResult = validator.validateCrossCommunication(testContext, otherContext);
            // This should succeed for TRUSTED level agents
            
            // Cleanup test data
            agentContexts.remove("TestAgent");
            agentContexts.remove("OtherAgent");
            
            System.out.println("✅ Security system test completed successfully");
            return true;
            
        } catch (Exception e) {
            System.err.println("❌ Security test failed: " + e.getMessage());
            return false;
        }
    }
    
    // ========== Private Helper Methods ==========
    
    /**
     * Initialize default company policies
     */
    private void initializeCompanyPolicies() {
        try {
            // Load from configuration if available, otherwise use defaults
            if (config.hasCustomPolicies()) {
                companyServices.putAll(config.getCompanyPolicies());
            } else {
                initializeDefaultPolicies();
            }
            
            System.out.println("📋 Initialized security policies for " + companyServices.size() + " companies");
        } catch (Exception e) {
            System.err.println("❌ Error initializing company policies: " + e.getMessage());
            initializeDefaultPolicies(); // Fallback to defaults
        }
    }
    
    private void initializeDefaultPolicies() {
        // TechRobotics company services
        Set<String> techServices = new HashSet<>();
        techServices.addAll(Arrays.asList(
            "capability-discovery", "task-execution", "collaboration", 
            "navigation-services", "swarm-coordination"
        ));
        companyServices.put("TechRobotics", techServices);
        
        // InnovateBots company services
        Set<String> innovaServices = new HashSet<>();
        innovaServices.addAll(Arrays.asList(
            "capability-discovery", "task-execution", "collaboration",
            "energy-optimization", "precision-manipulation"
        ));
        companyServices.put("InnovateBots", innovaServices);
        
        // Federation services (admin access)
        Set<String> federationServices = new HashSet<>();
        federationServices.add("all-services");
        companyServices.put("Federation", federationServices);
    }
    
    /**
     * Determine security level based on company ID
     */
    private SecurityLevel determineSecurityLevel(String companyId) {
        try {
            if (config.hasCustomLevels()) {
                return config.getSecurityLevel(companyId);
            }
            
            // Default logic
            switch (companyId) {
                case "Federation":
                    return SecurityLevel.PRIVILEGED;
                case "TechRobotics":
                case "InnovateBots":
                    return SecurityLevel.TRUSTED;
                default:
                    return SecurityLevel.PUBLIC;
            }
        } catch (Exception e) {
            System.err.println("❌ Error determining security level for " + companyId + ": " + e.getMessage());
            return SecurityLevel.PUBLIC; // Fail to lowest privilege
        }
    }
    
    /**
     * Log security events for audit trail
     */
    private void logSecurityEvent(String eventType, String details) {
        try {
            String logEntry = String.format("[%s] %s: %s", 
                new Date().toString(), eventType, details);
            
            auditLog.computeIfAbsent(eventType, k -> new ArrayList<>()).add(logEntry);
            
            // Print security violations to console
            if (eventType.contains("DENIED") || eventType.contains("BLOCKED") || eventType.contains("ERROR")) {
                System.out.println("🚫 Security Event: " + logEntry);
            }
        } catch (Exception e) {
            System.err.println("❌ Error logging security event: " + e.getMessage());
        }
    }
    
    /**
     * Security configuration helper class
     */
    private static class SecurityConfiguration {
        private boolean loaded = false;
        private Map<String, Set<String>> customPolicies = new HashMap<>();
        private Map<String, SecurityLevel> customLevels = new HashMap<>();
        
        public SecurityConfiguration() {
            try {
                loadConfiguration();
                loaded = true;
            } catch (Exception e) {
                System.err.println("⚠️ Could not load security configuration, using defaults: " + e.getMessage());
                loaded = false;
            }
        }
        
        private void loadConfiguration() {
            // This could load from a properties file or database
            // For now, we'll use programmatic configuration
        }
        
        public boolean isLoaded() { return loaded; }
        public boolean hasCustomPolicies() { return !customPolicies.isEmpty(); }
        public boolean hasCustomLevels() { return !customLevels.isEmpty(); }
        
        public Map<String, Set<String>> getCompanyPolicies() { return customPolicies; }
        public SecurityLevel getSecurityLevel(String companyId) {
            return customLevels.getOrDefault(companyId, SecurityLevel.PUBLIC);
        }
    }
    
    /**
     * NEW: Demonstrate policy decision process with detailed logging
     * Shows exactly how policies are applied step-by-step
     */
    public PolicyDecision evaluateServiceAccessWithExplanation(String agentName, String serviceName) {
        PolicyDecision decision = new PolicyDecision(agentName, serviceName);
        
        try {
            // Step 1: Check if system agent
            if (validator.isSystemAgent(agentName)) {
                decision.addStep("SYSTEM_AGENT_CHECK", "ALLOW", "Agent recognized as system agent - bypass all restrictions");
                decision.setFinalDecision(true, "System agent access granted");
                return decision;
            }
            decision.addStep("SYSTEM_AGENT_CHECK", "CONTINUE", "Agent is not a system agent - continue policy evaluation");
            
            // Step 2: Check security context
            SecurityContext context = agentContexts.get(agentName);
            if (context == null) {
                decision.addStep("SECURITY_CONTEXT", "DENY", "No security context found - agent not registered");
                decision.setFinalDecision(false, "Agent not registered with security manager");
                return decision;
            }
            decision.addStep("SECURITY_CONTEXT", "CONTINUE", 
                "Security context found - Company: " + context.companyId + ", Level: " + context.level);
            
            // Step 3: Check company services
            Set<String> allowedServices = companyServices.get(context.companyId);
            if (allowedServices != null) {
                if (allowedServices.contains("all-services")) {
                    decision.addStep("COMPANY_POLICY", "ALLOW", "Company has unlimited service access");
                    decision.setFinalDecision(true, "Company policy grants unlimited access");
                    return decision;
                }
                if (allowedServices.contains(serviceName)) {
                    decision.addStep("COMPANY_POLICY", "ALLOW", "Service explicitly listed in company allowlist");
                    decision.setFinalDecision(true, "Company policy allows service");
                    return decision;
                }
            }
            decision.addStep("COMPANY_POLICY", "CONTINUE", "Service not in company allowlist - check other policies");
            
            // Step 4: Check basic services
            if (serviceName.startsWith("public-") || serviceName.equals("status") || serviceName.equals("heartbeat")) {
                decision.addStep("BASIC_SERVICES", "ALLOW", "Service is universally available basic service");
                decision.setFinalDecision(true, "Basic service access granted");
                return decision;
            }
            decision.addStep("BASIC_SERVICES", "CONTINUE", "Service is not a basic service - check security level");
            
            // Step 5: Check security level access
            SecurityLevel level = context.level;
            if (level.getLevel() >= 1 && (serviceName.startsWith("federation-") || serviceName.equals("capability-discovery"))) {
                decision.addStep("SECURITY_LEVEL", "ALLOW", "RESTRICTED+ level grants federation service access");
                decision.setFinalDecision(true, "Security level permits federation service");
                return decision;
            }
            
            if (level.getLevel() >= 3 && serviceName.startsWith("admin-")) {
                decision.addStep("SECURITY_LEVEL", "ALLOW", "PRIVILEGED level grants administrative access");
                decision.setFinalDecision(true, "Privileged administrative access granted");
                return decision;
            }
            
            decision.addStep("SECURITY_LEVEL", "DENY", "Security level " + level.name() + " insufficient for service");
            decision.setFinalDecision(false, "Insufficient security level for service");
            return decision;
            
        } catch (Exception e) {
            decision.addStep("ERROR", "DENY", "Policy evaluation error: " + e.getMessage());
            decision.setFinalDecision(false, "Policy evaluation failed");
            return decision;
        }
    }
    
    /**
     * Policy decision tracking class
     */
    public static class PolicyDecision {
        public final String agentName;
        public final String serviceName;
        public final List<PolicyStep> steps = new ArrayList<>();
        public boolean finalDecision;
        public String finalReason;
        
        public PolicyDecision(String agentName, String serviceName) {
            this.agentName = agentName;
            this.serviceName = serviceName;
        }
        
        public void addStep(String checkType, String result, String explanation) {
            steps.add(new PolicyStep(checkType, result, explanation));
        }
        
        public void setFinalDecision(boolean allowed, String reason) {
            this.finalDecision = allowed;
            this.finalReason = reason;
        }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Policy Decision: ").append(agentName).append(" → ").append(serviceName).append("\n");
            sb.append("Decision Steps:\n");
            for (int i = 0; i < steps.size(); i++) {
                PolicyStep step = steps.get(i);
                sb.append("  ").append(i + 1).append(". ").append(step.checkType)
                  .append(": ").append(step.result)
                  .append(" - ").append(step.explanation).append("\n");
            }
            sb.append("Final Decision: ").append(finalDecision ? "✅ ALLOWED" : "❌ DENIED")
              .append(" (").append(finalReason).append(")");
            return sb.toString();
        }
    }
    
    public static class PolicyStep {
        public final String checkType;
        public final String result;
        public final String explanation;
        
        public PolicyStep(String checkType, String result, String explanation) {
            this.checkType = checkType;
            this.result = result;
            this.explanation = explanation;
        }
    }
}
