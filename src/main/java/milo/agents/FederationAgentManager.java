package milo.agents;

import milo.federation.FAPProtocol;
import milo.federation.FederatedDirectoryService;
import milo.federation.FederationWorkflowOrchestrator;
import milo.federation.FederationHealthMonitor;
import milo.federation.FederationPolicyManager; // NEW
import milo.security.FederationSecurityManager;
import milo.security.FederationSecurityManager.SecurityContext; // NEW: Keycloak context
import milo.utils.SL;
import milo.utils.ACLUtil;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.*;
import java.util.List;

/**
 * Implements FAP-ALLOC, FAP-RESOLVE, FAP-UPDATE (lease) using FIPA-Request
 * Enhanced with federation policy enforcement using JADE's DF and AMS infrastructure
 */
public class FederationAgentManager extends Agent {

    private FAPProtocol fapProtocol;
    private FederatedDirectoryService federatedDirectory;
    private FederationWorkflowOrchestrator workflowOrchestrator;
    private FederationHealthMonitor healthMonitor;
    private FederationSecurityManager securityManager;
    private FederationPolicyManager policyManager; // NEW: Policy enforcement
    private SecurityContext securityContext; // Keycloak authentication context

    @Override
    protected void setup() {
        System.out.println("🏗️ " + getLocalName() + " starting (Federation Address Manager)");
        
        // STEP 1: Authenticate with Keycloak
        System.out.println("🔐 Authenticating with Keycloak...");
        securityManager = FederationSecurityManager.getInstance();
        securityContext = securityManager.authenticateWithKeycloak("FederationAgentManager", "federation");
        
        if (securityContext == null) {
            System.err.println("❌ Keycloak authentication failed for FederationAgentManager");
            System.err.println("⚠️ Falling back to local authentication");
            securityManager.registerSecureAgent(getLocalName(), "main", "Main-Container");
        } else {
            System.out.println("✅ Authenticated as: " + securityContext.agentName);
            System.out.println("   Organization: " + securityContext.companyId);
            System.out.println("   Security Level: " + securityContext.level);
        }
        
        // STEP 2: Initialize federation components
        fapProtocol = new FAPProtocol();
        federatedDirectory = new FederatedDirectoryService();
        workflowOrchestrator = new FederationWorkflowOrchestrator();
        healthMonitor = new FederationHealthMonitor(fapProtocol);
        
        // STEP 3: Initialize policy manager
        policyManager = new FederationPolicyManager(this);
        System.out.println("🛡️ Federation Policy Manager initialized");
        
        // Register FAM itself with DF
        registerWithDirectoryFacilitator();
        
        System.out.println("📋 FAM Services:");
        System.out.println("   • FAP-ALLOC: Federation address allocation");
        System.out.println("   • FAP-RESOLVE: Address resolution (FFA → AID)");
        System.out.println("   • FAP-UPDATE: Lease renewal and management");
        System.out.println("   • SERVICE-SEARCH: Enhanced FFA-based service discovery");
        System.out.println("   • SERVICE-REGISTER: Federated service registration");
        System.out.println("   • SECURITY-STATUS: Security policy monitoring");
        System.out.println("   • WORKFLOW-INIT: Federation workflow orchestration");
        System.out.println("   • WORKFLOW-STATUS: Workflow monitoring and status");
        System.out.println("   • HEALTH-METRICS: Federation health monitoring");
        // NEW: Add policy services
        System.out.println("   • POLICY-CHECK: Federation policy validation");
        System.out.println("   • POLICY-AUDIT: Policy compliance auditing");
        System.out.println("   • Automatic lease cleanup (60s intervals)");
        System.out.println("🎯 FAM ready to serve enhanced federation services with policy enforcement");
        
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive();
                if (msg == null) {
                    block();
                    return;
                }
                String c = msg.getContent();
                String senderName = msg.getSender().getLocalName();
                
                // SECURITY: Validate message with OPA policy
                String authenticatedName = securityContext != null ? securityContext.agentName : getLocalName();
                boolean messageAllowed = securityManager.validateMessageWithOPA(msg, senderName, authenticatedName);
                
                if (!messageAllowed) {
                    System.out.println("🚫 FAM blocked message from " + senderName + " (OPA policy denied)");
                    sendPolicyViolation(msg, "OPA policy denies this communication");
                    return;
                }
                
                // Check container communication policy
                FederationPolicyManager.PolicyDecision commPolicy = 
                    policyManager.checkContainerCommunicationPolicy(msg.getSender(), getAID());
                
                if (!commPolicy.allowed) {
                    System.out.println("🚫 FAM blocked communication from " + senderName + ": " + commPolicy.reason);
                    sendPolicyViolation(msg, commPolicy.reason);
                    return;
                }
                
                if (msg.getPerformative() == ACLMessage.REQUEST && c != null) {
                    if (c.contains("(AssignFFA") || c.contains("(action (AssignFFA")) {
                        System.out.println("📥 FAM received FAP-ALLOC request from " + senderName);
                        handleAlloc(msg);
                    } else if (c.contains("(ResolveFFA")) {
                        System.out.println("📥 FAM received FAP-RESOLVE request from " + senderName);
                        handleResolve(msg);
                    } else if (c.contains("(UpdateFFA")) {
                        System.out.println("📥 FAM received FAP-UPDATE request from " + senderName);
                        handleUpdate(msg);
                    } else if (c.contains("(SearchServices")) {
                        System.out.println("📥 FAM received SERVICE-SEARCH request from " + senderName);
                        handleServiceSearch(msg);
                    } else if (c.contains("(RegisterService")) {
                        System.out.println("📥 FAM received SERVICE-REGISTER request from " + senderName);
                        handleServiceRegistration(msg);
                    } else if (c.contains("(GetSecurityStatus")) {
                        System.out.println("📥 FAM received SECURITY-STATUS request from " + senderName);
                        handleSecurityStatus(msg);
                    } else if (c.contains("(InitializeWorkflow")) {
                        System.out.println("📥 FAM received WORKFLOW-INIT request from " + senderName);
                        handleWorkflowInitialization(msg);
                    } else if (c.contains("(GetWorkflowStatus")) {
                        System.out.println("📥 FAM received WORKFLOW-STATUS request from " + senderName);
                        handleWorkflowStatus(msg);
                    } else if (c.contains("(GetHealthMetrics")) {
                        System.out.println("📥 FAM received HEALTH-METRICS request from " + senderName);
                        handleHealthMetrics(msg);
                    } else if (c.contains("(CheckPolicy")) {
                        System.out.println("📥 FAM received POLICY-CHECK request from " + senderName);
                        handlePolicyCheck(msg);
                    } else if (c.contains("(GetPolicyAudit")) {
                        System.out.println("📥 FAM received POLICY-AUDIT request from " + senderName);
                        handlePolicyAudit(msg);
                    } else {
                        System.out.println("❓ FAM received unknown request from " + senderName + ": " + c);
                        sendNotUnderstood(msg);
                    }
                } else {
                    sendNotUnderstood(msg);
                }
            }
        });
        
        // Add periodic lease cleanup behavior
        addBehaviour(new TickerBehaviour(this, 60000) { // Check every minute
            @Override
            protected void onTick() {
                int staleServices = federatedDirectory.cleanupStaleServices();
                if (staleServices > 0) {
                    System.out.println("🧹 FAM: cleaned " + staleServices + " stale services");
                }
            }
        });
    }

    private void handleAlloc(ACLMessage req) {
        String senderName = req.getSender().getLocalName();
        System.out.println("🔧 FAM processing allocation request for " + senderName);
        
        // NEW: Check federation join policy
        String federationId = "default-federation"; // Extract from request if specified
        FederationPolicyManager.PolicyDecision policy = 
            policyManager.checkFederationJoinPolicy(req.getSender(), federationId);
        
        if (!policy.allowed) {
            System.out.println("🚫 FAM denied allocation for " + senderName + ": " + policy.reason);
            sendPolicyViolation(req, policy.reason);
            return;
        }
        
        String geo = SL.ex(req.getContent(), ":geo \"", "\"");
        String dom = SL.ex(req.getContent(), ":domain \"", "\"");
        String level = SL.ex(req.getContent(), ":level \"", "\"");
        String sys = SL.ex(req.getContent(), ":system \"", "\"");
        String comp = SL.ex(req.getContent(), ":component \"", "\"");
        String cap = SL.ex(req.getContent(), ":capability \"", "\"");
        String qos = SL.ex(req.getContent(), ":qos \"", "\"");
        
        System.out.println("📋 Allocation parameters:");
        System.out.println("   GEO: " + geo + " | DOMAIN: " + dom + " | LEVEL: " + level);
        System.out.println("   SYSTEM: " + sys + " | COMPONENT: " + comp);
        System.out.println("   CAPABILITY: " + cap + " | QOS: " + qos);
        
        FAPProtocol.FAPResponse response = fapProtocol.allocateAddress(req.getSender(), geo, dom, level, sys, comp, cap, qos);
        
        ACLMessage inf = req.createReply();
        ACLUtil.commonHeaders(inf, "fipa-request", req.getConversationId());
        
        if (response.result == FAPProtocol.FAPResult.SUCCESS) {
            inf.setPerformative(ACLMessage.INFORM);
            inf.setContent("(Assigned-FFA :ffa \"" + response.getFfa() + "\" :policy-applied \"" + String.join(",", policy.appliedPolicies) + "\")");
            System.out.println("✅ FAM allocated FFA for " + senderName + ": " + response.getFfa() + " (Policy: " + policy.reason + ")");
            System.out.println("📊 Total active addresses: " + fapProtocol.getStatistics().get("activeRecords"));
        } else {
            inf.setPerformative(ACLMessage.FAILURE);
            inf.setContent("(Failure :reason \"" + response.getMessage() + "\")");
            System.out.println("❌ FAM allocation failed for " + senderName + ": " + response.getMessage());
        }
        send(inf);
    }

    private void handleResolve(ACLMessage req) {
        String ffaStr = SL.ex(req.getContent(), ":ffa \"", "\"");
        
        FAPProtocol.FAPResponse response = fapProtocol.resolveAddress(ffaStr);
        
        ACLMessage rep = req.createReply();
        ACLUtil.commonHeaders(rep, "fipa-request", req.getConversationId());
        
        if (response.result == FAPProtocol.FAPResult.SUCCESS) {
            rep.setPerformative(ACLMessage.INFORM);
            rep.setContent("(Resolved :aid \"" + response.getAid().getName() + "\")");
        } else {
            rep.setPerformative(ACLMessage.FAILURE);
            rep.setContent("(Failure :reason \"not-found\")");
        }
        send(rep);
    }

    private void handleUpdate(ACLMessage req) {
        FAPProtocol.FAPResponse response = fapProtocol.updateLease(req.getSender());
        
        ACLMessage rep = req.createReply();
        ACLUtil.commonHeaders(rep, "fipa-request", req.getConversationId());
        
        if (response.result == FAPProtocol.FAPResult.SUCCESS) {
            rep.setPerformative(ACLMessage.INFORM);
            rep.setContent("(Updated :ffa \"" + response.getFfa() + "\")");
        } else {
            rep.setPerformative(ACLMessage.FAILURE);
            rep.setContent("(Failure :reason \"no-lease\")");
        }
        send(rep);
    }

    private void sendNotUnderstood(ACLMessage req) {
        ACLMessage nu = req.createReply();
        nu.setPerformative(ACLMessage.NOT_UNDERSTOOD);
        send(nu);
    }
    
    /**
     * Register FAM with Directory Facilitator
     */
    private void registerWithDirectoryFacilitator() {
        try {
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());
            
            ServiceDescription sd = new ServiceDescription();
            sd.setType("FederationAddressManagement");
            sd.setName("FAM-Services");
            sd.addProtocols("fipa-request");
            sd.addProtocols("federation-addressing");
            
            // Add FAM-specific properties
            sd.addProperties(new Property("Service", "FAP-ALLOC"));
            sd.addProperties(new Property("Service", "FAP-RESOLVE"));
            sd.addProperties(new Property("Service", "FAP-UPDATE"));
            sd.addProperties(new Property("Service", "SERVICE-SEARCH"));
            sd.addProperties(new Property("Service", "SERVICE-REGISTER"));
            
            dfd.addServices(sd);
            DFService.register(this, dfd);
            
            System.out.println("✅ FAM registered with Directory Facilitator");
            
        } catch (FIPAException e) {
            System.err.println("❌ FAM DF registration failed: " + e.getMessage());
        }
    }
    
    /**
     * Handle federated service search requests
     */
    private void handleServiceSearch(ACLMessage req) {
        String ffaPattern = SL.ex(req.getContent(), ":ffa-pattern \"", "\"");
        String capability = SL.ex(req.getContent(), ":capability \"", "\"");
        String serviceType = SL.ex(req.getContent(), ":service-type \"", "\"");
        String qos = SL.ex(req.getContent(), ":qos \"", "\"");
        
        System.out.println("🔍 FAM searching services:");
        System.out.println("   FFA Pattern: " + ffaPattern);
        System.out.println("   Capability: " + capability);
        System.out.println("   Service Type: " + serviceType);
        
        // NEW: Check service discovery policy using DF
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        if (serviceType != null && !serviceType.isEmpty()) {
            sd.setType(serviceType);
        }
        template.addServices(sd);
        
        FederationPolicyManager.PolicyDecision policy = 
            policyManager.checkServiceDiscoveryPolicy(req.getSender(), template);
        
        if (!policy.allowed) {
            System.out.println("🚫 FAM denied service search for " + req.getSender().getLocalName() + ": " + policy.reason);
            sendPolicyViolation(req, policy.reason);
            return;
        }
        
        FederatedDirectoryService.FederatedServiceQuery query = 
            new FederatedDirectoryService.FederatedServiceQuery();
        query.ffaPattern = ffaPattern;
        query.capability = capability;
        query.serviceType = serviceType;
        query.qosRequirement = qos;
        
        List<jade.core.AID> results = federatedDirectory.searchFederatedServices(this, query);
        
        ACLMessage reply = req.createReply();
        ACLUtil.commonHeaders(reply, "fipa-request", req.getConversationId());
        
        if (!results.isEmpty()) {
            reply.setPerformative(ACLMessage.INFORM);
            StringBuilder content = new StringBuilder("(SearchResults :services (");
            
            for (jade.core.AID aid : results) {
                content.append("(service :aid \"").append(aid.getName()).append("\") ");
            }
            
            content.append(") :policy-applied \"").append(String.join(",", policy.appliedPolicies)).append("\")");
            reply.setContent(content.toString());
            
            System.out.println("✅ FAM found " + results.size() + " matching services (Policy: " + policy.reason + ")");
        } else {
            reply.setPerformative(ACLMessage.FAILURE);
            reply.setContent("(Failure :reason \"no-services-found\")");
            System.out.println("❌ FAM found no matching services");
        }
        
        send(reply);
    }
    
    /**
     * Handle federated service registration requests
     */
    private void handleServiceRegistration(ACLMessage req) {
        String serviceName = SL.ex(req.getContent(), ":name \"", "\"");
        String serviceType = SL.ex(req.getContent(), ":type \"", "\"");
        String ffa = SL.ex(req.getContent(), ":ffa \"", "\"");
        String capability = SL.ex(req.getContent(), ":capability \"", "\"");
        String qos = SL.ex(req.getContent(), ":qos \"", "\"");
        
        System.out.println("📝 FAM registering service:");
        System.out.println("   Name: " + serviceName);
        System.out.println("   Type: " + serviceType);
        System.out.println("   FFA: " + ffa);
        System.out.println("   Capability: " + capability);
        
        FederatedDirectoryService.FederatedServiceDescription service = 
            new FederatedDirectoryService.FederatedServiceDescription(serviceName, serviceType, ffa, capability);
        service.qosLevel = qos;
        service.addProtocol("fipa-request");
        
        boolean success = federatedDirectory.registerFederatedService(this, service);
        
        ACLMessage reply = req.createReply();
        ACLUtil.commonHeaders(reply, "fipa-request", req.getConversationId());
        
        if (success) {
            reply.setPerformative(ACLMessage.INFORM);
            reply.setContent("(Registered :service \"" + serviceName + "\")");
            System.out.println("✅ FAM registered service: " + serviceName);
        } else {
            reply.setPerformative(ACLMessage.FAILURE);
            reply.setContent("(Failure :reason \"registration-failed\")");
            System.out.println("❌ FAM service registration failed: " + serviceName);
        }
        
        send(reply);
    }
    
    /**
     * Handle workflow initialization requests
     */
    private void handleWorkflowInitialization(ACLMessage req) {
        String workflowType = SL.ex(req.getContent(), ":type \"", "\"");
        String geoParam = SL.ex(req.getContent(), ":geo \"", "\"");
        String domainParam = SL.ex(req.getContent(), ":domain \"", "\"");
        String capabilityParam = SL.ex(req.getContent(), ":capability \"", "\"");
        
        System.out.println("🚀 FAM initializing workflow:");
        System.out.println("   Type: " + workflowType);
        System.out.println("   Geo: " + geoParam);
        System.out.println("   Domain: " + domainParam);
        System.out.println("   Capability: " + capabilityParam);
        
        try {
            // Parse workflow type with fallback
            FederationWorkflowOrchestrator.WorkflowType type;
            try {
                type = FederationWorkflowOrchestrator.WorkflowType.valueOf(
                    workflowType.toUpperCase().replace("-", "_"));
            } catch (IllegalArgumentException e) {
                // Fallback to default if type not recognized
                System.out.println("⚠️ Unknown workflow type: " + workflowType + ", using SIMPLE_FEDERATION");
                type = FederationWorkflowOrchestrator.WorkflowType.SIMPLE_FEDERATION;
            }
            
            // Set up workflow parameters
            java.util.Map<String, Object> parameters = new java.util.HashMap<>();
            if (geoParam != null && !geoParam.isEmpty()) parameters.put("geo", geoParam);
            if (domainParam != null && !domainParam.isEmpty()) parameters.put("domain", domainParam);
            if (capabilityParam != null && !capabilityParam.isEmpty()) parameters.put("capability", capabilityParam);
            
            // Initialize workflow
            String workflowId = workflowOrchestrator.initializeFederationWorkflow(this, type, parameters);
            
            ACLMessage reply = req.createReply();
            ACLUtil.commonHeaders(reply, "fipa-request", req.getConversationId());
            reply.setPerformative(ACLMessage.INFORM);
            reply.setContent("(WorkflowInitialized :id \"" + workflowId + "\" :type \"" + type.name() + "\")");
            send(reply);
            
            System.out.println("✅ FAM initialized workflow: " + workflowId);
        } catch (Exception e) {
            ACLMessage reply = req.createReply();
            ACLUtil.commonHeaders(reply, "fipa-request", req.getConversationId());
            reply.setPerformative(ACLMessage.FAILURE);
            reply.setContent("(Failure :reason \"workflow-init-failed\" :details \"" + e.getMessage() + "\")");
            send(reply);
            
            System.out.println("❌ FAM workflow initialization failed: " + e.getMessage());
            e.printStackTrace(); // Print stack trace for debugging
        }
    }
    
    /**
     * Handle workflow status requests
     */
    private void handleWorkflowStatus(ACLMessage req) {
        String workflowId = SL.ex(req.getContent(), ":id \"", "\"");
        
        System.out.println("📊 FAM checking workflow status: " + workflowId);
        
        FederationWorkflowOrchestrator.FederationWorkflow workflow = 
            workflowOrchestrator.getWorkflowStatus(workflowId);
        
        ACLMessage reply = req.createReply();
        ACLUtil.commonHeaders(reply, "fipa-request", req.getConversationId());
        
        if (workflow != null) {
            reply.setPerformative(ACLMessage.INFORM);
            reply.setContent("(WorkflowStatus " +
                ":id \"" + workflow.workflowId + "\" " +
                ":state \"" + workflow.state + "\" " +
                ":completed-steps " + workflow.completedSteps.size() + " " +
                ":pending-steps " + workflow.pendingSteps.size() + " " +
                ":errors " + workflow.errorLog.size() + ")");
            System.out.println("✅ FAM returned workflow status for: " + workflowId);
        } else {
            reply.setPerformative(ACLMessage.FAILURE);
            reply.setContent("(Failure :reason \"workflow-not-found\" :id \"" + workflowId + "\")");
            System.out.println("❌ FAM workflow not found: " + workflowId);
        }
        
        send(reply);
    }
    
    /**
     * Handle health metrics requests
     */
    private void handleHealthMetrics(ACLMessage req) {
        String metricsType = SL.ex(req.getContent(), ":type \"", "\"");
        
        System.out.println("🏥 FAM retrieving health metrics: " + metricsType);
        
        try {
            java.util.Map<String, Object> healthStats = healthMonitor.getFederationHealthStats();
            
            ACLMessage reply = req.createReply();
            ACLUtil.commonHeaders(reply, "fipa-request", req.getConversationId());
            reply.setPerformative(ACLMessage.INFORM);
            
            String content = "(HealthMetrics " +
                ":total-entities " + healthStats.get("total-entities") + " " +
                ":avg-response-time-ms " + healthStats.get("avg-response-time-ms") + " " +
                ":health-check-interval-ms " + healthStats.get("health-check-interval-ms") + " " +
                ":status-distribution \"" + healthStats.get("status-distribution") + "\")";
            
            reply.setContent(content);
            send(reply);
            
            System.out.println("✅ FAM returned health metrics");
        } catch (Exception e) {
            ACLMessage reply = req.createReply();
            ACLUtil.commonHeaders(reply, "fipa-request", req.getConversationId());
            reply.setPerformative(ACLMessage.FAILURE);
            reply.setContent("(Failure :reason \"metrics-unavailable\" :details \"" + e.getMessage() + "\")");
            send(reply);
            
            System.out.println("❌ FAM health metrics retrieval failed: " + e.getMessage());
        }
    }
    
    /**
     * NEW: Handle security status requests
     */
    private void handleSecurityStatus(ACLMessage req) {
        String senderName = req.getSender().getLocalName();
        
        System.out.println("🔐 FAM retrieving security status for " + senderName);
        
        java.util.Map<String, Object> securityStats = securityManager.getSecurityStats();
        
        ACLMessage reply = req.createReply();
        ACLUtil.commonHeaders(reply, "fipa-request", req.getConversationId());
        reply.setPerformative(ACLMessage.INFORM);
        
        String content = "(SecurityStatus " +
            ":registered-companies " + securityStats.get("registered-companies") + " " +
            ":registered-agents " + securityStats.get("registered-agents") + " " +
            ":security-events " + securityStats.get("security-events") + " " +
            ":level-distribution " + securityStats.get("security-level-distribution") + ")";
        
        reply.setContent(content);
        send(reply);
        
        System.out.println("✅ FAM provided security status to " + senderName);
    }
    
    /**
     * NEW: Handle policy check requests
     */
    private void handlePolicyCheck(ACLMessage req) {
        String policyType = SL.ex(req.getContent(), ":type \"", "\"");
        String target = SL.ex(req.getContent(), ":target \"", "\"");
        
        System.out.println("🛡️ FAM checking policy: " + policyType + " for target: " + target);
        
        ACLMessage reply = req.createReply();
        ACLUtil.commonHeaders(reply, "fipa-request", req.getConversationId());
        
        try {
            java.util.Map<String, Object> policyStats = policyManager.getPolicyStatistics();
            
            reply.setPerformative(ACLMessage.INFORM);
            reply.setContent("(PolicyStatus " +
                ":total-policies " + policyStats.get("total-policies") + " " +
                ":active-policies " + policyStats.get("active-policies") + " " +
                ":audit-events " + policyStats.get("audit-events") + " " +
                ":distribution \"" + policyStats.get("policy-distribution") + "\")");
            
            System.out.println("✅ FAM provided policy status");
        } catch (Exception e) {
            reply.setPerformative(ACLMessage.FAILURE);
            reply.setContent("(Failure :reason \"policy-check-failed\" :details \"" + e.getMessage() + "\")");
            System.out.println("❌ FAM policy check failed: " + e.getMessage());
        }
        
        send(reply);
    }
    
    /**
     * NEW: Handle policy audit requests
     */
    private void handlePolicyAudit(ACLMessage req) {
        String countStr = SL.ex(req.getContent(), ":count \"", "\"");
        int count = 10; // default
        
        try {
            if (countStr != null && !countStr.isEmpty()) {
                count = Integer.parseInt(countStr);
            }
        } catch (NumberFormatException e) {
            count = 10;
        }
        
        System.out.println("📊 FAM retrieving " + count + " recent audit entries");
        
        List<String> auditEntries = policyManager.getRecentAuditLog(count);
        
        ACLMessage reply = req.createReply();
        ACLUtil.commonHeaders(reply, "fipa-request", req.getConversationId());
        reply.setPerformative(ACLMessage.INFORM);
        
        StringBuilder content = new StringBuilder("(PolicyAudit :entries (");
        for (String entry : auditEntries) {
            content.append("\"").append(entry.replace("\"", "'")).append("\" ");
        }
        content.append("))");
        
        reply.setContent(content.toString());
        send(reply);
        
        System.out.println("✅ FAM provided " + auditEntries.size() + " audit entries");
    }
    
    /**
     * NEW: Send policy violation response
     */
    private void sendPolicyViolation(ACLMessage req, String reason) {
        ACLMessage reply = req.createReply();
        ACLUtil.commonHeaders(reply, "fipa-request", req.getConversationId());
        reply.setPerformative(ACLMessage.REFUSE);
        reply.setContent("(PolicyViolation :reason \"" + reason + "\" :contact \"federation-admin\")");
        send(reply);
    }
}