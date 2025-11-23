package milo.security;

import jade.lang.acl.ACLMessage;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import milo.federation.FederationHelper;

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
    private final OPAClient opaClient;
    private final KeycloakClient keycloakClient;
    
    // Security state management
    private final Map<String, SecurityContext> agentContexts = new ConcurrentHashMap<>();
    private final Map<String, KeycloakClient.AuthToken> agentTokens = new ConcurrentHashMap<>();
    private final Map<String, List<String>> auditLog = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> companyServices = new ConcurrentHashMap<>();
    
    // Configuration
    private final SecurityConfiguration config;
    private boolean opaEnabled = false;
    private boolean keycloakEnabled = false;
    
    /**
     * Private constructor for singleton pattern
     */
    private FederationSecurityManager() {
        System.out.println("┌─ SECURITY SYSTEM INITIALIZATION ─────────────────");
        validator = new SecurityValidator();
        config = new SecurityConfiguration();
        // Initialize OPA client
        opaClient = new OPAClient();
        opaEnabled = opaClient.isAvailable();
        if (opaEnabled) {
            System.out.println("│  ✓ OPA (Policy Agent): ENABLED");
        } else {
            System.out.println("│  ⚠ OPA (Policy Agent): DISABLED");
        }
        
        // Initialize Keycloak client
        keycloakClient = new KeycloakClient();
        keycloakEnabled = keycloakClient.isAvailable();
        if (keycloakEnabled) {
            System.out.println("│  ✓ Keycloak IAM: ENABLED");
        } else {
            System.out.println("│  ⚠ Keycloak IAM: DISABLED");
        }
        
        initializeCompanyPolicies();
        System.out.println("│  ✓ Company policies loaded");
        System.out.println("│  ✓ Security Manager Ready");
        System.out.println("└──────────────────────────────────────────────────");
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
     * Link an agent's local name to an existing authenticated context
     * This is used when the agent's local name differs from the Keycloak username
     * 
     * @param localName The agent's actual local name (e.g., "RobotAgent1")
     * @param authenticatedName The Keycloak username (e.g., "RobotAgent")
     * @return true if linking was successful
     */
    public boolean linkAgentToContext(String localName, String authenticatedName) {
        SecurityContext context = agentContexts.get(authenticatedName);
        if (context != null) {
            // Register the local name with the same context
            agentContexts.put(localName, context);
            // Also link the token - use the SAME token object (not a copy) so updates are shared
            KeycloakClient.AuthToken token = agentTokens.get(authenticatedName);
            if (token != null) {
                agentTokens.put(localName, token);
                System.out.println("🔗 Linked " + localName + " to context of " + authenticatedName + " (shared token)");
            }
            return true;
        }
        return false;
    }
    
    /**
     * Check if an agent can access a specific service
     * For robot operations and conveyor access, also validates with OPA policy
     */
    public boolean canAccessService(String agentName, String serviceName) {
        try {
            SecurityContext context = agentContexts.get(agentName);
            
            if (context == null) {
                logSecurityEvent("ACCESS_DENIED", agentName + " -> " + serviceName + " (no security context)");
                return false;
            }
            
            // For robot operations and conveyor access, use OPA if available
            if (opaEnabled && (serviceName.equals("robot_operation") || serviceName.equals("conveyor_access"))) {
                return validateServiceAccessWithOPA(agentName, context, serviceName);
            }
            
            // For other services, use local validation
            Set<String> allowedServices = companyServices.get(context.companyId);
            
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
     * Validate service access with OPA (for robot operations and conveyor access)
     * Checks if the agent is authorized to access the service based on policy
     */
    private boolean validateServiceAccessWithOPA(String agentName, SecurityContext context, String serviceName) {
        try {
            // Get user attributes from Keycloak token
            KeycloakClient.AuthToken token = agentTokens.get(agentName);
            
            if (token == null || token.isExpired()) {
                logSecurityEvent("OPA_SERVICE_DENIED", agentName + " -> " + serviceName + " (no valid token)");
                return false;
            }
            
            KeycloakClient.UserAttributes attrs = token.userAttributes;
            
            // Evaluate service access policy with OPA
            OPAClient.PolicyDecision decision = opaClient.evaluateCommunicationPolicy(
                agentName, attrs.org, attrs.role, attrs.trustScore, attrs.status,
                serviceName + "Service", "main", "service", 1.0, "active", serviceName
            );
            
            // Print detailed OPA policy
            System.out.println("┌─ OPA POLICY EVALUATION ──────────────────────────");
            System.out.println("│  " + (decision.allowed ? "✅ ALLOWED" : "❌ DENIED"));
            System.out.println("│  Time:        " + java.time.Instant.now());
            System.out.println("│  From:        " + agentName + " (" + attrs.org + ")");
            System.out.println("│  To:          " + serviceName + "Service");
            System.out.println("│  Action:      " + serviceName);
            System.out.println("│  Role:        " + attrs.role);
            System.out.println("│  Trust Score: " + attrs.trustScore);
            System.out.println("│  Status:      " + attrs.status);
            System.out.println("│  Reason:      " + decision.reason);
            System.out.println("└──────────────────────────────────────────────────");
            
            if (decision.allowed) {
                logSecurityEvent("OPA_SERVICE_ALLOWED", agentName + " -> " + serviceName + " (authorized)");
            } else {
                logSecurityEvent("OPA_SERVICE_DENIED", agentName + " -> " + serviceName + " (" + decision.reason + ")");
            }
            
            return decision.allowed;
            
        } catch (Exception e) {
            logSecurityEvent("OPA_SERVICE_ERROR", agentName + " -> " + serviceName + " - " + e.getMessage());
            System.err.println("❌ Error during OPA service validation: " + e.getMessage());
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
     * Check if two agents can communicate with each other
     * Uses OPA policy to validate peer-to-peer communication
     * 
     * @param sourceAgent Source agent name
     * @param targetAgent Target agent name
     * @return true if OPA allows communication between the agents
     */
    public boolean canCommunicateWith(String sourceAgent, String targetAgent) {
        try {
            // Whitelist for system/infrastructure agents
            if (validator.isSystemAgent(sourceAgent) || validator.isSystemAgent(targetAgent)) {
                return true; // Allow system agents to communicate freely
            }
            
            // If OPA is enabled, check with OPA policy
            if (opaEnabled) {
                // Get tokens for both agents
                KeycloakClient.AuthToken sourceToken = agentTokens.get(sourceAgent);
                KeycloakClient.AuthToken targetToken = agentTokens.get(targetAgent);
                
                // If either token is missing or expired, deny communication
                if (sourceToken == null || sourceToken.isExpired() || 
                    targetToken == null || targetToken.isExpired()) {
                    logSecurityEvent("PEER_COMM_DENIED", sourceAgent + " -> " + targetAgent + " (Invalid/expired tokens)");
                    return false;
                }
                
                // Get user attributes
                KeycloakClient.UserAttributes sourceAttrs = sourceToken.userAttributes;
                KeycloakClient.UserAttributes targetAttrs = targetToken.userAttributes;
                
                // Determine the correct action based on agent types
                String action = determineActionType(sourceAgent, targetAgent);
                
                // Evaluate peer communication policy with OPA
                OPAClient.PolicyDecision decision = opaClient.evaluateCommunicationPolicy(
                    sourceAgent, sourceAttrs.org, sourceAttrs.role, sourceAttrs.trustScore, sourceAttrs.status,
                    targetAgent, targetAttrs.org, targetAttrs.role, targetAttrs.trustScore, targetAttrs.status, action
                );
                
                // Print detailed OPA policy evaluation box
                System.out.println("┌─ OPA POLICY EVALUATION ──────────────────────────");
                System.out.println("│  " + (decision.allowed ? "✅ ALLOWED" : "❌ DENIED"));
                System.out.println("│  Time:        " + java.time.Instant.now());
                System.out.println("│  From:        " + sourceAgent + " (" + sourceAttrs.org + ")");
                System.out.println("│  To:          " + targetAgent + " (" + targetAttrs.org + ")");
                System.out.println("│  Action:      " + action);
                System.out.println("│  Role:        " + sourceAttrs.role);
                System.out.println("│  Trust Score: " + sourceAttrs.trustScore);
                System.out.println("│  Reason:      " + decision.reason);
                System.out.println("└──────────────────────────────────────────────────");
                
                if (decision.allowed) {
                    logSecurityEvent("PEER_COMM_ALLOWED", sourceAgent + " -> " + targetAgent + " (OPA authorized)");
                } else {
                    logSecurityEvent("PEER_COMM_DENIED", sourceAgent + " -> " + targetAgent + " (" + decision.reason + ")");
                }
                
                return decision.allowed;
            }
            
            // If OPA not enabled, use local validation
            SecurityContext sourceContext = agentContexts.get(sourceAgent);
            SecurityContext targetContext = agentContexts.get(targetAgent);
            
            if (sourceContext == null || targetContext == null) {
                return false; // No context, deny communication
            }
            
            SecurityValidator.ValidationResult result = validator.validateCrossCommunication(
                sourceContext, targetContext);
            
            return result.allowed;
            
        } catch (Exception e) {
            logSecurityEvent("PEER_COMM_ERROR", sourceAgent + " -> " + targetAgent + " - " + e.getMessage());
            System.err.println("❌ Error checking peer communication: " + e.getMessage());
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
     * Determine the OPA action type based on agent types
     * RobotAgent → ConveyorAgent = "conveyor_access"
     * ConveyorAgent → RobotAgent = "peer_coordination"
     * Other combinations = "peer_coordination"
     */
    private String determineActionType(String sourceAgent, String targetAgent) {
        // Robot accessing conveyor (checking production status, pickup)
        if (sourceAgent.startsWith("RobotAgent") && targetAgent.startsWith("ConveyorAgent")) {
            return "conveyor_access";
        }
        // Conveyor notifying robot (push notifications)
        if (sourceAgent.startsWith("ConveyorAgent") && targetAgent.startsWith("RobotAgent")) {
            return "peer_coordination";
        }
        // Default: peer coordination (robot-to-robot, etc.)
        return "peer_coordination";
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
     * Authenticate agent with Keycloak and retrieve user attributes
     * 
     * @param agentName Agent username
     * @param password Agent password
     * @return SecurityContext with Keycloak attributes or null if authentication failed
     */
    public SecurityContext authenticateWithKeycloak(String agentName, String password) {
        if (!keycloakEnabled) {
            System.err.println("⚠️ Keycloak is not available, falling back to local authentication");
            return registerSecureAgent(agentName, "local", "default-container");
        }
        
        try {
            System.out.println("🔐 Authenticating " + agentName + " with Keycloak...");
            
            // Authenticate with Keycloak
            KeycloakClient.AuthToken token = keycloakClient.authenticate(agentName, password);
            if (token == null) {
                logSecurityEvent("KEYCLOAK_AUTH_FAILED", agentName);
                System.err.println("❌ Keycloak authentication failed for: " + agentName);
                return null;
            }
            
            // Store token for future use
            agentTokens.put(agentName, token);
            
            // Extract user attributes
            KeycloakClient.UserAttributes attrs = token.userAttributes;
            
            // Map Keycloak role to SecurityLevel
            SecurityLevel level = mapRoleToSecurityLevel(attrs.role);
            
            // Create security context
            SecurityContext context = new SecurityContext(
                agentName, attrs.org, "keycloak-managed", level
            );
            
            agentContexts.put(agentName, context);
            logSecurityEvent("KEYCLOAK_AUTH_SUCCESS", 
                agentName + " | org:" + attrs.org + " | role:" + attrs.role + " | trust:" + attrs.trustScore);
            
            System.out.println("┌─ SECURITY CONTEXT CREATED ───────────────────────");
            System.out.println("│  Agent:         " + agentName);
            System.out.println("│  Organization:  " + attrs.org);
            System.out.println("│  Role:          " + attrs.role);
            System.out.println("│  Trust Score:   " + attrs.trustScore);
            System.out.println("│  Security Level: " + level);
            System.out.println("└──────────────────────────────────────────────────");
            
            return context;
            
        } catch (Exception e) {
            logSecurityEvent("KEYCLOAK_AUTH_ERROR", agentName + " - " + e.getMessage());
            System.err.println("┌─ SECURITY CONTEXT CREATION ──────────────────────");
            System.err.println("│  ❌ FAILED");
            System.err.println("│  Agent: " + agentName);
            System.err.println("│  Error: " + e.getMessage());
            System.err.println("└──────────────────────────────────────────────────");
            return null;
        }
    }
    
    /**
     * Validate message communication using OPA policy engine
     * 
     * @param message ACL message to validate
     * @param sourceAgent Source agent name
     * @param targetAgent Target agent name
     * @return true if OPA allows the communication
     */
    public boolean validateMessageWithOPA(ACLMessage message, String sourceAgent, String targetAgent) {
        // If OPA is not enabled, use local validation only
        if (!opaEnabled) {
            return validateMessage(message, sourceAgent, targetAgent);
        }
        
        // When OPA is enabled, skip local cross-company checks - let OPA be authoritative
        // Only do basic message content validation
        SecurityValidator.ValidationResult messageResult = validator.validateMessage(message);
        if (!messageResult.allowed) {
            logSecurityEvent("MSG_BLOCKED", sourceAgent + " -> " + targetAgent + " (" + messageResult.reason + ")");
            return false;
        }
        
        try {
            // Get security contexts
            SecurityContext sourceContext = agentContexts.get(sourceAgent);
            SecurityContext targetContext = agentContexts.get(targetAgent);

            // NEW: Get FFAs for policy evaluation
            String sourceFFA = FederationHelper.getAgentFFA(sourceAgent);
            String targetFFA = FederationHelper.getAgentFFA(targetAgent);
            
            if (sourceContext == null || targetContext == null) {
                System.err.println("⚠️ Missing security context for OPA evaluation");
                return false; // Deny if context is missing
            }
            
            

            // Get user attributes from Keycloak token if available
            double sourceTrustScore = 0.5;
            String sourceRole = "worker";
            String sourceStatus = "active";
            
            KeycloakClient.AuthToken sourceToken = agentTokens.get(sourceAgent);
            if (sourceToken != null && !sourceToken.isExpired()) {
                sourceTrustScore = sourceToken.userAttributes.trustScore;
                sourceRole = sourceToken.userAttributes.role;
                sourceStatus = sourceToken.userAttributes.status;
            }
            
            // Get target attributes (including role and trust score)
            double targetTrustScore = 0.5;
            String targetRole = "worker";
            String targetStatus = "active";
            KeycloakClient.AuthToken targetToken = agentTokens.get(targetAgent);
            if (targetToken != null && !targetToken.isExpired()) {
                targetTrustScore = targetToken.userAttributes.trustScore;
                targetRole = targetToken.userAttributes.role;
                targetStatus = targetToken.userAttributes.status;
            }
            
            // Evaluate policy with OPA
            OPAClient.PolicyDecision decision = opaClient.evaluateCommunicationPolicy(
                sourceAgent, sourceContext.companyId, sourceRole, sourceTrustScore, sourceStatus,
                targetAgent, targetContext.companyId, targetRole, targetTrustScore, targetStatus, "send"
            );
            
            // Print detailed OPA policy evaluation box
            System.out.println("┌─ OPA POLICY EVALUATION ──────────────────────────");
            System.out.println("│  " + (decision.allowed ? "✅ ALLOWED" : "❌ DENIED"));
            System.out.println("│  --- SOURCE ---");
            System.out.println("│  Time:        " + java.time.Instant.now());
            System.out.println("│  From:        " + sourceAgent + " (" + sourceContext.companyId + ")");
            System.out.println("│  To:          " + targetAgent + " (" + targetContext.companyId + ")");
            System.out.println("│  Action:      send (message communication)");
            System.out.println("│  Role:        " + sourceRole);
            System.out.println("│  Trust Score: " + sourceTrustScore);
            System.out.println("│  Status:      " + sourceStatus);
            System.out.println("│  Source FFA:  " + sourceFFA);
            System.out.println("│  --- TARGET ---");
            System.out.println("│  Target FFA:  " + targetFFA);
            System.out.println("│  Reason:      " + decision.reason);
            System.out.println("└──────────────────────────────────────────────────");
            
            if (!decision.allowed) {
                logSecurityEvent("OPA_DENIED", sourceAgent + " -> " + targetAgent + " (" + decision.reason + ")");
            } else {
                logSecurityEvent("OPA_ALLOWED", sourceAgent + " -> " + targetAgent);
            }
            
            return decision.allowed;
            
        } catch (Exception e) {
            logSecurityEvent("OPA_ERROR", sourceAgent + " -> " + targetAgent + " - " + e.getMessage());
            System.err.println("❌ Error during OPA policy evaluation: " + e.getMessage());
            return false; // Fail secure on error
        }
    }
    
    /**
     * Check if agent has a valid authentication token
     * 
     * @param agentName Agent name
     * @return true if agent has valid token
     */
    public boolean hasValidToken(String agentName) {
        if (!keycloakEnabled) {
            return true; // No token required if Keycloak is disabled
        }
        
        KeycloakClient.AuthToken token = agentTokens.get(agentName);
        boolean hasToken = token != null && !token.isExpired();
        
        // Debug logging - show specific agent token info
//        System.out.println("┌─ TOKEN VALIDATION ────────────────────────────────");
//        System.out.println("│  Agent: " + agentName);
//        System.out.println("│  Has Token: " + hasToken);
//        if (token != null) {
//            System.out.println("│  Token Expired: " + token.isExpired());
//            System.out.println("│  Token Refresh Needed: " + token.needsRefresh());
//        } else {
//            System.out.println("│  Token: null");
//        }
//        System.out.println("└───────────────────────────────────────────────────");

        return hasToken;
    }
    
    /**
     * Check if agent's token needs refresh and refresh if necessary
     * 
     * @param agentName Agent name
     * @return true if token is valid (or successfully refreshed)
     */
    public boolean refreshTokenIfNeeded(String agentName) {
        if (!keycloakEnabled) {
            return true; // No token management if Keycloak is disabled
        }
        
        KeycloakClient.AuthToken token = agentTokens.get(agentName);
        if (token == null) {
            System.err.println("⚠️ No token found for " + agentName);
            return false;
        }
        
        if (token.isExpired()) {
            System.err.println("⚠️ Token expired for " + agentName + " - cannot refresh expired token");
            return false;
        }
        
        if (token.needsRefresh()) {
            System.out.println("🔄 Refreshing token for " + agentName + "...");
            KeycloakClient.AuthToken newToken = keycloakClient.refreshToken(token.refreshToken);
            
            if (newToken != null) {
                // Update token for THIS agent name
                agentTokens.put(agentName, newToken);
                
                // IMPORTANT: Also update token for all aliases that share this token
                // This ensures linked agents (localName vs authenticatedName) stay in sync
                String currentTokenId = token.accessToken; // Use as identifier
                for (Map.Entry<String, KeycloakClient.AuthToken> entry : agentTokens.entrySet()) {
                    if (entry.getValue().accessToken.equals(currentTokenId)) {
                        agentTokens.put(entry.getKey(), newToken);
                        System.out.println("   ↳ Also updated token for linked agent: " + entry.getKey());
                    }
                }
                
                logSecurityEvent("TOKEN_REFRESHED", agentName);
                System.out.println("✅ Token refreshed successfully for " + agentName);
                return true;
            } else {
                System.err.println("❌ Failed to refresh token for " + agentName);
                logSecurityEvent("TOKEN_REFRESH_FAILED", agentName);
                return false;
            }
        }
        
        return true; // Token is valid and doesn't need refresh
    }
    
    /**
     * Map Keycloak role to SecurityLevel
     */
    private SecurityLevel mapRoleToSecurityLevel(String role) {
        if (role == null) return SecurityLevel.RESTRICTED;
        
        switch (role.toLowerCase()) {
            case "federation_manager":
            case "admin":
                return SecurityLevel.PRIVILEGED;
            case "manager":
            case "trusted":
                return SecurityLevel.TRUSTED;
            case "worker":
                return SecurityLevel.RESTRICTED;
            default:
                return SecurityLevel.PUBLIC;
        }
    }
    
    /**
     * Check OPA and Keycloak service status
     */
    public Map<String, Boolean> getServiceStatus() {
        Map<String, Boolean> status = new HashMap<>();
        status.put("opa-enabled", opaEnabled);
        status.put("opa-available", opaClient.isAvailable());
        status.put("keycloak-enabled", keycloakEnabled);
        status.put("keycloak-available", keycloakClient.isAvailable());
        return status;
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
