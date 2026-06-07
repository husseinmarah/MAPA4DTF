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
 * Integrates with Federation Address Protocol (FAP a.k.a. FCP) for agent coordination
 */
public class ProductionManagerAgent extends Agent {

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
        public int completedTasks = 0; // NEW: Track completed tasks per agent
        public int productsProduced = 0; // NEW: Track products produced by conveyors
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

            // NEW: Get trust score and report to TrustManager
            double initialTrustScore = securityManager.getAgentTrustScore(keycloakUsername);
            reportInitialTrustScore(initialTrustScore);
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
        // addBehaviour(new TaskCompletionReportHandler()); // NEW: Handle completion
        // reports
        addBehaviour(new HeartbeatMonitor(this, 10000));
        addBehaviour(new ProductionCoordinationBehaviour(this, 3000)); // 3 seconds for very fast task distribution

        // Add delayed behavior to log OPA authorization summary after agents have
        // authenticated
        addBehaviour(new jade.core.behaviours.WakerBehaviour(this, 8000) {
            @Override
            protected void onWake() {
                logOPAAuthorizationSummary();
            }
        });

        System.out.println("✅ " + getLocalName() + " ready (Production coordination active)");
    }

    /**
     * Initialize federation capabilities
     */
    private void initializeFederation() {
        try {
            // Request FFA allocation for management role
            myFFA = FederationHelper.requestFFAAllocation(this, "ProductionManager", 1,
                    "ProductionCoordination.Primary");

            if (myFFA != null) {
                federationEnabled = true;
                // Initialize hierarchical federation workflow
                FederationHelper.initializeFederationWorkflow(
                        this, "hierarchical-federation", "EU/Plant7", "Manufacturing",
                        "ProductionCoordination.Primary");
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

    /**
     * Log OPA authorization summary showing which agents are enabled/disabled
     */
    private void logOPAAuthorizationSummary() {
        try {
            System.out.println("\n╔═════════════════════════════════════════════════════════════════════════╗");
            System.out.println("║           OPA POLICY AUTHORIZATION SUMMARY                              ║");
            System.out.println("╚═════════════════════════════════════════════════════════════════════════╝");
            System.out.println("Time: " + java.time.Instant.now());
            System.out.println();

            // Check robot agents
            System.out.println("┌─ ROBOT AGENTS ───────────────────────────────────────────┐");
            int enabledRobots = 0;
            int disabledRobots = 0;

            for (int i = 1; i <= milo.opcua.server.SystemConfig.NUM_ROBOTS; i++) {
                String agentName = "RobotAgent" + i;
                boolean canAccess = securityManager.canAccessService(agentName, "robot_operation");

                if (canAccess) {
                    System.out.println("│  ✅ " + agentName + " - ENABLED (OPA: robot_operation allowed)");
                    enabledRobots++;
                } else {
                    System.out.println("│  🚫 " + agentName + " - DISABLED (OPA: robot_operation denied)");
                    disabledRobots++;
                }
            }
            System.out.println("│  Summary: " + enabledRobots + " enabled, " + disabledRobots + " disabled");
            System.out.println("└──────────────────────────────────────────────────────────┘");
            System.out.println();

            // Check conveyor agents
            System.out.println("┌─ CONVEYOR AGENTS ────────────────────────────────────────┐");
            int enabledConveyors = 0;
            int disabledConveyors = 0;

            for (int i = 1; i <= milo.opcua.server.SystemConfig.NUM_INPUT_CONVEYORS; i++) {
                String agentName = "ConveyorAgent" + i;
                boolean canAccess = securityManager.canAccessService(agentName, "conveyor_access");

                if (canAccess) {
                    System.out.println("│  ✅ " + agentName + " - ENABLED (OPA: conveyor_access allowed)");
                    enabledConveyors++;
                } else {
                    System.out.println("│  🚫 " + agentName + " - DISABLED (OPA: conveyor_access denied)");
                    disabledConveyors++;
                }
            }
            System.out.println("│  Summary: " + enabledConveyors + " enabled, " + disabledConveyors + " disabled");
            System.out.println("└──────────────────────────────────────────────────────────┘");
            System.out.println();

            // Overall summary
            int totalAgents = enabledRobots + disabledRobots + enabledConveyors + disabledConveyors;
            int totalEnabled = enabledRobots + enabledConveyors;
            int totalDisabled = disabledRobots + disabledConveyors;

            System.out.println("╔═════════════════════════════════════════════════════════════════════════╗");
            System.out.println("║  TOTAL: " + totalEnabled + "/" + totalAgents + " agents ENABLED by OPA policy");
            if (totalDisabled > 0) {
                System.out.println("║  ⚠️  " + totalDisabled + " agent(s) DISABLED by OPA policy");
            }
            System.out.println("╚═════════════════════════════════════════════════════════════════════════╝");
            System.out.println();

        } catch (Exception e) {
            System.err.println("Error logging OPA authorization summary: " + e.getMessage());
        }
    }

    /**
     * Sends the initial trust score to the TrustManagerAgent.
     * 
     * @param score The initial trust score.
     */
    private void reportInitialTrustScore(double score) {
        try {
            // Find TrustManagerAgent through Directory Facilitator
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("TrustManagement");
            template.addServices(sd);

            DFAgentDescription[] results = DFService.search(this, template);

            if (results.length > 0) {
                AID trustManager = results[0].getName();

                ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                msg.addReceiver(trustManager);
                msg.setProtocol("initial-trust-score");
                msg.setContent("(:agent-id \"" + getLocalName() + "\" :score " + score + ")");
                send(msg);
                System.out.println("📈 " + getLocalName() + " - Reported initial trust score: " + score + ", Receiver: "
                        + trustManager);
            } else {
                System.err.println("⚠️ " + getLocalName()
                        + " - TrustManagerAgent not found in DF. Cannot report initial trust score.");
            }
        } catch (Exception e) {
            System.err.println(
                    "❌ " + getAID().getLocalName() + " - Error reporting initial trust score: " + e.getMessage());
        }
    }

    /**
     * Sends a trust update message to the TrustManagerAgent.
     * 
     * @param outcome The outcome of the task, e.g., "SUCCESS" or "FAILURE".
     */
    private void sendTrustUpdate(String outcome) {
        try {
            // Find TrustManagerAgent through Directory Facilitator
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("TrustManagement");
            template.addServices(sd);

            DFAgentDescription[] results = DFService.search(this, template);

            if (results.length > 0) {
                AID trustManager = results[0].getName();

                if (trustManager == null) {
                    System.err.println("ERROR: TrustManager AID is null after getting it from DF");
                    return;
                }

                ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                msg.addReceiver(trustManager);
                msg.setProtocol("trust-update");
                msg.setContent("(:agent-id \"" + getLocalName() + "\" :outcome \"" + outcome + "\")");
                send(msg);
                System.out.println("📈 " + getLocalName() + " - Sent trust update: " + outcome);
            } else {
                System.err.println("⚠️ " + getLocalName() + " - TrustManagerAgent not found in DF.");
            }
        } catch (Exception e) {
            System.err.println("❌ " + getAID().getLocalName() + " - Error sending trust update: " + e.getMessage());
            e.printStackTrace();
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
                    MessageTemplate.MatchProtocol("agent-registration"));

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
                    MessageTemplate.MatchProtocol("task-assignment"));

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
            MessageTemplate mt = MessageTemplate.and((MessageTemplate.MatchPerformative(ACLMessage.INFORM)),
                    MessageTemplate.or(MessageTemplate.MatchProtocol("task-completion-report"),
                            MessageTemplate.MatchProtocol("acknowledgement")));

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

                // Update agent heartbeat
                AgentStatus agentStatus = managedAgents.get(senderAID);
                if (agentStatus != null) {
                    agentStatus.lastHeartbeat = System.currentTimeMillis();

                    // Parse status from heartbeat and update the agent's state
                    String reportedStatus = extractValue(content, ":status");
                    if (reportedStatus != null && !reportedStatus.isEmpty()) {
                        agentStatus.status = reportedStatus;
                    }

                    // Track products produced by conveyors
                    if ("PRODUCING".equals(reportedStatus) && "CONVEYOR".equals(agentStatus.agentType)) {
                        agentStatus.productsProduced++;
                    }
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
                    System.out.println("💓 [" + myAgent.getLocalName() + "] Received Heartbeat from "
                            + senderAID.getLocalName() + " - Status: "
                            + (agentStatus != null ? agentStatus.status : "UNKNOWN") + " - Content: " + content);
                    return; // Heartbeat handled, no further processing needed
                }

            } catch (Exception e) {
                System.err
                        .println("[" + myAgent.getLocalName() + "] Error handling acknowledgement: " + e.getMessage());
            }
        }
    }

    /**
     * Handle task completion reports from robots
     * Tracks which robots have completed tasks and assigns new tasks to idle robots
     */
    private class TaskCompletionReportHandler extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                    MessageTemplate.MatchProtocol("task-completion-report"));

            ACLMessage msg = receive(mt);
            if (msg != null) {
                handleTaskCompletionReport(msg);
            } else {
                block();
            }
        }

        private void handleTaskCompletionReport(ACLMessage msg) {
            try {
                String content = msg.getContent();
                AID senderAID = msg.getSender();

                System.out.println("📋 [" + myAgent.getLocalName() + "] Received task completion report from "
                        + senderAID.getLocalName());

                // Update agent status
                AgentStatus status = managedAgents.get(senderAID);
                if (status != null) {
                    status.status = "IDLE"; // Robot is now idle and ready for new task
                    status.lastHeartbeat = System.currentTimeMillis();
                    status.currentTask = null;

                    // Increment task completion counter
                    Integer completedTasks = (Integer) status.properties.getOrDefault("completedTasks", 0);
                    status.properties.put("completedTasks", completedTasks + 1);

                    System.out.println("✅ [" + myAgent.getLocalName() + "] " + senderAID.getLocalName() +
                            " marked as " + status.status + " (Total completed: " + (completedTasks + 1) + ")");
                }

            } catch (Exception e) {
                System.err.println(
                        "[" + myAgent.getLocalName() + "] Error handling task completion report: " + e.getMessage());
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
                            ":assigned-time \"" + new Date(task.assignedTime) + "\")");

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
        AgentStatus agentStatus = managedAgents.get(senderAID);
        if (agentStatus != null) {
            agentStatus.status = "IDLE"; // Robot is now idle and ready for new task
            agentStatus.currentTask = null;

            // Increment task completion counter
            Integer completedTasks = (Integer) agentStatus.properties.getOrDefault("completedTasks", 0);
            agentStatus.properties.put("completedTasks", completedTasks + 1);

            System.out.println("✅ [" + getLocalName() + "] " + senderAID.getLocalName() +
                    " marked as " + agentStatus.status + " (Total completed: " + (completedTasks + 1) + ")");
        }

        System.out.println("[" + getLocalName() + "] Task " + taskId + " completed by " +
                senderAID.getLocalName());
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
            confirmation
                    .setContent("(TaskAssignmentConfirmation :task-id \"" + taskId + "\" :status \"" + status + "\")");
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
                            " timeout detected - Duration: " + (taskDuration / 1000) + "s");

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
        int activeTasks = (int) this.activeTasks.values().stream()
                .mapToLong(t -> "IN_PROGRESS".equals(t.status) ? 1 : 0).sum();

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
                if (endIndex == -1)
                    endIndex = content.indexOf(")", startIndex);
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
     * Production Coordination Behaviour - High-level production planning and
     * optimization
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
                System.out.println("🏭 [" + myAgent.getLocalName() + "] Coordinating production activities...");

                // DISABLED: assignTasksToIdleRobots() - Conflicts with Contract Net Protocol
                // (CNP)
                //
                // CNP HANDLES TASK DISTRIBUTION AUTOMATICALLY:
                // - ConveyorAgents broadcast CFP when products are ready
                // - RobotAgents respond with proposals based on availability, priority,
                // distance
                // - ConveyorAgents evaluate proposals and select winner
                // - Only the winner moves to the conveyor
                //
                // ProductionManager should NOT assign pickup tasks directly - it breaks CNP!
                // Instead, ProductionManager only monitors system health and efficiency.
                //
                // assignTasksToIdleRobots(); // COMMENTED OUT

                // Production coordination logic (monitoring only, no task assignment)
                optimizeTaskAllocation();
                balanceWorkload();
                monitorProductionEfficiency();

            } catch (Exception e) {
                System.err.println(
                        "[" + myAgent.getLocalName() + "] Error in production coordination: " + e.getMessage());
            }
        }

        /**
         * Assign tasks to idle robots that haven't received tasks recently
         * This ensures fair distribution and prevents robots from staying idle
         */
        private void assignTasksToIdleRobots() {
            // Find all idle robots
            List<Map.Entry<AID, AgentStatus>> idleRobots = new ArrayList<>();
            for (Map.Entry<AID, AgentStatus> entry : managedAgents.entrySet()) {
                AgentStatus status = entry.getValue();
                if ("ROBOT".equals(status.agentType) &&
                        "IDLE".equals(status.status) &&
                        status.isOnline()) {
                    idleRobots.add(entry);
                }
            }

            if (idleRobots.isEmpty()) {
                return; // No idle robots
            }

            // Sort by task completion count (prioritize robots with fewer completed tasks)
            idleRobots.sort((e1, e2) -> {
                Integer count1 = (Integer) e1.getValue().properties.getOrDefault("completedTasks", 0);
                Integer count2 = (Integer) e2.getValue().properties.getOrDefault("completedTasks", 0);
                return count1.compareTo(count2); // Ascending order - robots with fewer tasks first
            });

            System.out.println("🤖 [" + myAgent.getLocalName() + "] Found " + idleRobots.size() + " idle robot(s)");

            // Assign pickup task to each idle robot
            for (Map.Entry<AID, AgentStatus> entry : idleRobots) {
                AID robotAID = entry.getKey();
                AgentStatus status = entry.getValue();
                Integer completedTasks = (Integer) status.properties.getOrDefault("completedTasks", 0);

                // Create actual PICKUP task
                String taskId = "TASK-" + (taskIdCounter++);
                ProductionTask task = new ProductionTask(taskId, "PICKUP", "MEDIUM");
                task.parameters.put("operation", "pickup_from_conveyor");
                task.parameters.put("assigned_by", "ProductionAgentManager");
                task.assignedAgent = robotAID;
                activeTasks.put(taskId, task);

                // Send production command (correct protocol!)
                ACLMessage taskCmd = new ACLMessage(ACLMessage.REQUEST);
                taskCmd.addReceiver(robotAID);
                taskCmd.setProtocol("production-command");
                taskCmd.setContent(
                        "(ProductionCommand " +
                                ":task-id \"" + taskId + "\" " +
                                ":operation \"PICKUP\" " +
                                ":priority \"MEDIUM\" " +
                                ":assigned-time \"" + new java.util.Date() + "\")");

                myAgent.send(taskCmd);

                // Update agent status
                status.status = "BUSY";
                status.currentTask = taskId;

                System.out.println("✅ [" + myAgent.getLocalName() + "] Assigned " + taskId + " (PICKUP) to " +
                        robotAID.getLocalName() + " (completed: " + completedTasks + " tasks)");
            }
        }

        /**
         * Optimize task allocation across available agents
         */
        private void optimizeTaskAllocation() {
            long onlineAgents = managedAgents.values().stream()
                    .filter(AgentStatus::isOnline)
                    .count();

            long idleAgents = managedAgents.values().stream()
                    .filter(s -> "IDLE".equals(s.status) && s.isOnline())
                    .count();

            if (onlineAgents > 0) {
                double idlePercentage = (double) idleAgents / onlineAgents * 100;
                System.out.println("📊 [" + myAgent.getLocalName() + "] Agent Idle Capacity: " +
                        String.format("%.1f%% (%d/%d agents idle)", idlePercentage, idleAgents, onlineAgents));

            }
        }

        /**
         * Balance workload across agent types
         */
        private void balanceWorkload() {
            Map<String, Integer> workloadByType = new HashMap<>();

            for (Map.Entry<AID, AgentStatus> entry : managedAgents.entrySet()) {
                AgentStatus status = entry.getValue();
                if (!status.isOnline())
                    continue;

                String type = status.agentType;
                int currentLoad = ("BUSY".equals(status.status) || "PRODUCING".equals(status.status)) ? 1 : 0;
                workloadByType.merge(type, currentLoad, Integer::sum);
            }

            // Report workload distribution
            if (!workloadByType.isEmpty()) {
                System.out.println("⚖️ [" + myAgent.getLocalName() + "] Workload distribution: " + workloadByType);
            }
        }

        /**
         * Monitor overall production efficiency
         */
        private void monitorProductionEfficiency() {
            // NEW: Calculate stats based on agent-reported data
            long totalRobotTasks = managedAgents.values().stream()
                    .filter(s -> "ROBOT".equals(s.agentType))
                    .mapToInt(s -> s.completedTasks)
                    .sum();

            long totalProductsProduced = managedAgents.values().stream()
                    .filter(s -> "CONVEYOR".equals(s.agentType))
                    .mapToInt(s -> s.productsProduced)
                    .sum();

            if (totalRobotTasks > 0 || totalProductsProduced > 0) {
                System.out.println("📈 [" + myAgent.getLocalName() + "] Production Throughput: " +
                        totalRobotTasks + " tasks completed by robots, " +
                        totalProductsProduced + " products produced by conveyors.");
            } else {
                System.out.println("📈 [" + myAgent.getLocalName()
                        + "] Production Throughput: No tasks completed or products produced yet.");
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