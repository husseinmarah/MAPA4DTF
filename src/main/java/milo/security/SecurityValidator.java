package milo.security;

import jade.lang.acl.ACLMessage;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

/**
 * Modular security validator for federation policies
 * Separates validation logic from security manager for better testability
 */
public class SecurityValidator {
    
    private final Set<String> systemAgents;
    private final Set<String> basicServices;
    private final Set<String> federationServices;
    
    public SecurityValidator() {
        // Initialize system agents whitelist
        systemAgents = new HashSet<>(Arrays.asList(
            "ProductionManager", "FAM", "FederationAddressManager", 
            "DF", "AMS", "df", "ams"
        ));
        
        // Initialize basic services available to all
        basicServices = new HashSet<>(Arrays.asList(
            "status", "heartbeat", "registration"
        ));
        
        // Initialize federation-specific services
        federationServices = new HashSet<>(Arrays.asList(
            "capability-discovery", "task-execution", "collaboration",
            "federation-status", "workflow-coordination"
        ));
    }
    
    /**
     * Validate if an agent can access a specific service
     */
    public ValidationResult validateServiceAccess(String agentName, String serviceName, 
                                                 FederationSecurityManager.SecurityContext context, 
                                                 Set<String> companyServices) {
        
        // System agents have unrestricted access
        if (isSystemAgent(agentName)) {
            return ValidationResult.allow("System agent access");
        }
        
        // No security context - deny access
        if (context == null) {
            return ValidationResult.deny("No security context found");
        }
        
        // Check company-specific services first
        if (companyServices != null && (companyServices.contains(serviceName) || companyServices.contains("all-services"))) {
            return ValidationResult.allow("Company policy allows service");
        }
        
        // Check basic services (available to all security levels)
        if (basicServices.contains(serviceName) || serviceName.startsWith("public-")) {
            return ValidationResult.allow("Basic service access");
        }
        
        // Check security level-based access
        return validateSecurityLevelAccess(serviceName, context.level);
    }
    
    /**
     * Validate cross-company communication
     */
    public ValidationResult validateCrossCommunication(FederationSecurityManager.SecurityContext source,
                                                      FederationSecurityManager.SecurityContext target) {
        
        if (source == null || target == null) {
            return ValidationResult.deny("Missing security context");
        }
        
        // Same company - always allowed
        if (source.companyId.equals(target.companyId)) {
            return ValidationResult.allow("Same company communication");
        }
        
        // Federation can communicate with anyone
        if ("Federation".equals(source.companyId) || "Federation".equals(target.companyId)) {
            return ValidationResult.allow("Federation administrative access");
        }
        
        // Both must be at least RESTRICTED level for cross-company communication
        if (source.level.getLevel() < 1 || target.level.getLevel() < 1) {
            return ValidationResult.deny("Insufficient security level for cross-company communication");
        }
        
        // Companies with TRUSTED+ level can communicate with each other
        if (source.level.getLevel() >= 2 && target.level.getLevel() >= 2) {
            return ValidationResult.allow("Trusted cross-company communication");
        }
        
        return ValidationResult.deny("Cross-company communication not authorized");
    }
    
    /**
     * Validate message content and type
     */
    public ValidationResult validateMessage(ACLMessage message) {
        if (message == null) {
            return ValidationResult.deny("Null message");
        }
        
        // Check for suspicious content patterns
        String content = message.getContent();
        if (content != null) {
            if (content.contains("<script>") || content.contains("DROP TABLE") || content.contains("'; --")) {
                return ValidationResult.deny("Suspicious content detected");
            }
            
            // Check message size limits
            if (content.length() > 10000) {
                return ValidationResult.deny("Message too large");
            }
        }
        
        return ValidationResult.allow("Message validation passed");
    }
    
    /**
     * Check if agent is a system agent
     */
    public boolean isSystemAgent(String agentName) {
        if (agentName == null) return false;
        
        return systemAgents.stream().anyMatch(agentName::contains) || 
               systemAgents.contains(agentName);
    }
    
    /**
     * Validate security level-based service access
     */
    private ValidationResult validateSecurityLevelAccess(String serviceName, 
                                                        FederationSecurityManager.SecurityLevel level) {
        
        // Federation services require RESTRICTED level
        if (level.getLevel() >= 1 && federationServices.contains(serviceName)) {
            return ValidationResult.allow("Security level permits federation service");
        }
        
        // Privileged services require PRIVILEGED level
        if (level.getLevel() >= 3 && serviceName.startsWith("admin-")) {
            return ValidationResult.allow("Privileged access granted");
        }
        
        return ValidationResult.deny("Insufficient security level");
    }
    
    /**
     * Validation result wrapper
     */
    public static class ValidationResult {
        public final boolean allowed;
        public final String reason;
        
        private ValidationResult(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }
        
        public static ValidationResult allow(String reason) {
            return new ValidationResult(true, reason);
        }
        
        public static ValidationResult deny(String reason) {
            return new ValidationResult(false, reason);
        }
        
        @Override
        public String toString() {
            return (allowed ? "ALLOW" : "DENY") + ": " + reason;
        }
    }
}
