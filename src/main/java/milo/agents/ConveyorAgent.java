package milo.agents;

import jade.core.Agent;
import jade.core.behaviours.ParallelBehaviour;
import jade.core.behaviours.TickerBehaviour;
import milo.opcua.server.CustomNamespace;
import milo.federation.FederationHelper;
import milo.security.FederationSecurityManager;
import milo.security.FederationSecurityManager.SecurityContext;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;

/**
 * Template-configurable Conveyor Agent with Federation Support
 * Each agent instance manages a specific conveyor based on its ID
 * Integrates with Federation Address Protocol (FAP) and Federation Fractal Address (FFA)
 */
public class ConveyorAgent extends Agent {
    
    // =====================================================================
    // CONFIGURATION - Agent-specific
    // =====================================================================
    private static final int AGENT_INTERVAL = 1000; // Fixed interval
    private UaVariableNode producedNode;
    private UaVariableNode enabledNode;
    private int conveyorId;
    private ConveyorAgent myConveyorReference; // Reference to conveyor in the namespace
    
    // =====================================================================
    // FEDERATION SUPPORT
    // =====================================================================
    private String myFFA; // This agent's Federation Fractal Address
    private boolean federationEnabled = false; // Whether federation is active
    
    // =====================================================================
    // SECURITY
    // =====================================================================
    private FederationSecurityManager securityManager;
    private SecurityContext securityContext; // Keycloak authentication context
    private boolean enabledConveyor = false; // OPA authorization status
    
    // Default constructor for JADE agent creation
    public ConveyorAgent() {
        // JADE will call setup() after construction
    }
    
    // Legacy constructor for backward compatibility
    public ConveyorAgent(UaVariableNode producedNode, int conveyorId) {
        this.producedNode = producedNode;
        this.conveyorId = conveyorId;
    }
    
    // Constructor with enabled node
    public ConveyorAgent(UaVariableNode producedNode, UaVariableNode enabledNode, int conveyorId) {
        this.producedNode = producedNode;
        this.enabledNode = enabledNode;
        this.conveyorId = conveyorId;
    }

    // =====================================================================
    // AGENT LIFECYCLE
    // =====================================================================
    @Override
    protected void setup() {
        // Get conveyor ID from arguments
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            conveyorId = (Integer) args[0];
            // Get reference to this specific conveyor from the namespace
            if (conveyorId > 0 && conveyorId <= CustomNamespace.inputConveyors.size()) {
                myConveyorReference = CustomNamespace.inputConveyors.get(conveyorId - 1);
                this.producedNode = myConveyorReference.producedNode;
                this.enabledNode = myConveyorReference.enabledNode;
            }
        }
        
        // STEP 1: Detect container and authenticate with Keycloak
        String containerName = "unknown";
        try {
            containerName = getContainerController().getContainerName();
            System.out.println("┌─ CONTAINER DETECTION ─────────────────────────────");
            System.out.println("│  Agent: " + getLocalName());
            System.out.println("│  Detected Container: " + containerName);
            System.out.println("└───────────────────────────────────────────────────");
        } catch (Exception e) {
            System.err.println("⚠️ Could not detect container name: " + e.getMessage());
        }
        
        // Use agent's local name as Keycloak username for agent-level blocking
        String keycloakUsername = getLocalName();  // e.g., "ConveyorAgent1", "ConveyorAgent2"
        String keycloakPassword = "conveyor";
        String organization = "Stakeholder3_ConveyorContainer";
        
        // Verify we're in the correct container
        if (!containerName.equals("Stakeholder3_ConveyorContainer") && !containerName.equals("Main-Container")) {
            System.err.println("⚠️ ConveyorAgent running in unexpected container: " + containerName);
        }
        
        System.out.println("┌─ AUTHENTICATION MAPPING ──────────────────────────");
        System.out.println("│  Agent Name:    " + getLocalName());
        System.out.println("│  Container:     " + containerName);
        System.out.println("│  Keycloak User: " + keycloakUsername);
        System.out.println("│  Organization:  " + organization);
        System.out.println("└───────────────────────────────────────────────────");
        
        securityManager = FederationSecurityManager.getInstance();
        securityContext = securityManager.authenticateWithKeycloak(keycloakUsername, keycloakPassword);
        
        if (securityContext == null) {
            System.err.println("┌─ AUTHENTICATION FALLBACK ────────────────────────");
            System.err.println("│  ⚠️ Keycloak authentication failed");
            System.err.println("│  Agent: " + getLocalName());
            System.err.println("│  User: " + keycloakUsername);
            System.err.println("│  Using local authentication");
            System.err.println("└──────────────────────────────────────────────────");
            securityManager.registerSecureAgent(getLocalName(), organization, containerName);
        } else {
            // Link the agent's local name to the authenticated context
            securityManager.linkAgentToContext(getLocalName(), keycloakUsername);
        }
        
        // Initialize the enabled node with default value (false)
        initializeEnabledNode();
        
        // Add small delay to ensure ProductionAgentManager is ready
        addBehaviour(new jade.core.behaviours.WakerBehaviour(this, 2000) {
            @Override
            protected void onWake() {
                // Initialize federation capabilities
                initializeFederation();
            }
        });
        
        // Start main behaviors
        ParallelBehaviour parallelBehaviour = new ParallelBehaviour();
        parallelBehaviour.addSubBehaviour(conveyorBehavior);
        parallelBehaviour.addSubBehaviour(new ConveyorProductionCommandHandler()); // Handle production commands
        parallelBehaviour.addSubBehaviour(new ConveyorHeartbeatBehaviour(this, 10000)); // Send heartbeat every 10 seconds
        addBehaviour(parallelBehaviour);
        
        // Register with ProductionAgentManager
        registerWithProductionManager();
    }

    // =====================================================================
    // MAIN CONVEYOR BEHAVIOR
    // =====================================================================
    TickerBehaviour conveyorBehavior = new TickerBehaviour(this, AGENT_INTERVAL) {
        @Override
        public void onTick() {
            // 0. Check OPA authorization and update enabled status
            updateConveyorEnabledStatus();
            
            // Monitor and manage this conveyor's production only if enabled
            if (myConveyorReference != null && enabledConveyor) {
                monitorConveyorProduction();
            }
        }
    };

    /**
     * Initialize the enabled node with default value (false)
     * This ensures the OPC-UA variable has a known initial state
     */
    private void initializeEnabledNode() {
        if (enabledNode != null) {
            try {
                // Set initial value to false (disabled by default until OPA authorizes)
                enabledNode.setValue(new DataValue(new Variant(false)));
                System.out.println("┌─ CONVEYOR INITIALIZATION ────────────────────────");
                System.out.println("│  Conveyor: " + getLocalName());
                System.out.println("│  Enabled Node initialized to: false");
                System.out.println("│  Status: Waiting for OPA authorization");
                System.out.println("└──────────────────────────────────────────────────");
            } catch (Exception e) {
                System.err.println("❌ Error initializing enabled node for " + getLocalName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Update conveyor enabled status based on OPA authorization
     * If OPA allows the conveyor to operate, set enabled = true
     * Otherwise, set enabled = false
     */
    private void updateConveyorEnabledStatus() {
        try {
            if (securityManager == null) {
                enabledConveyor = false;
                return;
            }
            
            // Refresh token if needed
            boolean tokenRefreshed = securityManager.refreshTokenIfNeeded(getLocalName());
            if (tokenRefreshed) {
                System.out.println("🔄 " + getLocalName() + " - Token refreshed successfully");
            }
            
            // Check if agent can access conveyor operation service
            boolean authorized = securityManager.canAccessService(getLocalName(), "conveyor_access");
            
            // Update the enabled status based on authorization
            if (authorized != enabledConveyor) {
                enabledConveyor = authorized;
                
                // Write to OPC-UA node
                if (enabledNode != null) {
                    try {
                        enabledNode.setValue(new DataValue(new Variant(enabledConveyor)));
                    } catch (Exception e) {
                        System.err.println("❌ Error writing enabled status to OPC-UA for " + getLocalName() + ": " + e.getMessage());
                    }
                }
                
                if (authorized) {
                    System.out.println("┌─ CONVEYOR STATUS UPDATE ─────────────────────────");
                    System.out.println("│  ✅ ENABLED");
                    System.out.println("│  Time:     " + java.time.Instant.now());
                    System.out.println("│  Conveyor: " + getLocalName());
                    System.out.println("│  Status:   OPA authorization granted");
                    System.out.println("└──────────────────────────────────────────────────");
                } else {
                    System.out.println("┌─ CONVEYOR STATUS UPDATE ─────────────────────────");
                    System.out.println("│  🚫 DISABLED");
                    System.out.println("│  Time:     " + java.time.Instant.now());
                    System.out.println("│  Conveyor: " + getLocalName());
                    System.out.println("│  Status:   OPA authorization denied");
                    System.out.println("└──────────────────────────────────────────────────");
                }
            }
            
        } catch (Exception e) {
            System.err.println("⚠️ " + getLocalName() + " - Authorization check error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void monitorConveyorProduction() {
        // This method can be extended to add conveyor-specific logic
        // For now, it maintains the same logic as the original implementation
        if (getProduced()) {
            // System.out.println("Conveyor " + conveyorId + " is currently producing");
            
            // If federation is enabled, broadcast production status
            if (federationEnabled) {
                broadcastProductionStatus();
            }
        }
    }
    
    /**
     * Initialize federation capabilities for this conveyor agent
     */
    private void initializeFederation() {
        try {
            System.out.println("[" + getLocalName() + "] Initializing federation capabilities...");
            
            // Request FFA allocation from Federation Address Manager
            String capability = "MaterialProduction.Input";
            myFFA = FederationHelper.requestFFAAllocation(this, "Conveyor", conveyorId, capability);
            
            if (myFFA != null) {
                federationEnabled = true;
                System.out.println("┌─ FEDERATION ENABLED ──────────────────────────────");
                System.out.println("│  Agent:      " + getLocalName());
                System.out.println("│  FFA:        " + myFFA);
                System.out.println("│  Capability: " + capability);
                System.out.println("└───────────────────────────────────────────────────");
                
                // Add federation maintenance behavior
                addBehaviour(new ConveyorFederationMaintenance());
                
                // Initialize horizontal federation with other conveyors
                initializeHorizontalFederation();
                
                // Initialize vertical federation with production systems
                initializeVerticalFederation();
                
            } else {
                System.out.println("[" + getLocalName() + "] Operating without federation (FAM not available)");
                federationEnabled = false;
            }
            
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Federation initialization failed: " + e.getMessage());
            federationEnabled = false;
        }
    }
    
    /**
     * Initialize horizontal federation with peer conveyors
     */
    private void initializeHorizontalFederation() {
        if (!federationEnabled) return;
        
        String peerPattern = "EU/Plant7.Manufacturing.Production.OpcUA.MultiAgentSystem.Conveyor*::MaterialProduction.*";
        
        System.out.println("┌─ HORIZONTAL FEDERATION (Peer-to-Peer) ───────────");
        System.out.println("│  Agent:    " + getLocalName());
        System.out.println("│  Target:   Other conveyor agents (same level)");
        System.out.println("│  Pattern:  " + peerPattern);
        System.out.println("│  Purpose:  Coordinate material production flow");
        System.out.println("└───────────────────────────────────────────────────");
    }
    
    /**
     * Initialize vertical federation with production control systems
     */
    private void initializeVerticalFederation() {
        if (!federationEnabled) return;
        
        String controlPattern = "EU/Plant7.Manufacturing.Production.**.ProductionControl::MaterialFlow";
        
        System.out.println("┌─ VERTICAL FEDERATION (Hierarchical) ─────────────");
        System.out.println("│  Agent:    " + getLocalName());
        System.out.println("│  Target:   Production control systems");
        System.out.println("│  Pattern:  " + controlPattern);
        System.out.println("│  Purpose:  Receive production commands & report");
        System.out.println("└───────────────────────────────────────────────────");
    }
    
    /**
     * Broadcast production status to federation members (Horizontal Federation)
     * Notifies all authorized RobotAgents when a new product is ready for pickup
     */
    private void broadcastProductionStatus() {
        try {
            // Only broadcast if product is ready
            if (!getProduced()) {
                return;
            }
            
            // Create notification message
            jade.lang.acl.ACLMessage notification = new jade.lang.acl.ACLMessage(jade.lang.acl.ACLMessage.INFORM);
            notification.setProtocol("product-ready-notification");
            
            // Find all RobotAgents and check OPA authorization before adding them
            int authorizedRobots = 0;
            for (int i = 1; i <= milo.opcua.server.CustomNamespace.robots.size(); i++) {
                String robotAgentName = "RobotAgent" + i;
                
                // OPA CHECK: Can this conveyor communicate with the robot?
                if (securityManager != null && securityManager.canCommunicateWith(getLocalName(), robotAgentName)) {
                    notification.addReceiver(new jade.core.AID(robotAgentName, jade.core.AID.ISLOCALNAME));
                    authorizedRobots++;
                    System.out.println("🔗 " + getLocalName() + " → " + robotAgentName + " (OPA: Communication allowed)");
                } else {
                    System.out.println("🚫 " + getLocalName() + " ✗ " + robotAgentName + " (OPA: Communication blocked)");
                }
            }
            
            // Only send if there are authorized receivers
            if (authorizedRobots > 0) {
                String content = String.format(
                    "(ProductReady :conveyor \"%s\" :conveyorId %d :location \"%s\" :time \"%s\")",
                    getLocalName(),
                    conveyorId,
                    "InputConveyor #" + conveyorId,
                    java.time.Instant.now()
                );
                
                notification.setContent(content);
                send(notification);
                
                System.out.println("┌─ PRODUCT NOTIFICATION ───────────────────────────");
                System.out.println("│  📢 BROADCAST");
                System.out.println("│  Time:      " + java.time.Instant.now());
                System.out.println("│  Conveyor:  " + getLocalName() + " (#" + conveyorId + ")");
                System.out.println("│  Recipients: " + authorizedRobots + " authorized robots");
                System.out.println("│  Message:   Product ready for pickup");
                System.out.println("└──────────────────────────────────────────────────");
            } else {
                System.out.println("⚠️ " + getLocalName() + " - No authorized robots for notification");
            }
            
        } catch (Exception e) {
            System.err.println("❌ " + getLocalName() + " - Error broadcasting production status: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    // =====================================================================
    // GETTERS - Clean interface
    // =====================================================================
    public UaVariableNode getProducedNode() {
        return producedNode;
    }
    
    public int getConveyorId() {
        return conveyorId;
    }
    
    // =====================================================================
    // CONVEYOR OPERATIONS - Well organized
    // =====================================================================
    public boolean getProduced() {
        try {
            DataValue value = producedNode.getValue();
            if (value != null && value.getValue() != null && value.getValue().getValue() != null) {
                return (Boolean) value.getValue().getValue();
            }
        } catch (Exception e) {
            System.err.println("Error getting produced value for conveyor " + conveyorId + ": " + e.getMessage());
        }
        return false;
    }
    
    public void setProduced(boolean produced) {
        try {
            producedNode.setValue(new DataValue(new Variant(produced)));
            if (!produced) {
                // Only log when product is picked up (status changes to false)
                System.out.println("┌─ CONVEYOR STATUS UPDATE ─────────────────────────");
                System.out.println("│  🏭 PRODUCT PICKED UP");
                System.out.println("│  Time:     " + java.time.Instant.now());
                System.out.println("│  Conveyor: " + getLocalName() + " (#" + conveyorId + ")");
                System.out.println("│  Status:   Produced → false");
                System.out.println("└──────────────────────────────────────────────────");
            }
        } catch (Exception e) {
            System.err.println("❌ Error setting produced value for conveyor " + conveyorId + ": " + e.getMessage());
        }
    }
    
    // =====================================================================
    // UTILITY METHODS - Template friendly
    // =====================================================================
    public String getConveyorInfo() {
        return "Conveyor " + conveyorId + " (Produced: " + getProduced() + ")";
    }
    
    public boolean isProducing() {
        return getProduced();
    }
    
    public void startProduction() {
        setProduced(true);
    }
    
    public void stopProduction() {
        setProduced(false);
    }
    
    // =====================================================================
    // FEDERATION BEHAVIORS - Handle federation protocol operations
    // =====================================================================
    
    /**
     * Maintains federation lease and handles conveyor-specific federation tasks
     */
    private class ConveyorFederationMaintenance extends TickerBehaviour {
        
        public ConveyorFederationMaintenance() {
            super(ConveyorAgent.this, 60000); // Update lease every minute
        }
        
        @Override
        protected void onTick() {
            if (federationEnabled && myFFA != null) {
                // Update FFA lease to keep federation address active
                boolean success = FederationHelper.updateFFALease(ConveyorAgent.this);
                if (!success) {
                    System.err.println("[" + getLocalName() + "] Failed to update FFA lease");
                }
                
                // Report conveyor status to federation
                reportConveyorStatus();
            }
        }
        
        private void reportConveyorStatus() {
            // Report conveyor production status to interested federation members
            // This could include production managers, quality control systems, etc.
            if (getProduced()) {
                System.out.println("[" + getLocalName() + "] Federation status: Conveyor " + conveyorId + " producing");
            }
        }
    }
    
    // =====================================================================
    // PRODUCTION MANAGEMENT INTEGRATION - ACKNOWLEDGEMENT SUPPORT
    // =====================================================================
    
    /**
     * Register with ProductionAgentManager
     */
    private void registerWithProductionManager() {
        try {
            System.out.println("[" + getLocalName() + "] Registering with ProductionAgentManager...");
            
            // Find ProductionAgentManager through Directory Facilitator
            jade.domain.FIPAAgentManagement.DFAgentDescription template = new jade.domain.FIPAAgentManagement.DFAgentDescription();
            jade.domain.FIPAAgentManagement.ServiceDescription sd = new jade.domain.FIPAAgentManagement.ServiceDescription();
            sd.setType("ManufacturingCoordination");
            template.addServices(sd);
            
            jade.domain.FIPAAgentManagement.DFAgentDescription[] results = jade.domain.DFService.search(this, template);
            
            if (results.length > 0) {
                jade.core.AID productionManager = results[0].getName();
                
                // Send registration message
                jade.lang.acl.ACLMessage registration = new jade.lang.acl.ACLMessage(jade.lang.acl.ACLMessage.INFORM);
                registration.addReceiver(productionManager);
                registration.setProtocol("agent-registration");
                registration.setContent(
                    "(RegisterAgent " +
                    ":type \"CONVEYOR\" " +
                    ":capability \"" + determineConveyorCapability() + "\" " +
                    ":ffa \"" + (myFFA != null ? myFFA : "NONE") + "\" " +
                    ":conveyor-id \"" + conveyorId + "\")"
                );
                
                send(registration);
                System.out.println("[" + getLocalName() + "] Registration sent to ProductionAgentManager");
            } else {
                System.out.println("[" + getLocalName() + "] ProductionAgentManager not found in Directory Facilitator");
            }
            
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error registering with ProductionAgentManager: " + e.getMessage());
        }
    }
    
    /**
     * Determine conveyor's primary capability
     */
    private String determineConveyorCapability() {
        if (conveyorId == 1) {
            return "MaterialTransport.Input";
        } else if (conveyorId <= 3) {
            return "MaterialTransport.Production";
        } else {
            return "MaterialTransport.Output";
        }
    }
    
    /**
     * Conveyor Production Command Handler - Handles production commands from ProductionAgentManager
     */
    private class ConveyorProductionCommandHandler extends jade.core.behaviours.CyclicBehaviour {
        @Override
        public void action() {
            jade.lang.acl.MessageTemplate mt = jade.lang.acl.MessageTemplate.and(
                jade.lang.acl.MessageTemplate.MatchPerformative(jade.lang.acl.ACLMessage.REQUEST),
                jade.lang.acl.MessageTemplate.MatchProtocol("production-command")
            );
            
            jade.lang.acl.ACLMessage msg = receive(mt);
            if (msg != null) {
                handleProductionCommand(msg);
            } else {
                block();
            }
        }
        
        private void handleProductionCommand(jade.lang.acl.ACLMessage msg) {
            try {
                // SECURITY: Validate message with OPA policy
                String senderName = msg.getSender().getLocalName();
                String receiverName = securityContext != null ? securityContext.agentName : myAgent.getLocalName();
                boolean messageAllowed = securityManager.validateMessageWithOPA(msg, senderName, receiverName);
                
                if (!messageAllowed) {
                    System.out.println("🚫 ConveyorAgent blocked command from " + senderName + " (OPA policy denied)");
                    sendTaskFailedAck(msg.getSender(), "UNKNOWN", "Security policy denies this command");
                    return;
                }
                
                String content = msg.getContent();
                System.out.println("[" + myAgent.getLocalName() + "] Received production command: " + content);
                
                // Extract task information
                String taskId = extractValue(content, ":task-id");
                String operation = extractValue(content, ":operation");
                String priority = extractValue(content, ":priority");
                
                // Send task started acknowledgement
                sendTaskStartedAck(msg.getSender(), taskId);
                
                // Execute the task
                boolean taskSuccess = executeConveyorTask(taskId, operation, priority);
                
                // Send completion acknowledgement
                if (taskSuccess) {
                    sendTaskCompletedAck(msg.getSender(), taskId, "Conveyor task executed successfully");
                } else {
                    sendTaskFailedAck(msg.getSender(), taskId, "Conveyor task execution failed");
                }
                
            } catch (Exception e) {
                System.err.println("[" + myAgent.getLocalName() + "] Error handling production command: " + e.getMessage());
                String taskId = extractValue(msg.getContent(), ":task-id");
                if (taskId != null) {
                    sendTaskFailedAck(msg.getSender(), taskId, "Error: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Conveyor Heartbeat Behaviour - Regular status updates to ProductionAgentManager
     */
    private class ConveyorHeartbeatBehaviour extends TickerBehaviour {
        public ConveyorHeartbeatBehaviour(Agent agent, long period) {
            super(agent, period);
        }
        
        @Override
        protected void onTick() {
            sendConveyorHeartbeat();
        }
    }
    
    /**
     * Execute conveyor-specific production task
     */
    private boolean executeConveyorTask(String taskId, String operation, String priority) {
        try {
            System.out.println("[" + getLocalName() + "] Executing conveyor task " + taskId + 
                " - Operation: " + operation + ", Priority: " + priority);
            
            // Determine task execution based on operation type
            switch (operation.toUpperCase()) {
                case "START_PRODUCTION":
                case "PRODUCTION":
                    return startConveyorProduction();
                    
                case "STOP_PRODUCTION":
                    return stopConveyorProduction();
                    
                case "TRANSPORT":
                case "CONVEY":
                    return executeTransportOperation();
                    
                case "STATUS_CHECK":
                    return checkConveyorStatus();
                    
                default:
                    System.out.println("[" + getLocalName() + "] Executing generic conveyor task: " + operation);
                    // Simulate task execution time
                    Thread.sleep(1500);
                    return true;
            }
            
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error executing conveyor task " + taskId + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Start conveyor production
     */
    private boolean startConveyorProduction() {
        try {
            System.out.println("[" + getLocalName() + "] Starting conveyor production for conveyor " + conveyorId);
            
            // Simulate production start
            if (producedNode != null) {
                // Set production to true
                DataValue newValue = new DataValue(new Variant(true));
                producedNode.setValue(newValue);
                System.out.println("[" + getLocalName() + "] Conveyor " + conveyorId + " production started");
                return true;
            } else {
                System.err.println("[" + getLocalName() + "] Conveyor node not available");
                return false;
            }
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error starting production: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Stop conveyor production
     */
    private boolean stopConveyorProduction() {
        try {
            System.out.println("[" + getLocalName() + "] Stopping conveyor production for conveyor " + conveyorId);
            
            // Simulate production stop
            if (producedNode != null) {
                // Set production to false
                DataValue newValue = new DataValue(new Variant(false));
                producedNode.setValue(newValue);
                System.out.println("[" + getLocalName() + "] Conveyor " + conveyorId + " production stopped");
                return true;
            } else {
                System.err.println("[" + getLocalName() + "] Conveyor node not available");
                return false;
            }
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error stopping production: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Execute transport operation
     */
    private boolean executeTransportOperation() {
        try {
            System.out.println("[" + getLocalName() + "] Executing transport operation on conveyor " + conveyorId);
            // Simulate transport time
            Thread.sleep(2000);
            return true;
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error in transport operation: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Check conveyor status
     */
    private boolean checkConveyorStatus() {
        try {
            boolean isProducing = getProduced();
            System.out.println("[" + getLocalName() + "] Conveyor " + conveyorId + " status check - Producing: " + isProducing);
            return true;
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error checking conveyor status: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Send heartbeat to ProductionAgentManager
     */
    private void sendConveyorHeartbeat() {
        try {
            // Find ProductionAgentManager
            jade.domain.FIPAAgentManagement.DFAgentDescription template = new jade.domain.FIPAAgentManagement.DFAgentDescription();
            jade.domain.FIPAAgentManagement.ServiceDescription sd = new jade.domain.FIPAAgentManagement.ServiceDescription();
            sd.setType("ManufacturingCoordination");
            template.addServices(sd);
            
            jade.domain.FIPAAgentManagement.DFAgentDescription[] results = jade.domain.DFService.search(this, template);
            
            if (results.length > 0) {
                jade.core.AID productionManager = results[0].getName();
                
                // Create heartbeat message
                jade.lang.acl.ACLMessage heartbeat = new jade.lang.acl.ACLMessage(jade.lang.acl.ACLMessage.INFORM);
                heartbeat.addReceiver(productionManager);
                heartbeat.setProtocol("acknowledgement");
                
                String currentStatus = getProduced() ? "PRODUCING" : "IDLE";
                heartbeat.setContent(
                    "(Heartbeat " +
                    ":agent-id \"" + getLocalName() + "\" " +
                    ":status \"" + currentStatus + "\" " +
                    ":timestamp \"" + new java.util.Date() + "\" " +
                    ":conveyor-id \"" + conveyorId + "\" " +
                    ":production-state \"" + currentStatus + "\")"
                );
                
                // SECURITY: Validate outgoing heartbeat with OPA policy
                String senderName = getLocalName();
                String receiverName = productionManager.getLocalName();
                boolean messageAllowed = securityManager.validateMessageWithOPA(heartbeat, senderName, receiverName);
                
                if (messageAllowed) {
                    send(heartbeat);
                } else {
                    // Silently skip - blocked agents shouldn't send heartbeats
                    // System.out.println("🚫 " + getLocalName() + " heartbeat blocked by policy");
                }
                
            }
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error sending heartbeat: " + e.getMessage());
        }
    }
    
    /**
     * Send task started acknowledgement
     */
    private void sendTaskStartedAck(jade.core.AID manager, String taskId) {
        try {
            jade.lang.acl.ACLMessage ack = new jade.lang.acl.ACLMessage(jade.lang.acl.ACLMessage.INFORM);
            ack.addReceiver(manager);
            ack.setProtocol("acknowledgement");
            ack.setContent(
                "(TaskStarted " +
                ":task-id \"" + taskId + "\" " +
                ":agent-id \"" + getLocalName() + "\" " +
                ":timestamp \"" + new java.util.Date() + "\")"
            );
            send(ack);
            
            System.out.println("[" + getLocalName() + "] Sent task started acknowledgement for task: " + taskId);
            
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error sending task started ack: " + e.getMessage());
        }
    }
    
    /**
     * Send task completed acknowledgement
     */
    private void sendTaskCompletedAck(jade.core.AID manager, String taskId, String details) {
        try {
            jade.lang.acl.ACLMessage ack = new jade.lang.acl.ACLMessage(jade.lang.acl.ACLMessage.INFORM);
            ack.addReceiver(manager);
            ack.setProtocol("acknowledgement");
            ack.setContent(
                "(TaskCompleted " +
                ":task-id \"" + taskId + "\" " +
                ":agent-id \"" + getLocalName() + "\" " +
                ":status \"SUCCESS\" " +
                ":details \"" + details + "\" " +
                ":completion-time \"" + new java.util.Date() + "\")"
            );
            send(ack);
            
            System.out.println("[" + getLocalName() + "] Sent task completed acknowledgement for task: " + taskId);
            
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error sending task completed ack: " + e.getMessage());
        }
    }
    
    /**
     * Send task failed acknowledgement
     */
    private void sendTaskFailedAck(jade.core.AID manager, String taskId, String reason) {
        try {
            jade.lang.acl.ACLMessage ack = new jade.lang.acl.ACLMessage(jade.lang.acl.ACLMessage.INFORM);
            ack.addReceiver(manager);
            ack.setProtocol("acknowledgement");
            ack.setContent(
                "(TaskFailed " +
                ":task-id \"" + taskId + "\" " +
                ":agent-id \"" + getLocalName() + "\" " +
                ":reason \"" + reason + "\" " +
                ":failure-time \"" + new java.util.Date() + "\")"
            );
            send(ack);
            
            System.out.println("[" + getLocalName() + "] Sent task failed acknowledgement for task: " + taskId);
            
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error sending task failed ack: " + e.getMessage());
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
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error extracting value for " + key + ": " + e.getMessage());
        }
        return null;
    }
}
