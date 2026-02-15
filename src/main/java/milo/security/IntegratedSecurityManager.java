package milo.security;

import jade.core.Agent;
import jade.lang.acl.ACLMessage;
import jade.wrapper.ControllerException;

import java.util.Map;

/**
 * Integrated Security Manager
 * 
 * Unified facade for OPA, Keycloak, and local security management.
 * Provides a single point of entry for all security operations in the
 * federation.
 */
public class IntegratedSecurityManager {

    private final FederationSecurityManager securityManager;
    private final Agent agent;
    private final milo.eval.MetricsLogService metrics;

    /**
     * Create integrated security manager for an agent
     * 
     * @param agent The JADE agent using this security manager
     */
    public IntegratedSecurityManager(Agent agent) {
        this.agent = agent;
        this.securityManager = FederationSecurityManager.getInstance();
        this.metrics = milo.eval.MetricsLogService.getInstance();

        System.out.println("🔐 IntegratedSecurityManager initialized for: " + agent.getLocalName());
        printServiceStatus();
    }

    /**
     * Authenticate agent with Keycloak (if available) or local authentication
     * 
     * @param username Agent username
     * @param password Agent password
     * @return SecurityContext if authentication successful, null otherwise
     */
    public FederationSecurityManager.SecurityContext authenticate(String username, String password)
            throws ControllerException {
        System.out.println("🔐 Authenticating agent: " + username);

        // Try Keycloak authentication first
        FederationSecurityManager.SecurityContext context = securityManager.authenticateWithKeycloak(username,
                password);

        if (context != null) {
            System.out.println("✅ Authenticated via Keycloak: " + username);
            return context;
        }

        // Fallback to local authentication
        System.out.println("⚠️ Keycloak auth failed, using local authentication");
        return securityManager.registerSecureAgent(username, "local",
                agent.getContainerController().getContainerName());
    }

    /**
     * Validate message between agents using OPA (if available) or local policies
     * 
     * @param message     The ACL message to validate
     * @param sourceAgent Source agent name
     * @param targetAgent Target agent name
     * @return true if message is allowed
     */
    public boolean validateMessage(ACLMessage message, String sourceAgent, String targetAgent) {
        // Refresh token if needed
        securityManager.refreshTokenIfNeeded(sourceAgent);

        long start = System.nanoTime();
        // Use OPA if available, otherwise local validation
        boolean allowed = securityManager.validateMessageWithOPA(message, sourceAgent, targetAgent);
        long duration = System.nanoTime() - start;

        metrics.logLatency("opa_enforcement_log.csv", "OPA_ENFORCE", duration,
                "source=" + sourceAgent + ",target=" + targetAgent + ",allowed=" + allowed);

        return allowed;
    }

    /**
     * Check if agent can access a service
     * 
     * @param agentName   Agent name
     * @param serviceName Service name
     * @return true if access is allowed
     */
    public boolean canAccessService(String agentName, String serviceName) {
        return securityManager.canAccessService(agentName, serviceName);
    }

    /**
     * Get security context for an agent
     * 
     * @param agentName Agent name
     * @return SecurityContext or null if not found
     */
    public FederationSecurityManager.SecurityContext getContext(String agentName) {
        return securityManager.getAgentContext(agentName);
    }

    /**
     * Get security statistics
     * 
     * @return Map of security statistics
     */
    public Map<String, Object> getSecurityStats() {
        return securityManager.getSecurityStats();
    }

    /**
     * Get service status (OPA, Keycloak)
     * 
     * @return Map of service availability
     */
    public Map<String, Boolean> getServiceStatus() {
        return securityManager.getServiceStatus();
    }

    /**
     * Print service status to console
     */
    public void printServiceStatus() {
        Map<String, Boolean> status = getServiceStatus();
        System.out.println("📊 Security Service Status:");
        System.out.println("   OPA Enabled: " + status.get("opa-enabled"));
        System.out.println("   OPA Available: " + status.get("opa-available"));
        System.out.println("   Keycloak Enabled: " + status.get("keycloak-enabled"));
        System.out.println("   Keycloak Available: " + status.get("keycloak-available"));
    }

    /**
     * Get audit log for security review
     * 
     * @return Map of audit events
     */
    public Map<String, java.util.List<String>> getAuditLog() {
        return securityManager.getAuditLog();
    }
}
