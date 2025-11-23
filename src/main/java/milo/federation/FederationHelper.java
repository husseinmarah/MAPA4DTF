package milo.federation;

import jade.core.AID;
import jade.core.Agent;
import jade.lang.acl.ACLMessage;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.*;
import jade.lang.acl.MessageTemplate;
import milo.utils.SL;

import java.util.*;

/**
 * Federation Helper for OPC UA Server Agents
 * 
 * Provides utilities for agents to integrate with the Federation Address
 * Protocol (FAP)
 * and Federation Fractal Address (FFA) system.
 */
public class FederationHelper {

    // Cache for agent FFAs to avoid repeated lookups
    private static final Map<String, String> ffaCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Update the local FFA cache for an agent
     * 
     * @param agentName The local name of the agent
     * @param ffa       The allocated FFA
     */
    public static void updateFFACache(String agentName, String ffa) {
        if (agentName != null && ffa != null) {
            ffaCache.put(agentName, ffa);
            // System.out.println("[FederationHelper] Updated FFA cache for " + agentName);
        }
    }

    /**
     * Get an agent's FFA from the local cache
     * 
     * @param agentName The local name of the agent
     * @return The cached FFA, or null if not found
     */
    public static String getAgentFFA(String agentName) {
        return ffaCache.get(agentName);
    }

    /**
     * Request FFA allocation from Federation Address Manager Agent
     * 
     * @param agent      The agent requesting federation address
     * @param agentType  Type of agent (e.g., "Robot", "Conveyor")
     * @param instanceId Instance identifier for this specific agent
     * @param capability Primary capability of this agent
     * @return The allocated FFA string, or null if allocation failed
     */
    public static String requestFFAAllocation(Agent agent, String agentType, int instanceId, String capability) {
        try {
            // Find Federation Address Manager Agent
            AID famAgent = findFederationAddressManager(agent);
            if (famAgent == null) {
                System.err.println("⚠️ Federation Address Manager not found");
                return null;
            }

            // Create FAP-ALLOC request
            ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
            request.addReceiver(famAgent);
            request.setProtocol("fipa-request");
            request.setConversationId("fap-alloc-" + System.currentTimeMillis());

            // Build allocation parameters based on manufacturing context
            String content = String.format(
                    "(AssignFFA " +
                            ":geo \"EU\" " +
                            ":domain \"Manufacturing\" " +
                            ":level \"Production\" " +
                            ":system \"Chemical\" " +
                            ":component \"%s\" " +
                            ":capability \"%s\" " +
                            ":qos \"Standard\")",
                    agentType, capability);

            request.setContent(content);

            // Send request and wait for response
            agent.send(request);
            ACLMessage response = agent.blockingReceive(
                    MessageTemplate.MatchConversationId(request.getConversationId()),
                    10000 // 10 second timeout
            );

            if (response != null) {
                if (response.getPerformative() == ACLMessage.INFORM) {
                    // Extract FFA from response
                    String ffa = SL.ex(response.getContent(), ":ffa \"", "\"");

                    if (ffa != null && !ffa.isEmpty()) {
                        System.out.println("┌─ FFA ALLOCATED ───────────────────────────────────");
                        System.out.println("│  Agent: " + agent.getLocalName());
                        System.out.println("│  FFA:   " + ffa);
                        System.out.println("└──────────────────────────────────────────────────");
                        FederationHelper.updateFFACache(agent.getLocalName(), ffa);
                        return ffa;
                    } else {
                        System.err.println("❌ FFA allocation failed - no FFA in response");
                    }
                } else if (response.getPerformative() == ACLMessage.FAILURE) {
                    System.err.println("❌ FFA allocation failed: " + response.getContent());
                } else if (response.getPerformative() == ACLMessage.REFUSE) {
                    System.err.println(
                            "[FederationHelper] ❌ FAM REFUSED allocation request for agent: " + agent.getLocalName());
                    System.err.println("[FederationHelper]    Reason: " + response.getContent());
                } else {
                    System.err.println("[FederationHelper] ❌ Unexpected performative: "
                            + ACLMessage.getPerformative(response.getPerformative()));
                }
            } else {
                System.err.println("[FederationHelper] ❌ No response from FAM (timeout after 10 seconds)");
                System.err.println("[FederationHelper]    Conversation ID: " + request.getConversationId());
                System.err.println("[FederationHelper]    FAM AID: " + famAgent.getName());
            }

        } catch (Exception e) {
            System.err.println("[FederationHelper] ❌ Exception requesting FFA: " + e.getMessage());
            e.printStackTrace();
        }

        return null;
    }

    /**
     * Resolve FFA to AID using Federation Address Manager
     * 
     * @param agent     The agent making the resolution request
     * @param targetFFA The FFA to resolve
     * @return The resolved AID, or null if resolution failed
     */
    public static AID resolveFFA(Agent agent, String targetFFA) {
        try {
            AID famAgent = findFederationAddressManager(agent);
            if (famAgent == null) {
                return null;
            }

            ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
            request.addReceiver(famAgent);
            request.setProtocol("fipa-request");
            request.setConversationId("fap-resolve-" + UUID.randomUUID().toString());
            request.setContent("(ResolveFFA :ffa \"" + targetFFA + "\")");

            agent.send(request);
            ACLMessage response = agent.blockingReceive();

            if (response != null && response.getPerformative() == ACLMessage.INFORM) {
                String aidName = SL.ex(response.getContent(), ":aid \"", "\"");
                return new AID(aidName, AID.ISLOCALNAME);
            }

        } catch (Exception e) {
            System.err.println("[" + agent.getLocalName() + "] Error in FFA resolution: " + e.getMessage());
        }

        return null;
    }

    /**
     * Update lease for current agent's allocated FFA
     * 
     * @param agent The agent updating its lease
     * @return true if lease was successfully updated
     */
    public static boolean updateFFALease(Agent agent) {
        try {
            AID famAgent = findFederationAddressManager(agent);
            if (famAgent == null) {
                return false;
            }

            ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
            request.addReceiver(famAgent);
            request.setProtocol("fipa-request");
            request.setConversationId("fap-update-" + UUID.randomUUID().toString());
            request.setContent("(UpdateFFA)");

            agent.send(request);
            ACLMessage response = agent.blockingReceive();

            return response != null && response.getPerformative() == ACLMessage.INFORM;

        } catch (Exception e) {
            System.err.println("[" + agent.getLocalName() + "] Error in FFA lease update: " + e.getMessage());
        }

        return false;
    }

    /**
     * Find Federation Address Manager Agent using Directory Facilitator
     * 
     * @param agent The agent searching for FAM
     * @return AID of Federation Address Manager, or null if not found
     */
    private static AID findFederationAddressManager(Agent agent) {
        try {
            // Search for FAM using Directory Facilitator
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("FederationAddressManagement");
            template.addServices(sd);

            DFAgentDescription[] results = DFService.search(agent, template);

            if (results.length > 0) {
                return results[0].getName();
            }

            // Fallback to known name
            return new AID("FederationAddressManager", AID.ISLOCALNAME);

        } catch (FIPAException e) {
            System.err.println("[FederationHelper] DF search failed: " + e.getMessage());
            return new AID("FederationAddressManager", AID.ISLOCALNAME);
        }
    }

    /**
     * Register federated service using enhanced directory service
     */
    public static boolean registerFederatedService(Agent agent, String serviceName,
            String serviceType, String ffa, String capability) {
        try {
            // Create federated service description
            FederatedDirectoryService.FederatedServiceDescription service = new FederatedDirectoryService.FederatedServiceDescription(
                    serviceName, serviceType, ffa, capability);
            service.addProtocol("fipa-request");
            service.addProperty("agent-type", agent.getClass().getSimpleName());

            // Register with standard DF
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(agent.getAID());

            ServiceDescription sd = new ServiceDescription();
            sd.setType(serviceType);
            sd.setName(serviceName);
            sd.addProtocols("fipa-request");

            if (ffa != null) {
                sd.addProperties(new Property("FFA", ffa));
            }
            if (capability != null) {
                sd.addProperties(new Property("Capability", capability));
            }

            dfd.addServices(sd);
            DFService.register(agent, dfd);

            System.out
                    .println("[FederationHelper] Registered federated service: " + serviceName + " (FFA: " + ffa + ")");
            return true;

        } catch (FIPAException e) {
            System.err.println("[FederationHelper] Service registration failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Discover federated services by capability
     */
    public static List<AID> discoverServicesByCapability(Agent agent, String capability) {
        List<AID> services = new ArrayList<>();

        try {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            template.addServices(sd);

            DFAgentDescription[] results = DFService.search(agent, template);

            for (DFAgentDescription desc : results) {
                // Check if service has the required capability
                for (Iterator services_it = desc.getAllServices(); services_it.hasNext();) {
                    ServiceDescription service = (ServiceDescription) services_it.next();

                    for (Iterator props_it = service.getAllProperties(); props_it.hasNext();) {
                        Property prop = (Property) props_it.next();
                        if ("Capability".equals(prop.getName()) && capability.equals(prop.getValue())) {
                            services.add(desc.getName());
                            break;
                        }
                    }
                }
            }

            System.out.println(
                    "[FederationHelper] Found " + services.size() + " services with capability: " + capability);

        } catch (FIPAException e) {
            System.err.println("[FederationHelper] Service discovery failed: " + e.getMessage());
        }

        return services;
    }

    /**
     * Discover federated services by FFA pattern
     */
    public static List<AID> discoverServicesByFFAPattern(Agent agent, String ffaPattern) {
        List<AID> services = new ArrayList<>();

        try {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            template.addServices(sd);

            DFAgentDescription[] results = DFService.search(agent, template);

            for (DFAgentDescription desc : results) {
                // Check if service has FFA matching the pattern
                for (Iterator services_it = desc.getAllServices(); services_it.hasNext();) {
                    ServiceDescription service = (ServiceDescription) services_it.next();

                    for (Iterator props_it = service.getAllProperties(); props_it.hasNext();) {
                        Property prop = (Property) props_it.next();
                        if ("FFA".equals(prop.getName())) {
                            String ffa = (String) prop.getValue();
                            if (ffa != null && matchesFFAPattern(ffa, ffaPattern)) {
                                services.add(desc.getName());
                                break;
                            }
                        }
                    }
                }
            }

            System.out.println(
                    "[FederationHelper] Found " + services.size() + " services matching FFA pattern: " + ffaPattern);

        } catch (FIPAException e) {
            System.err.println("[FederationHelper] FFA pattern discovery failed: " + e.getMessage());
        }

        return services;
    }

    /**
     * Search for federated services via Federation Address Manager
     * 
     * @param agent       The agent performing the search
     * @param ffaPattern  The FFA pattern to match (e.g., "EU/Plant7.*")
     * @param capability  The capability to match (optional)
     * @param serviceType The service type to match (optional)
     * @return List of AIDs of matching services
     */
    public static List<AID> searchFederatedServices(Agent agent, String ffaPattern, String capability,
            String serviceType) {
        List<AID> services = new ArrayList<>();

        try {
            AID famAgent = findFederationAddressManager(agent);
            if (famAgent == null) {
                System.err.println("[FederationHelper] Federation Address Manager not found for service search");
                return services;
            }

            ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
            request.addReceiver(famAgent);
            request.setProtocol("fipa-request");
            request.setConversationId("service-search-" + UUID.randomUUID().toString());

            String content = String.format(
                    "(SearchServices :ffa-pattern \"%s\" :capability \"%s\" :service-type \"%s\")",
                    ffaPattern != null ? ffaPattern : "*",
                    capability != null ? capability : "",
                    serviceType != null ? serviceType : "");

            request.setContent(content);
            agent.send(request);

            ACLMessage response = agent.blockingReceive(
                    jade.lang.acl.MessageTemplate.MatchConversationId(request.getConversationId()),
                    10000); // Increased timeout to 10 seconds

            if (response != null && response.getPerformative() == ACLMessage.INFORM) {
                // Parse results from content: (SearchResults :services ((service :aid "name")
                // ...))
                String respContent = response.getContent();

                // Simple parsing loop
                int index = 0;
                while ((index = respContent.indexOf(":aid \"", index)) != -1) {
                    index += 6; // Skip :aid "
                    int endQuote = respContent.indexOf("\"", index);
                    if (endQuote != -1) {
                        String agentName = respContent.substring(index, endQuote);
                        services.add(new AID(agentName, AID.ISLOCALNAME));
                        index = endQuote + 1;
                    } else {
                        break;
                    }
                }

                System.out.println("[FederationHelper] Found " + services.size() + " services via FAM");
            } else {
                String reason = "timed out";
                if (response != null) {
                    reason = "received " + ACLMessage.getPerformative(response.getPerformative()) + 
                             " with content: " + response.getContent();
                }
                System.err.println("[FederationHelper] Service search failed or " + reason);
            }

        } catch (Exception e) {
            System.err.println("[FederationHelper] Error searching services: " + e.getMessage());
            e.printStackTrace();
        }

        return services;
    }

    /**
     * Simple FFA pattern matching (basic wildcard support)
     */
    private static boolean matchesFFAPattern(String ffa, String pattern) {
        if (pattern.equals("*"))
            return true;
        if (!pattern.contains("*"))
            return ffa.equals(pattern);

        String regex = pattern.replace(".", "\\.")
                .replace("*", ".*");
        return ffa.matches(regex);
    }

    /**
     * Create federation-capable agent name
     * 
     * @param baseType   Base agent type (e.g., "Robot", "Conveyor")
     * @param instanceId Instance number
     * @return Federation-aware agent name
     */
    public static String createFederationAgentName(String baseType, int instanceId) {
        return baseType + "Agent" + instanceId + "_Fed";
    }

    /**
     * Parse FFA to extract system information
     * 
     * @param ffa The FFA string to parse
     * @return FFA object with parsed components
     */
    public static FFA parseFFA(String ffa) {
        if (ffa == null || ffa.isEmpty()) {
            return null;
        }

        try {
            return new FFA(ffa);
        } catch (Exception e) {
            System.err.println("Error parsing FFA: " + ffa + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * Check if agent is federation-enabled
     * 
     * @param ffa The agent's FFA
     * @return true if agent has valid federation address
     */
    public static boolean isFederationEnabled(String ffa) {
        return ffa != null && !ffa.isEmpty() && parseFFA(ffa) != null;
    }

    /**
     * Comprehensive federation initialization for an agent
     * 
     * @param agent       The agent to initialize
     * @param geo         Geographic organization
     * @param domain      Domain
     * @param level       Level in hierarchy
     * @param system      System identifier
     * @param component   Component identifier (optional)
     * @param capability  Primary capability
     * @param qos         QoS requirement (optional)
     * @param serviceType Service type for DF registration
     * @return Allocated FFA or null if failed
     */
    public static String initializeFederation(Agent agent, String geo, String domain,
            String level, String system, String component,
            String capability, String qos, String serviceType) {
        try {
            System.out.println("[FederationHelper] Initializing federation for " + agent.getLocalName());

            // Step 1: Allocate FFA (simplified call for now)
            String ffa = geo + "." + domain + "." + level + "." + system +
                    (component != null ? "." + component : "") + "#1::" + capability +
                    (qos != null ? "@" + qos : "");
            // FFA constructed successfully
            System.out.println("[FederationHelper] Generated FFA: " + ffa);

            // Step 2: Register federated service
            String serviceName = agent.getLocalName() + "-FederatedService";
            boolean serviceRegistered = registerFederatedService(agent, serviceName, serviceType, ffa, capability);
            if (!serviceRegistered) {
                System.err.println("[FederationHelper] Service registration failed for " + agent.getLocalName());
                // Continue anyway, as FFA allocation succeeded
            }

            // Step 3: Discover peer services
            List<AID> peers = discoverServicesByCapability(agent, capability);
            System.out.println(
                    "[FederationHelper] Discovered " + peers.size() + " peer services for " + agent.getLocalName());

            System.out.println("[FederationHelper] Federation initialization completed for " + agent.getLocalName()
                    + " (FFA: " + ffa + ")");
            return ffa;

        } catch (Exception e) {
            System.err.println("[FederationHelper] Federation initialization failed for " + agent.getLocalName() + ": "
                    + e.getMessage());
            return null;
        }
    }

    /**
     * Initialize federation workflow
     */
    public static String initializeFederationWorkflow(Agent agent, String workflowType,
            String geo, String domain, String capability) {
        try {
            AID famAgent = findFederationAddressManager(agent);
            if (famAgent == null) {
                System.err
                        .println("[FederationHelper] Federation Address Manager not found for workflow initialization");
                return null;
            }

            // Use valid workflow type
            String validWorkflowType = normalizeWorkflowType(workflowType);

            ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
            request.addReceiver(famAgent);
            request.setProtocol("fipa-request");
            request.setConversationId("workflow-init-" + System.currentTimeMillis());
            request.setContent(String.format(
                    "(InitializeWorkflow :type \"%s\" :geo \"%s\" :domain \"%s\" :capability \"%s\")",
                    validWorkflowType, geo, domain, capability));

            System.out.println("[FederationHelper] Sending workflow request: " + validWorkflowType);
            agent.send(request);

            // Wait for response
            ACLMessage response = agent.blockingReceive(
                    jade.lang.acl.MessageTemplate.MatchConversationId(request.getConversationId()),
                    5000);

            if (response != null) {
                if (response.getPerformative() == ACLMessage.INFORM) {
                    String workflowId = SL.ex(response.getContent(), ":id \"", "\"");

                    if (workflowId != null && !workflowId.isEmpty()) {
                        System.out.println("┌─ FEDERATION WORKFLOW RESPONSE ───────────────────");
                        System.out.println("│  Status:      ✅ SUCCESS");
                        System.out.println("│  Workflow ID: " + workflowId);
                        System.out.println("│  Type:        " + validWorkflowType);
                        System.out.println("│  Performative: INFORM");
                        System.out.println("└───────────────────────────────────────────────────");
                        return workflowId;
                    } else {
                        System.err.println("[FederationHelper] ⚠️ Workflow response contained no ID");
                        System.err.println("[FederationHelper]    Content: " + response.getContent());
                    }
                } else {
                    System.err.println("[FederationHelper] ❌ Workflow init failed: " + response.getContent());
                }
            } else {
                System.err.println("[FederationHelper] ⚠️ No response for workflow initialization (timeout)");
            }

        } catch (Exception e) {
            System.err.println("[FederationHelper] ❌ Error initializing workflow: " + e.getMessage());
            e.printStackTrace();
        }

        return null;
    }

    /**
     * Normalize workflow type to valid enum value
     * Maps common names to actual WorkflowType enum values
     */
    private static String normalizeWorkflowType(String workflowType) {
        if (workflowType == null)
            return "simple-federation";

        String normalized = workflowType.toLowerCase().trim();

        // Map common workflow names to valid enum values
        switch (normalized) {
            case "company-federation":
            case "company":
            case "basic":
            case "simple":
                return "simple-federation";

            case "multi-level-federation":
            case "multi-level":
            case "hierarchical":
                return "multi-level-federation";

            case "cross-domain-federation":
            case "cross-domain":
            case "distributed":
                return "cross-domain-federation";

            case "dynamic-federation":
            case "dynamic":
            case "adaptive":
                return "dynamic-federation";

            default:
                // If it already looks valid, return as-is
                if (normalized.contains("-federation")) {
                    return normalized;
                }
                // Default fallback
                return "simple-federation";
        }
    }

    /**
     * Get workflow status
     */
    public static String getWorkflowStatus(Agent agent, String workflowId) {
        try {
            AID famAgent = findFederationAddressManager(agent);
            if (famAgent == null) {
                System.err.println("[FederationHelper] Federation Address Manager not found for workflow status");
                return null;
            }

            ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
            request.addReceiver(famAgent);
            request.setProtocol("fipa-request");
            request.setConversationId("workflow-status-" + UUID.randomUUID().toString());

            String content = "(GetWorkflowStatus :id \"" + workflowId + "\")";
            request.setContent(content);
            agent.send(request);

            // Wait for response
            ACLMessage reply = agent.blockingReceive();
            if (reply != null && reply.getPerformative() == ACLMessage.INFORM) {
                System.out.println("[FederationHelper] Workflow status retrieved for: " + workflowId);
                return reply.getContent();
            } else {
                System.err.println("[FederationHelper] Workflow status retrieval failed");
                return null;
            }

        } catch (Exception e) {
            System.err.println("[FederationHelper] Workflow status error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Get federation health metrics
     */
    public static String getFederationHealthMetrics(Agent agent) {
        try {
            AID famAgent = findFederationAddressManager(agent);
            if (famAgent == null) {
                System.err.println("[FederationHelper] Federation Address Manager not found for health metrics");
                return null;
            }

            ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
            request.addReceiver(famAgent);
            request.setProtocol("fipa-request");
            request.setConversationId("health-metrics-" + UUID.randomUUID().toString());

            String content = "(GetHealthMetrics :type \"federation\")";
            request.setContent(content);
            agent.send(request);

            // Wait for response
            ACLMessage reply = agent.blockingReceive();
            if (reply != null && reply.getPerformative() == ACLMessage.INFORM) {
                System.out.println("[FederationHelper] Health metrics retrieved");
                return reply.getContent();
            } else {
                System.err.println("[FederationHelper] Health metrics retrieval failed");
                return null;
            }

        } catch (Exception e) {
            System.err.println("[FederationHelper] Health metrics error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Graceful federation shutdown for an agent
     */
    public static void shutdownFederation(Agent agent) {
        try {
            // Deregister from Directory Facilitator
            DFService.deregister(agent);
            System.out.println("[FederationHelper] Federation shutdown completed for " + agent.getLocalName());

        } catch (FIPAException e) {
            System.err.println("[FederationHelper] Federation shutdown failed for " + agent.getLocalName() + ": "
                    + e.getMessage());
        }
    }
}