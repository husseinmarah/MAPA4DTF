package milo.federation;

import jade.core.Agent;
import jade.core.AID;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Federated Directory Service - Directory Facilitator
 * with Federation-specific capabilities and FFA-based service discovery
 * 
 * Provides:
 * - FFA-based service registration and discovery
 * - Hierarchical service categorization
 * - Federation-aware yellow pages
 * - Cross-federation service resolution
 */
public class FederatedDirectoryService {
    
    /**
     * Enhanced service descriptor with federation metadata
     */
    public static class FederatedServiceDescription {
        public String serviceName;
        public String serviceType;
        public String ffa;
        public String capability;
        public String qosLevel;
        public Map<String, String> properties;
        public Set<String> protocols;
        public long registrationTime;
        public long lastHeartbeat;
        
        public FederatedServiceDescription(String name, String type, String ffa, String capability) {
            this.serviceName = name;
            this.serviceType = type;
            this.ffa = ffa;
            this.capability = capability;
            this.properties = new HashMap<>();
            this.protocols = new HashSet<>();
            this.registrationTime = System.currentTimeMillis();
            this.lastHeartbeat = System.currentTimeMillis();
        }
        
        public void addProperty(String key, String value) {
            properties.put(key, value);
        }
        
        public void addProtocol(String protocol) {
            protocols.add(protocol);
        }
        
        public void updateHeartbeat() {
            this.lastHeartbeat = System.currentTimeMillis();
        }
        
        public boolean isStale(long maxAge) {
            return (System.currentTimeMillis() - lastHeartbeat) > maxAge;
        }
    }
    
    /**
     * Service query with federation-aware search criteria
     */
    public static class FederatedServiceQuery {
        public String ffaPattern;
        public String serviceType;
        public String capability;
        public String qosRequirement;
        public Map<String, String> requiredProperties;
        public boolean includeRemoteFederations;
        
        public FederatedServiceQuery() {
            this.requiredProperties = new HashMap<>();
            this.includeRemoteFederations = false;
        }
        
        public static FederatedServiceQuery byCapability(String capability) {
            FederatedServiceQuery query = new FederatedServiceQuery();
            query.capability = capability;
            return query;
        }
        
        public static FederatedServiceQuery byFFAPattern(String ffaPattern) {
            FederatedServiceQuery query = new FederatedServiceQuery();
            query.ffaPattern = ffaPattern;
            return query;
        }
        
        public static FederatedServiceQuery byServiceType(String serviceType) {
            FederatedServiceQuery query = new FederatedServiceQuery();
            query.serviceType = serviceType;
            return query;
        }
    }
    
    // Local service registry (extends DF functionality)
    private final Map<AID, List<FederatedServiceDescription>> agentServices = new ConcurrentHashMap<>();
    private final Map<String, Set<AID>> serviceTypeIndex = new ConcurrentHashMap<>();
    private final Map<String, Set<AID>> capabilityIndex = new ConcurrentHashMap<>();
    private final Map<String, Set<AID>> ffaIndex = new ConcurrentHashMap<>();
    
    // Configuration
    private static final long SERVICE_HEARTBEAT_TIMEOUT = 60000; // 1 minute
    
    /**
     * Register a federated service (combines DF registration with FFA metadata)
     */
    public boolean registerFederatedService(Agent agent, FederatedServiceDescription service) {
        try {
            // Register with JADE's Directory Facilitator first
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(agent.getAID());
            
            ServiceDescription sd = new ServiceDescription();
            sd.setType(service.serviceType);
            sd.setName(service.serviceName);
            
            // Add FFA as a property
            if (service.ffa != null) {
                sd.addProperties(new Property("FFA", service.ffa));
            }
            if (service.capability != null) {
                sd.addProperties(new Property("Capability", service.capability));
            }
            if (service.qosLevel != null) {
                sd.addProperties(new Property("QoS", service.qosLevel));
            }
            
            // Add custom properties
            for (Map.Entry<String, String> prop : service.properties.entrySet()) {
                sd.addProperties(new Property(prop.getKey(), prop.getValue()));
            }
            
            // Add protocols
            for (String protocol : service.protocols) {
                sd.addProtocols(protocol);
            }
            
            dfd.addServices(sd);
            DFService.register(agent, dfd);
            
            // Add to our federated indexes
            addToFederatedIndexes(agent.getAID(), service);
            
            System.out.println("✅ Registered federated service: " + service.serviceName + 
                             " (FFA: " + service.ffa + ")");
            return true;
            
        } catch (FIPAException e) {
            System.err.println("❌ Failed to register federated service: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Search for federated services using enhanced criteria
     */
    public List<AID> searchFederatedServices(Agent agent, FederatedServiceQuery query) {
        List<AID> results = new ArrayList<>();
        
        try {
            // First, search using standard DF
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            
            if (query.serviceType != null) {
                sd.setType(query.serviceType);
            }
            
            template.addServices(sd);
            DFAgentDescription[] dfResults = DFService.search(agent, template);
            
            // Filter results based on federation criteria
            for (DFAgentDescription desc : dfResults) {
                AID candidateAID = desc.getName();
                
                if (matchesFederatedCriteria(candidateAID, query)) {
                    results.add(candidateAID);
                }
            }
            
            // Additional federation-specific searches
            if (query.ffaPattern != null) {
                Set<AID> ffaMatches = searchByFFAPattern(query.ffaPattern);
                for (AID aid : ffaMatches) {
                    if (!results.contains(aid) && matchesFederatedCriteria(aid, query)) {
                        results.add(aid);
                    }
                }
            }
            
            if (query.capability != null) {
                Set<AID> capabilityMatches = capabilityIndex.get(query.capability);
                if (capabilityMatches != null) {
                    for (AID aid : capabilityMatches) {
                        if (!results.contains(aid) && matchesFederatedCriteria(aid, query)) {
                            results.add(aid);
                        }
                    }
                }
            }
            
        } catch (FIPAException e) {
            System.err.println("❌ Federated service search failed: " + e.getMessage());
        }
        
        return results;
    }
    
    /**
     * Search services by FFA pattern matching
     */
    private Set<AID> searchByFFAPattern(String pattern) {
        Set<AID> matches = new HashSet<>();
        
        for (Map.Entry<String, Set<AID>> entry : ffaIndex.entrySet()) {
            String ffa = entry.getKey();
            if (FFAMatcher.matches(ffa, pattern)) {
                matches.addAll(entry.getValue());
            }
        }
        
        return matches;
    }
    
    /**
     * Check if agent matches federated search criteria
     */
    private boolean matchesFederatedCriteria(AID agent, FederatedServiceQuery query) {
        List<FederatedServiceDescription> services = agentServices.get(agent);
        if (services == null || services.isEmpty()) {
            return false;
        }
        
        for (FederatedServiceDescription service : services) {
            // Check if service is stale
            if (service.isStale(SERVICE_HEARTBEAT_TIMEOUT)) {
                continue;
            }
            
            // Match capability
            if (query.capability != null && !query.capability.equals(service.capability)) {
                continue;
            }
            
            // Match FFA pattern
            if (query.ffaPattern != null && service.ffa != null) {
                if (!FFAMatcher.matches(service.ffa, query.ffaPattern)) {
                    continue;
                }
            }
            
            // Match QoS requirement
            if (query.qosRequirement != null && !query.qosRequirement.equals(service.qosLevel)) {
                continue;
            }
            
            // Match custom properties
            boolean propertiesMatch = true;
            for (Map.Entry<String, String> reqProp : query.requiredProperties.entrySet()) {
                if (!reqProp.getValue().equals(service.properties.get(reqProp.getKey()))) {
                    propertiesMatch = false;
                    break;
                }
            }
            
            if (propertiesMatch) {
                return true; // Found a matching service
            }
        }
        
        return false;
    }
    
    /**
     * Add service to federated indexes
     */
    private void addToFederatedIndexes(AID agent, FederatedServiceDescription service) {
        // Agent services index
        agentServices.computeIfAbsent(agent, k -> new ArrayList<>()).add(service);
        
        // Service type index
        if (service.serviceType != null) {
            serviceTypeIndex.computeIfAbsent(service.serviceType, k -> new HashSet<>()).add(agent);
        }
        
        // Capability index
        if (service.capability != null) {
            capabilityIndex.computeIfAbsent(service.capability, k -> new HashSet<>()).add(agent);
        }
        
        // FFA index
        if (service.ffa != null) {
            ffaIndex.computeIfAbsent(service.ffa, k -> new HashSet<>()).add(agent);
        }
    }
    
    /**
     * Update service heartbeat
     */
    public void updateServiceHeartbeat(AID agent) {
        List<FederatedServiceDescription> services = agentServices.get(agent);
        if (services != null) {
            for (FederatedServiceDescription service : services) {
                service.updateHeartbeat();
            }
        }
    }
    
    /**
     * Cleanup stale services
     */
    public int cleanupStaleServices() {
        int cleanedCount = 0;
        List<AID> toRemove = new ArrayList<>();
        
        for (Map.Entry<AID, List<FederatedServiceDescription>> entry : agentServices.entrySet()) {
            AID agent = entry.getKey();
            List<FederatedServiceDescription> services = entry.getValue();
            
            services.removeIf(service -> service.isStale(SERVICE_HEARTBEAT_TIMEOUT));
            
            if (services.isEmpty()) {
                toRemove.add(agent);
            }
            
            cleanedCount += services.size();
        }
        
        // Remove agents with no active services
        for (AID agent : toRemove) {
            removeAgentFromIndexes(agent);
        }
        
        return cleanedCount;
    }
    
    /**
     * Remove agent from all indexes
     */
    private void removeAgentFromIndexes(AID agent) {
        agentServices.remove(agent);
        
        // Remove from type index
        for (Set<AID> agents : serviceTypeIndex.values()) {
            agents.remove(agent);
        }
        
        // Remove from capability index
        for (Set<AID> agents : capabilityIndex.values()) {
            agents.remove(agent);
        }
        
        // Remove from FFA index
        for (Set<AID> agents : ffaIndex.values()) {
            agents.remove(agent);
        }
    }
    
    /**
     * Get service statistics
     */
    public Map<String, Object> getServiceStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("registered-agents", agentServices.size());
        stats.put("service-types", serviceTypeIndex.size());
        stats.put("capabilities", capabilityIndex.size());
        stats.put("ffa-registrations", ffaIndex.size());
        
        int totalServices = agentServices.values().stream()
                                        .mapToInt(List::size)
                                        .sum();
        stats.put("total-services", totalServices);
        
        return stats;
    }
}