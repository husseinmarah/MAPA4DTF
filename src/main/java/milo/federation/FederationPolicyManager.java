package milo.federation;

import jade.core.Agent;
import jade.core.AID;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.*;
import jade.domain.AMSService;
import jade.domain.JADEAgentManagement.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.time.LocalDateTime;

/**
 * Federation Policy Manager that leverages JADE's DF and AMS infrastructure
 * to implement and enforce policies between agent federations and containers
 */
public class FederationPolicyManager {
    
    // Policy types
    public enum PolicyType {
        FEDERATION_ACCESS,     // Who can join federations
        SERVICE_SHARING,       // Service discovery/usage policies
        CONTAINER_COMMUNICATION, // Inter-container communication rules
        RESOURCE_ALLOCATION,   // Resource usage limits
        SECURITY_CLEARANCE    // Security level requirements
    }
    
    public enum PolicyAction {
        ALLOW, DENY, REQUIRE_APPROVAL, LOG_AND_ALLOW
    }
    
    // Policy rule structure
    public static class FederationPolicy {
        public String policyId;
        public PolicyType type;
        public String sourcePattern;  // Agent/container pattern (regex)
        public String targetPattern;  // Target pattern (regex)
        public PolicyAction action;
        public Map<String, String> conditions;
        public LocalDateTime createdAt;
        public boolean isActive;
        
        public FederationPolicy(String id, PolicyType type, String source, String target, PolicyAction action) {
            this.policyId = id;
            this.type = type;
            this.sourcePattern = source;
            this.targetPattern = target;
            this.action = action;
            this.conditions = new HashMap<>();
            this.createdAt = LocalDateTime.now();
            this.isActive = true;
        }
    }
    
    // Policy enforcement results
    public static class PolicyDecision {
        public boolean allowed;
        public String reason;
        public List<String> appliedPolicies;
        public boolean requiresAudit;
        
        public PolicyDecision(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
            this.appliedPolicies = new ArrayList<>();
            this.requiresAudit = false;
        }
    }
    
    private final Map<String, FederationPolicy> policies;
    private final Map<String, List<String>> containerAgentMap;
    private final Map<String, Set<String>> federationMembership;
    private final List<String> auditLog;
    private final Agent parentAgent;
    
    public FederationPolicyManager(Agent parentAgent) {
        this.parentAgent = parentAgent;
        this.policies = new ConcurrentHashMap<>();
        this.containerAgentMap = new ConcurrentHashMap<>();
        this.federationMembership = new ConcurrentHashMap<>();
        this.auditLog = new ArrayList<>();
        initializeDefaultPolicies();
    }
    
    /**
     * Initialize default federation policies
     */
    private void initializeDefaultPolicies() {
        // Default policies for federation access
        addPolicy("default-federation-access", PolicyType.FEDERATION_ACCESS, 
                 ".*", ".*", PolicyAction.REQUIRE_APPROVAL);
        
        // Allow same-container agents to communicate freely
        addPolicy("same-container-allow", PolicyType.CONTAINER_COMMUNICATION,
                 "same-container", "same-container", PolicyAction.ALLOW);
        
        // Log all cross-container service discovery
        addPolicy("cross-container-audit", PolicyType.SERVICE_SHARING,
                 ".*", "cross-container", PolicyAction.LOG_AND_ALLOW);
        
        // Security clearance requirements
        addPolicy("security-clearance", PolicyType.SECURITY_CLEARANCE,
                 ".*", ".*", PolicyAction.REQUIRE_APPROVAL);
    }
    
    /**
     * Add a new federation policy
     */
    public void addPolicy(String id, PolicyType type, String source, String target, PolicyAction action) {
        FederationPolicy policy = new FederationPolicy(id, type, source, target, action);
        policies.put(id, policy);
        logAuditEvent("POLICY_ADDED", "Policy " + id + " added: " + type + " " + action);
    }
    
    /**
     * Check federation join policy using AMS information
     */
    public PolicyDecision checkFederationJoinPolicy(AID requestingAgent, String federationId) {
        try {
            // Get agent information from AMS
            AMSAgentDescription[] agents = AMSService.search(parentAgent, new AMSAgentDescription());
            String agentContainer = getAgentContainer(requestingAgent, agents);
            
            // Apply federation access policies
            for (FederationPolicy policy : policies.values()) {
                if (policy.type == PolicyType.FEDERATION_ACCESS && policy.isActive) {
                    if (matchesPattern(requestingAgent.getLocalName(), policy.sourcePattern)) {
                        PolicyDecision decision = applyPolicy(policy, requestingAgent, federationId, agentContainer);
                        if (!decision.allowed) {
                            return decision;
                        }
                    }
                }
            }
            
            return new PolicyDecision(true, "Federation join approved");
            
        } catch (FIPAException e) {
            logAuditEvent("POLICY_ERROR", "Failed to check federation join policy: " + e.getMessage());
            return new PolicyDecision(false, "Policy check failed: " + e.getMessage());
        }
    }
    
    /**
     * Check service discovery policy using DF information
     */
    public PolicyDecision checkServiceDiscoveryPolicy(AID requestingAgent, DFAgentDescription template) {
        try {
            // Get requesting agent's container
            AMSAgentDescription[] agents = AMSService.search(parentAgent, new AMSAgentDescription());
            String requestingContainer = getAgentContainer(requestingAgent, agents);
            
            // Search for matching services in DF
            DFAgentDescription[] results = DFService.search(parentAgent, template);
            
            for (DFAgentDescription service : results) {
                String serviceContainer = getAgentContainer(service.getName(), agents);
                
                // Check if cross-container access
                boolean crossContainer = !requestingContainer.equals(serviceContainer);
                
                // Apply service sharing policies
                for (FederationPolicy policy : policies.values()) {
                    if (policy.type == PolicyType.SERVICE_SHARING && policy.isActive) {
                        String targetPattern = crossContainer ? "cross-container" : "same-container";
                        
                        if (matchesPattern(requestingAgent.getLocalName(), policy.sourcePattern) &&
                            matchesPattern(targetPattern, policy.targetPattern)) {
                            
                            PolicyDecision decision = applyPolicy(policy, requestingAgent, 
                                                                service.getName().getLocalName(), requestingContainer);
                            decision.appliedPolicies.add(policy.policyId);
                            
                            if (policy.action == PolicyAction.LOG_AND_ALLOW) {
                                logAuditEvent("SERVICE_ACCESS", 
                                    requestingAgent.getLocalName() + " accessed service from " + serviceContainer);
                            }
                        }
                    }
                }
            }
            
            return new PolicyDecision(true, "Service discovery approved");
            
        } catch (FIPAException e) {
            logAuditEvent("POLICY_ERROR", "Failed to check service discovery policy: " + e.getMessage());
            return new PolicyDecision(false, "Service discovery policy check failed");
        }
    }
    
    /**
     * Check container communication policy
     */
    public PolicyDecision checkContainerCommunicationPolicy(AID sender, AID receiver) {
        try {
            AMSAgentDescription[] agents = AMSService.search(parentAgent, new AMSAgentDescription());
            String senderContainer = getAgentContainer(sender, agents);
            String receiverContainer = getAgentContainer(receiver, agents);
            
            boolean sameContainer = senderContainer.equals(receiverContainer);
            
            for (FederationPolicy policy : policies.values()) {
                if (policy.type == PolicyType.CONTAINER_COMMUNICATION && policy.isActive) {
                    String pattern = sameContainer ? "same-container" : "cross-container";
                    
                    if (matchesPattern(pattern, policy.sourcePattern)) {
                        PolicyDecision decision = applyPolicy(policy, sender, receiver.getLocalName(), senderContainer);
                        decision.appliedPolicies.add(policy.policyId);
                        return decision;
                    }
                }
            }
            
            return new PolicyDecision(true, "Communication approved");
            
        } catch (FIPAException e) {
            return new PolicyDecision(false, "Container communication policy check failed");
        }
    }
    
    /**
     * Apply a specific policy
     */
    private PolicyDecision applyPolicy(FederationPolicy policy, AID agent, String target, String context) {
        PolicyDecision decision = new PolicyDecision(true, "Policy applied: " + policy.policyId);
        
        switch (policy.action) {
            case ALLOW:
                decision.allowed = true;
                decision.reason = "Policy " + policy.policyId + " allows action";
                break;
                
            case DENY:
                decision.allowed = false;
                decision.reason = "Policy " + policy.policyId + " denies action";
                break;
                
            case REQUIRE_APPROVAL:
                decision.allowed = checkApproval(agent, target, policy);
                decision.reason = decision.allowed ? "Approval granted" : "Approval required";
                break;
                
            case LOG_AND_ALLOW:
                decision.allowed = true;
                decision.requiresAudit = true;
                decision.reason = "Action allowed with audit logging";
                break;
        }
        
        return decision;
    }
    
    /**
     * Check if approval exists for the action (simplified implementation)
     */
    private boolean checkApproval(AID agent, String target, FederationPolicy policy) {
        // In a real implementation, this would check an approval database
        // For now, we'll simulate approval for demonstration
        String approvalKey = policy.policyId + ":" + agent.getLocalName() + ":" + target;
        
        // Simulate that agents with "Manager" in their name have blanket approval
        if (agent.getLocalName().contains("Manager") || agent.getLocalName().contains("FAM")) {
            logAuditEvent("AUTO_APPROVAL", "Auto-approved for " + agent.getLocalName());
            return true;
        }
        
        // Otherwise require manual approval (would be handled externally)
        logAuditEvent("APPROVAL_REQUIRED", "Manual approval needed for " + approvalKey);
        return false;
    }
    
    /**
     * Get the container name for an agent using AMS information
     */
    private String getAgentContainer(AID agent, AMSAgentDescription[] agents) {
        for (AMSAgentDescription desc : agents) {
            if (desc.getName().equals(agent)) {
                // Extract container from agent's addresses
                String[] addresses = desc.getName().getAddressesArray();
                if (addresses.length > 0) {
                    // Parse container from address format
                    return parseContainerFromAddress(addresses[0]);
                }
            }
        }
        return "unknown-container";
    }
    
    /**
     * Parse container name from agent address
     */
    private String parseContainerFromAddress(String address) {
        // JADE addresses typically format: protocol://host:port/ACC or similar
        if (address.contains("/")) {
            String[] parts = address.split("/");
            return parts[parts.length - 1]; // Last part is usually container identifier
        }
        return "Main-Container";
    }
    
    /**
     * Check if a string matches a pattern (supports regex)
     */
    private boolean matchesPattern(String value, String pattern) {
        if (pattern.equals(".*")) return true;
        return value.matches(pattern);
    }
    
    /**
     * Log audit events
     */
    private void logAuditEvent(String eventType, String description) {
        String timestamp = LocalDateTime.now().toString();
        String logEntry = timestamp + " [" + eventType + "] " + description;
        auditLog.add(logEntry);
        System.out.println("🔍 POLICY AUDIT: " + logEntry);
        
        // Keep audit log size manageable
        if (auditLog.size() > 1000) {
            auditLog.remove(0);
        }
    }
    
    /**
     * Get current policy statistics
     */
    public Map<String, Object> getPolicyStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total-policies", policies.size());
        stats.put("active-policies", policies.values().stream().mapToInt(p -> p.isActive ? 1 : 0).sum());
        stats.put("audit-events", auditLog.size());
        
        // Count policies by type
        Map<PolicyType, Long> typeCount = new HashMap<>();
        for (PolicyType type : PolicyType.values()) {
            long count = policies.values().stream().filter(p -> p.type == type).count();
            typeCount.put(type, count);
        }
        stats.put("policy-distribution", typeCount);
        
        return stats;
    }
    
    /**
     * Get recent audit log entries
     */
    public List<String> getRecentAuditLog(int count) {
        int start = Math.max(0, auditLog.size() - count);
        return new ArrayList<>(auditLog.subList(start, auditLog.size()));
    }
    
    /**
     * Enable/disable a policy
     */
    public void setPolicyActive(String policyId, boolean active) {
        FederationPolicy policy = policies.get(policyId);
        if (policy != null) {
            policy.isActive = active;
            logAuditEvent("POLICY_MODIFIED", "Policy " + policyId + " set to " + (active ? "active" : "inactive"));
        }
    }
    
    /**
     * Remove a policy
     */
    public void removePolicy(String policyId) {
        FederationPolicy removed = policies.remove(policyId);
        if (removed != null) {
            logAuditEvent("POLICY_REMOVED", "Policy " + policyId + " removed");
        }
    }
}
