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
    
    // Default constructor for JADE agent creation
    public ConveyorAgent() {
        // JADE will call setup() after construction
    }
    
    // Legacy constructor for backward compatibility
    public ConveyorAgent(UaVariableNode producedNode, int conveyorId) {
        this.producedNode = producedNode;
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
            }
        }
        
        System.out.println("Agent " + getLocalName() + " started managing Conveyor " + conveyorId + " with interval: " + AGENT_INTERVAL + "ms");
        
        // STEP 1: Authenticate with Keycloak
        System.out.println("🔐 Authenticating Conveyor" + conveyorId + " with Keycloak...");
        securityManager = FederationSecurityManager.getInstance();
        securityContext = securityManager.authenticateWithKeycloak("ConveyorAgent", "conveyor");
        
        if (securityContext == null) {
            System.err.println("❌ Keycloak authentication failed for ConveyorAgent");
            System.err.println("⚠️ Falling back to local authentication");
            securityManager.registerSecureAgent(getLocalName(), "Stakeholder3_ConveyorContainer", "Main-Container");
        } else {
            System.out.println("✅ Authenticated as: " + securityContext.agentName);
            System.out.println("   Organization: " + securityContext.companyId);
            System.out.println("   Security Level: " + securityContext.level);
        }
        
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
            // Monitor and manage this conveyor's production
            if (myConveyorReference != null) {
                monitorConveyorProduction();
            }
        }
    };

    private void monitorConveyorProduction() {
        // This method can be extended to add conveyor-specific logic
        // For now, it maintains the same logic as the original implementation
        if (getProduced()) {
            System.out.println("Conveyor " + conveyorId + " is currently producing");
            
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
                System.out.println("[" + getLocalName() + "] Federation enabled with FFA: " + myFFA);
                
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
        
        System.out.println("[" + getLocalName() + "] Initializing horizontal federation with peer conveyors...");
        
        // Pattern for finding other conveyor agents
        String peerPattern = "EU/Plant7.Manufacturing.Production.OpcUA.MultiAgentSystem.Conveyor*::MaterialProduction.*";
        System.out.println("[" + getLocalName() + "] Peer discovery pattern: " + peerPattern);
    }
    
    /**
     * Initialize vertical federation with production control systems
     */
    private void initializeVerticalFederation() {
        if (!federationEnabled) return;
        
        System.out.println("[" + getLocalName() + "] Initializing vertical federation with production control...");
        
        // Pattern for finding production control systems
        String controlPattern = "EU/Plant7.Manufacturing.Production.**.ProductionControl::MaterialFlow";
        System.out.println("[" + getLocalName() + "] Control system pattern: " + controlPattern);
    }
    
    /**
     * Broadcast production status to federation members
     */
    private void broadcastProductionStatus() {
        // This would send production status updates to interested federation members
        // Implementation would depend on specific federation coordination requirements
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
            System.out.println("Conveyor " + conveyorId + " produced status set to: " + produced);
        } catch (Exception e) {
            System.err.println("Error setting produced value for conveyor " + conveyorId + ": " + e.getMessage());
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
                
                send(heartbeat);
                
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
