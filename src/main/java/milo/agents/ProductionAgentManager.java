package milo.agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import milo.federation.FederationHelper;
import milo.security.FederationSecurityManager;
import milo.security.FederationSecurityManager.SecurityContext; // NEW: Keycloak context

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production Agent Manager - Coordinates and manages manufacturing agents
 * Handles task assignment, status monitoring, and acknowledgement tracking
 * Integrates with Federation Address Protocol (FAP) for agent coordination
 */
public class ProductionAgentManager extends Agent {

    // =====================================================================
    // AGENT MANAGEMENT
    // =====================================================================
    private Map<AID, AgentStatus> managedAgents; // Track agent status
    private Map<String, ProductionTask> activeTasks; // Track active tasks
    private Map<String, String> agentFFAs; // Agent FFA mappings
    private int taskIdCounter = 1000; // Task ID generation
    
    // =====================================================================
    // FEDERATION SUPPORT
    // =====================================================================
    private String myFFA; // This manager's Federation Fractal Address
    private boolean federationEnabled = false;
    
    // =====================================================================
    // SECURITY
    // =====================================================================
    private FederationSecurityManager securityManager;
    private SecurityContext securityContext; // Keycloak authentication context
    
    // =====================================================================
    // INNER CLASSES
    // =====================================================================
    
    /**
     * Agent Status Information
     */
    public static class AgentStatus {
        public String agentType; // ROBOT, CONVEYOR, etc.
        public String status; // IDLE, BUSY, ERROR, OFFLINE
        public String capability; // MaterialHandling, MaterialTransport, etc.
        public long lastHeartbeat;
        public String currentTask;
        public Map<String, Object> properties;
        
        public AgentStatus(String agentType, String capability) {
            this.agentType = agentType;
            this.capability = capability;
            this.status = "IDLE";
            this.lastHeartbeat = System.currentTimeMillis();
            this.properties = new HashMap<>();
        }
        
        public boolean isOnline() {
            return (System.currentTimeMillis() - lastHeartbeat) < 30000; // 30 second timeout
        }
    }
    
    /**
     * Production Task Information
     */
    public static class ProductionTask {
        public String taskId;
        public String taskType; // PICKUP, TRANSPORT, PROCESS, etc.
        public AID assignedAgent;
        public String status; // ASSIGNED, IN_PROGRESS, COMPLETED, FAILED
        public long assignedTime;
        public long completedTime;
        public Map<String, Object> parameters;
        public String priority; // HIGH, MEDIUM, LOW
        
        public ProductionTask(String taskId, String taskType, String priority) {
            this.taskId = taskId;
            this.taskType = taskType;
            this.priority = priority;
            this.status = "CREATED";
            this.assignedTime = System.currentTimeMillis();
            this.parameters = new HashMap<>();
        }
    }

    // =====================================================================
    // AGENT LIFECYCLE
    // =====================================================================
    
    @Override
    protected void setup() {
        // STEP 1: Authenticate with Keycloak
        securityManager = FederationSecurityManager.getInstance();
        String keycloakUsername = "ProductionAgentManager"; // Keycloak user
        securityContext = securityManager.authenticateWithKeycloak(keycloakUsername, "production");
        
        if (securityContext == null) {
            System.err.println("┌─ AUTHENTICATION FALLBACK ────────────────────────");
            System.err.println("│  ⚠️ Keycloak authentication failed");
            System.err.println("│  Agent: " + getLocalName());
            System.err.println("│  Using local authentication");
            System.err.println("└──────────────────────────────────────────────────");
            securityManager.registerSecureAgent(getLocalName(), "main", "Main-Container");
        } else {
            // Link JADE agent name to Keycloak identity
            securityManager.linkAgentToContext(getLocalName(), keycloakUsername);
        }

        // Initialize data structures
        managedAgents = new ConcurrentHashMap<>();
        activeTasks = new ConcurrentHashMap<>();
        agentFFAs = new ConcurrentHashMap<>();
        
        // Initialize federation and registration
        initializeFederation();
        registerWithDF();
        
        // Start management behaviors
        addBehaviour(new AgentRegistrationHandler());
        addBehaviour(new TaskAssignmentHandler());
        addBehaviour(new AcknowledgementHandler());
        addBehaviour(new HeartbeatMonitor(this, 10000));
        addBehaviour(new ProductionCoordinationBehaviour(this, 30000));
        
        System.out.println("✅ " + getLocalName() + " ready (Production coordination active)");
    }
    
    /**
     * Initialize federation capabilities
     */
    private void initializeFederation() {
        try {
            // Request FFA allocation for management role
            myFFA = FederationHelper.requestFFAAllocation(this, "ProductionManager", 1, "ProductionCoordination.Primary");
            
            if (myFFA != null) {
                federationEnabled = true;
                // Initialize hierarchical federation workflow
                FederationHelper.initializeFederationWorkflow(
                    this, "hierarchical-federation", "EU/Plant7", "Manufacturing", "ProductionCoordination.Primary");
            }
            
        } catch (Exception e) {
            System.err.println("⚠️ Federation initialization failed: " + e.getMessage());
        }
    }
    
    /**
     * Register management services with Directory Facilitator
     */
    private void registerWithDF() {
        try {
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());
            
            ServiceDescription sd = new ServiceDescription();
            sd.setType("ManufacturingCoordination");
            sd.setName("ProductionAgentManager-Coordination");
            sd.addProtocols("fipa-request");
            sd.addProtocols("federation-coordination");
            
            if (federationEnabled) {
                sd.addProperties(new jade.domain.FIPAAgentManagement.Property("FFA", myFFA));
                sd.addProperties(new jade.domain.FIPAAgentManagement.Property("Federation-Enabled", "true"));
            }
            
            dfd.addServices(sd);
            DFService.register(this, dfd);
            
            System.out.println("[" + getLocalName() + "] Registered with Directory Facilitator");
            
        } catch (FIPAException e) {
            System.err.println("[" + getLocalName() + "] DF registration failed: " + e.getMessage());
        }
    }

    // =====================================================================
    // MESSAGE HANDLERS
    // =====================================================================
    
    /**
     * Handle agent registration and status updates
     */
    private class AgentRegistrationHandler extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                MessageTemplate.MatchProtocol("agent-registration")
            );
            
            ACLMessage msg = receive(mt);
            if (msg != null) {
                handleAgentRegistration(msg);
            } else {
                block();
            }
        }
        
        private void handleAgentRegistration(ACLMessage msg) {
            try {
                // SECURITY: Validate message with OPA policy
                String senderName = msg.getSender().getLocalName();
                String receiverName = securityContext != null ? securityContext.agentName : myAgent.getLocalName();
                boolean messageAllowed = securityManager.validateMessageWithOPA(msg, senderName, receiverName);
                
                if (!messageAllowed) {
                    System.out.println("┌─ REGISTRATION BLOCKED ────────────────────────────");
                    System.out.println("│  🚫 OPA Policy Violation");
                    System.out.println("│  From:     " + senderName);
                    System.out.println("│  To:       " + myAgent.getLocalName());
                    System.out.println("│  Action:   Agent Registration");
                    System.out.println("└──────────────────────────────────────────────────");
                    sendRegistrationAck(msg.getSender(), "ERROR", "Security policy denies registration");
                    return;
                }
                
                String content = msg.getContent();
                AID senderAID = msg.getSender();
                
                System.out.println("[" + myAgent.getLocalName() + "] Received registration from: " + 
                    senderAID.getLocalName() + " - " + content);
                
                // Parse registration content
                if (content.contains("RegisterAgent")) {
                    String agentType = extractValue(content, ":type");
                    String capability = extractValue(content, ":capability");
                    String ffa = extractValue(content, ":ffa");
                    
                    // Register the agent
                    AgentStatus status = new AgentStatus(agentType, capability);
                    managedAgents.put(senderAID, status);
                    
                    if (ffa != null && !ffa.isEmpty()) {
                        agentFFAs.put(senderAID.getLocalName(), ffa);
                    }
                    
                    // Send acknowledgement
                    sendRegistrationAck(senderAID, "SUCCESS", "Agent registered successfully");
                }
                
            } catch (Exception e) {
                System.err.println("[" + myAgent.getLocalName() + "] Error handling registration: " + e.getMessage());
                sendRegistrationAck(msg.getSender(), "ERROR", "Registration failed: " + e.getMessage());
            }
        }
    }
    
    /**
     * Handle task assignment and coordination
     */
    private class TaskAssignmentHandler extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                MessageTemplate.MatchProtocol("task-assignment")
            );
            
            ACLMessage msg = receive(mt);
            if (msg != null) {
                handleTaskRequest(msg);
            } else {
                block();
            }
        }
        
        private void handleTaskRequest(ACLMessage msg) {
            try {
                String content = msg.getContent();
                System.out.println("[" + myAgent.getLocalName() + "] Received task request: " + content);
                
                // Create and assign task
                String taskId = "TASK-" + (taskIdCounter++);
                String taskType = extractValue(content, ":operation");
                String priority = extractValue(content, ":priority");
                
                ProductionTask task = new ProductionTask(taskId, taskType, priority != null ? priority : "MEDIUM");
                
                // Find suitable agent
                AID selectedAgent = findSuitableAgent(taskType);
                if (selectedAgent != null) {
                    task.assignedAgent = selectedAgent;
                    task.status = "ASSIGNED";
                    activeTasks.put(taskId, task);
                    
                    // Send task assignment
                    assignTaskToAgent(selectedAgent, task);
                    
                    // Send confirmation to requester
                    sendTaskAssignmentConfirmation(msg.getSender(), taskId, "ASSIGNED");
                } else {
                    sendTaskAssignmentConfirmation(msg.getSender(), taskId, "NO_AGENT_AVAILABLE");
                }
                
            } catch (Exception e) {
                System.err.println("[" + myAgent.getLocalName() + "] Error handling task request: " + e.getMessage());
            }
        }
    }
    
    /**
     * Handle acknowledgements and status updates from agents
     */
    private class AcknowledgementHandler extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                MessageTemplate.MatchProtocol("acknowledgement")
            );
            
            ACLMessage msg = receive(mt);
            if (msg != null) {
                handleAcknowledgement(msg);
            } else {
                block();
            }
        }
        
        private void handleAcknowledgement(ACLMessage msg) {
            try {
                String content = msg.getContent();
                AID senderAID = msg.getSender();
                
                System.out.println("[" + myAgent.getLocalName() + "] Received acknowledgement from " + 
                    senderAID.getLocalName() + ": " + content);
                
                // Update agent heartbeat
                AgentStatus status = managedAgents.get(senderAID);
                if (status != null) {
                    status.lastHeartbeat = System.currentTimeMillis();
                }
                
                // Handle different acknowledgement types
                if (content.contains("TaskStarted")) {
                    handleTaskStartAck(senderAID, content);
                } else if (content.contains("TaskCompleted")) {
                    handleTaskCompletionAck(senderAID, content);
                } else if (content.contains("TaskFailed")) {
                    handleTaskFailureAck(senderAID, content);
                } else if (content.contains("StatusUpdate")) {
                    handleStatusUpdateAck(senderAID, content);
                } else if (content.contains("Heartbeat")) {
                    handleHeartbeatAck(senderAID, content);
                }
                
            } catch (Exception e) {
                System.err.println("[" + myAgent.getLocalName() + "] Error handling acknowledgement: " + e.getMessage());
            }
        }
    }
    
    /**
     * Monitor agent health and task progress
     */
    private class HeartbeatMonitor extends TickerBehaviour {
        public HeartbeatMonitor(Agent agent, long period) {
            super(agent, period);
        }
        
        @Override
        protected void onTick() {
            // Check agent health
            checkAgentHealth();
            
            // Monitor task progress
            monitorTaskProgress();
            
            // Send production status update
            broadcastProductionStatus();
        }
    }

    // =====================================================================
    // TASK MANAGEMENT METHODS
    // =====================================================================
    
    /**
     * Find suitable agent for task type
     */
    private AID findSuitableAgent(String taskType) {
        for (Map.Entry<AID, AgentStatus> entry : managedAgents.entrySet()) {
            AgentStatus status = entry.getValue();
            if (status.isOnline() && "IDLE".equals(status.status)) {
                if (isAgentSuitableForTask(status, taskType)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }
    
    /**
     * Check if agent is suitable for task
     */
    private boolean isAgentSuitableForTask(AgentStatus agentStatus, String taskType) {
        switch (taskType.toUpperCase()) {
            case "PICKUP":
            case "TRANSPORT":
            case "MATERIALHANDLING":
                return "ROBOT".equals(agentStatus.agentType) && 
                       agentStatus.capability.contains("MaterialHandling");
                       
            case "CONVEY":
            case "PRODUCTION":
            case "MATERIALTRANSPORT":
                return "CONVEYOR".equals(agentStatus.agentType) && 
                       agentStatus.capability.contains("MaterialTransport");
                       
            default:
                return true; // Generic task, any agent can handle
        }
    }
    
    /**
     * Assign task to specific agent
     */
    private void assignTaskToAgent(AID agentAID, ProductionTask task) {
        try {
            ACLMessage taskMsg = new ACLMessage(ACLMessage.REQUEST);
            taskMsg.addReceiver(agentAID);
            taskMsg.setProtocol("production-command");
            taskMsg.setContent(
                "(ProductionCommand " +
                ":task-id \"" + task.taskId + "\" " +
                ":operation \"" + task.taskType + "\" " +
                ":priority \"" + task.priority + "\" " +
                ":assigned-time \"" + new Date(task.assignedTime) + "\")"
            );
            
            send(taskMsg);
            
            // Update agent status
            AgentStatus agentStatus = managedAgents.get(agentAID);
            if (agentStatus != null) {
                agentStatus.status = "BUSY";
                agentStatus.currentTask = task.taskId;
            }
            
            System.out.println("[" + getLocalName() + "] Assigned task " + task.taskId + 
                " to agent " + agentAID.getLocalName());
                
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error assigning task: " + e.getMessage());
        }
    }

    // =====================================================================
    // ACKNOWLEDGEMENT HANDLERS
    // =====================================================================
    
    private void handleTaskStartAck(AID senderAID, String content) {
        String taskId = extractValue(content, ":task-id");
        ProductionTask task = activeTasks.get(taskId);
        if (task != null) {
            task.status = "IN_PROGRESS";
            System.out.println("[" + getLocalName() + "] Task " + taskId + " started by " + senderAID.getLocalName());
        }
    }
    
    private void handleTaskCompletionAck(AID senderAID, String content) {
        String taskId = extractValue(content, ":task-id");
        ProductionTask task = activeTasks.get(taskId);
        if (task != null) {
            task.status = "COMPLETED";
            task.completedTime = System.currentTimeMillis();
            
            // Update agent status back to idle
            AgentStatus agentStatus = managedAgents.get(senderAID);
            if (agentStatus != null) {
                agentStatus.status = "IDLE";
                agentStatus.currentTask = null;
            }
            
            System.out.println("[" + getLocalName() + "] Task " + taskId + " completed by " + 
                senderAID.getLocalName());
        }
    }
    
    private void handleTaskFailureAck(AID senderAID, String content) {
        String taskId = extractValue(content, ":task-id");
        String reason = extractValue(content, ":reason");
        
        ProductionTask task = activeTasks.get(taskId);
        if (task != null) {
            task.status = "FAILED";
            
            // Update agent status back to idle
            AgentStatus agentStatus = managedAgents.get(senderAID);
            if (agentStatus != null) {
                agentStatus.status = "IDLE";
                agentStatus.currentTask = null;
            }
            
            System.out.println("[" + getLocalName() + "] Task " + taskId + " failed by " + 
                senderAID.getLocalName() + " - Reason: " + reason);
                
            // Could implement task reassignment logic here
        }
    }
    
    private void handleStatusUpdateAck(AID senderAID, String content) {
        String status = extractValue(content, ":status");
        
        AgentStatus agentStatus = managedAgents.get(senderAID);
        if (agentStatus != null) {
            agentStatus.status = status;
            System.out.println("[" + getLocalName() + "] Status update from " + 
                senderAID.getLocalName() + ": " + status);
        }
    }
    
    private void handleHeartbeatAck(AID senderAID, String content) {
        AgentStatus agentStatus = managedAgents.get(senderAID);
        if (agentStatus != null) {
            agentStatus.lastHeartbeat = System.currentTimeMillis();
        }
    }

    // =====================================================================
    // UTILITY METHODS
    // =====================================================================
    
    /**
     * Send registration acknowledgement
     */
    private void sendRegistrationAck(AID agentAID, String status, String message) {
        try {
            ACLMessage ack = new ACLMessage(ACLMessage.INFORM);
            ack.addReceiver(agentAID);
            ack.setProtocol("agent-registration");
            ack.setContent("(RegistrationAck :status \"" + status + "\" :message \"" + message + "\")");
            send(ack);
            
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error sending registration ack: " + e.getMessage());
        }
    }
    
    /**
     * Send task assignment confirmation
     */
    private void sendTaskAssignmentConfirmation(AID requesterAID, String taskId, String status) {
        try {
            ACLMessage confirmation = new ACLMessage(ACLMessage.INFORM);
            confirmation.addReceiver(requesterAID);
            confirmation.setProtocol("task-assignment");
            confirmation.setContent("(TaskAssignmentConfirmation :task-id \"" + taskId + "\" :status \"" + status + "\")");
            send(confirmation);
            
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error sending task confirmation: " + e.getMessage());
        }
    }
    
    /**
     * Check agent health and handle offline agents
     */
    private void checkAgentHealth() {
        List<AID> offlineAgents = new ArrayList<>();
        
        for (Map.Entry<AID, AgentStatus> entry : managedAgents.entrySet()) {
            if (!entry.getValue().isOnline()) {
                offlineAgents.add(entry.getKey());
                System.out.println("[" + getLocalName() + "] Agent " + 
                    entry.getKey().getLocalName() + " appears to be offline");
            }
        }
        
        // Handle offline agents
        for (AID offlineAgent : offlineAgents) {
            handleOfflineAgent(offlineAgent);
        }
    }
    
    /**
     * Handle offline agent detection
     */
    private void handleOfflineAgent(AID offlineAgent) {
        AgentStatus status = managedAgents.get(offlineAgent);
        if (status != null && status.currentTask != null) {
            // Reassign active task
            ProductionTask task = activeTasks.get(status.currentTask);
            if (task != null) {
                System.out.println("[" + getLocalName() + "] Reassigning task " + 
                    task.taskId + " from offline agent " + offlineAgent.getLocalName());
                
                // Find alternative agent
                AID alternativeAgent = findSuitableAgent(task.taskType);
                if (alternativeAgent != null) {
                    task.assignedAgent = alternativeAgent;
                    assignTaskToAgent(alternativeAgent, task);
                }
            }
        }
        
        status.status = "OFFLINE";
        status.currentTask = null;
    }
    
    /**
     * Monitor task progress and timeouts
     */
    private void monitorTaskProgress() {
        long currentTime = System.currentTimeMillis();
        
        for (ProductionTask task : activeTasks.values()) {
            if ("IN_PROGRESS".equals(task.status)) {
                long taskDuration = currentTime - task.assignedTime;
                
                // Check for task timeout (5 minutes for example)
                if (taskDuration > 300000) {
                    System.out.println("[" + getLocalName() + "] Task " + task.taskId + 
                        " timeout detected - Duration: " + (taskDuration/1000) + "s");
                    
                    // Could implement timeout handling logic here
                }
            }
        }
    }
    
    /**
     * Broadcast production status to interested parties
     */
    private void broadcastProductionStatus() {
        int totalAgents = managedAgents.size();
        int onlineAgents = (int) managedAgents.values().stream().mapToLong(s -> s.isOnline() ? 1 : 0).sum();
        int activeTasks = (int) this.activeTasks.values().stream().mapToLong(t -> "IN_PROGRESS".equals(t.status) ? 1 : 0).sum();
        
        if (totalAgents > 0) {
            System.out.println("[" + getLocalName() + "] Production Status - Agents: " + 
                onlineAgents + "/" + totalAgents + " online, Active Tasks: " + activeTasks);
        }
    }
    
    /**
     * Extract value from ACL message content
     */
    private String extractValue(String content, String key) {
        try {
            int startIndex = content.indexOf(key + " \"");
            if (startIndex != -1) {
                startIndex += key.length() + 2;
                int endIndex = content.indexOf("\"", startIndex);
                if (endIndex != -1) {
                    return content.substring(startIndex, endIndex);
                }
            }
            
            // Try without quotes
            startIndex = content.indexOf(key + " ");
            if (startIndex != -1) {
                startIndex += key.length() + 1;
                int endIndex = content.indexOf(" ", startIndex);
                if (endIndex == -1) endIndex = content.indexOf(")", startIndex);
                if (endIndex != -1) {
                    return content.substring(startIndex, endIndex);
                }
            }
        } catch (Exception e) {
            System.err.println("Error extracting value for " + key + ": " + e.getMessage());
        }
        return null;
    }
    
    /**
     * Production Coordination Behaviour - High-level production planning and optimization
     */
    private class ProductionCoordinationBehaviour extends TickerBehaviour {
        public ProductionCoordinationBehaviour(Agent agent, long period) {
            super(agent, period);
        }
        
        @Override
        protected void onTick() {
            coordinateProduction();
        }
        
        private void coordinateProduction() {
            try {
                System.out.println("[" + myAgent.getLocalName() + "] 🏭 Coordinating production activities...");
                
                // Production coordination logic
                optimizeTaskAllocation();
                balanceWorkload();
                monitorProductionEfficiency();
                
            } catch (Exception e) {
                System.err.println("[" + myAgent.getLocalName() + "] Error in production coordination: " + e.getMessage());
            }
        }
        
        /**
         * Optimize task allocation across available agents
         */
        private void optimizeTaskAllocation() {
            int totalAgents = managedAgents.size();
            int idleAgents = (int) managedAgents.values().stream()
                .filter(status -> "IDLE".equals(status.status) && status.isOnline())
                .count();
            
            if (totalAgents > 0) {
                double efficiency = (double) idleAgents / totalAgents * 100;
                System.out.println("[" + myAgent.getLocalName() + "] 📊 Agent efficiency: " + 
                    String.format("%.1f%% (%d/%d agents idle)", efficiency, idleAgents, totalAgents));
                
                // If too many agents are idle, could trigger new production tasks
                if (efficiency > 80 && activeTasks.size() < 3) {
                    System.out.println("[" + myAgent.getLocalName() + "] 🚀 High idle capacity - Consider new production tasks");
                }
            }
        }
        
        /**
         * Balance workload across agent types
         */
        private void balanceWorkload() {
            Map<String, Integer> workloadByType = new HashMap<>();
            
            for (AgentStatus status : managedAgents.values()) {
                String type = status.agentType;
                int currentLoad = workloadByType.getOrDefault(type, 0);
                if ("BUSY".equals(status.status)) {
                    currentLoad++;
                }
                workloadByType.put(type, currentLoad);
            }
            
            // Report workload distribution
            if (!workloadByType.isEmpty()) {
                System.out.println("[" + myAgent.getLocalName() + "] ⚖️ Workload distribution: " + workloadByType);
            }
        }
        
        /**
         * Monitor overall production efficiency
         */
        private void monitorProductionEfficiency() {
            int completedTasks = (int) activeTasks.values().stream()
                .filter(task -> "COMPLETED".equals(task.status))
                .count();
            
            int failedTasks = (int) activeTasks.values().stream()
                .filter(task -> "FAILED".equals(task.status))
                .count();
            
            int totalProcessedTasks = completedTasks + failedTasks;
            
            if (totalProcessedTasks > 0) {
                double successRate = (double) completedTasks / totalProcessedTasks * 100;
                System.out.println("[" + myAgent.getLocalName() + "] 📈 Production success rate: " + 
                    String.format("%.1f%% (%d completed, %d failed)", successRate, completedTasks, failedTasks));
            }
        }
    }
    
    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
            System.out.println("[" + getLocalName() + "] Production Agent Manager shutting down");
        } catch (FIPAException e) {
            System.err.println("[" + getLocalName() + "] Error during shutdown: " + e.getMessage());
        }
    }
}