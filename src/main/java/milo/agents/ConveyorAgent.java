package milo.agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.ParallelBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import milo.opcua.server.CustomNamespace;
import milo.federation.FederationHelper;
import milo.security.FederationSecurityManager;
import milo.security.FederationSecurityManager.SecurityContext;
import milo.utils.DelayUtils;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.Iterator;

/**
 * Template-configurable Conveyor Agent with Federation Support
 * Each agent instance manages a specific conveyor based on its ID
 * Integrates with Federation Address Protocol (FAP) and Federation Fractal Address (FFA)
 */
public class ConveyorAgent extends Agent {
    
    // =====================================================================
    // CONFIGURATION - Agent-specific
    // =====================================================================
    private static final int AGENT_INTERVAL = 5500; // Fixed interval
    private UaVariableNode producedNode;
    private UaVariableNode enabledNode;
    private int conveyorId;
    private ConveyorAgent myConveyorReference; // Reference to conveyor in the namespace
    private String agentName;
    
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
    
    // =====================================================================
    // TASK ASSIGNMENT (Contract Net Protocol)
    // =====================================================================
    private volatile boolean taskAssignmentInProgress = false; // Prevent multiple CFP broadcasts
    private java.util.Queue<RobotQueueEntry> pickupQueue = new java.util.LinkedList<>(); // Robots waiting to pick up
    private String currentPickingRobot = null; // Robot currently at pickup location
    private String winnerRobotAgent = null; // Winner robot from CNP that must exit before next CFP
    private volatile boolean waitingForWinnerExit = false; // True when waiting for winner to leave conveyor area
    
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

            // NEW: Get trust score and report to TrustManager
            double initialTrustScore = securityManager.getAgentTrustScore(keycloakUsername);
            reportInitialTrustScore(initialTrustScore);
        }
        
        // Initialize the enabled node with default value (false)
        initializeEnabledNode();
        
        // Add small delay to ensure ProductionAgentManager is ready
        addBehaviour(new WakerBehaviour(this, 2000) {
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
        parallelBehaviour.addSubBehaviour(new RobotExitNotificationHandler()); // Handle robot exit notifications
        parallelBehaviour.addSubBehaviour(new PickupCompletionHandler()); // Handle pickup completion requests
        addBehaviour(parallelBehaviour);

        // Register with Directory Facilitator so other agents can find this conveyor
        // Register with ProductionAgentManager
        registerWithProductionManager();
    }


    // =====================================================================
    // MAIN CONVEYOR BEHAVIOR
    // =====================================================================
    TickerBehaviour conveyorBehavior = new TickerBehaviour(this, AGENT_INTERVAL) {
        @Override
        public void onStart() {
            agentName = myAgent.getLocalName();
            DelayUtils.randomDelay(10, 1000);
        }

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
            // SecurityManager now properly constructs OPA query with sender context for self-authorization
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
            
            // ALWAYS broadcast production status (federation or not)
            // Federation is just for FFA addressing; CFP can work without it
            broadcastProductionStatus();
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
     * Broadcast production status using Contract Net Protocol (CFP)
     * Sends Call-For-Proposals to authorized robots and waits for bids
     */
    private void broadcastProductionStatus() {
        try {
            // CRITICAL: Check if we're waiting for winner to exit the conveyor area
            // This prevents multiple robots from trying to pick up at the same time (collision prevention)
            if (waitingForWinnerExit) {
                // Periodic debug log to show we're waiting for winner to leave
                if (System.currentTimeMillis() % 5000 < 1000) {
                    System.out.println("┌─ CFP BLOCKED - WAITING FOR WINNER EXIT ─────────");
                    System.out.println("│  ⏸️ COLLISION PREVENTION ACTIVE");
                    System.out.println("│  Time:         " + java.time.Instant.now());
                    System.out.println("│  Conveyor:     " + getLocalName());
                    System.out.println("│  Winner Robot: " + winnerRobotAgent);
                    System.out.println("│  Status:       Must wait for " + winnerRobotAgent + " to leave");
                    System.out.println("│  Reason:       Prevents robot collisions at conveyor");
                    System.out.println("└──────────────────────────────────────────────────");
                }
                return;
            }
            
            // Only broadcast if product is ready and not already assigned
            if (!getProduced() || taskAssignmentInProgress) {
                // Periodic debug log to show why we're not broadcasting.
                // Only log if there's actually a product, to avoid spamming the console.
                if (getProduced() && System.currentTimeMillis() % 10000 < 1000) {
                    System.out.println("┌─ CFP BLOCKED - TASK ASSIGNMENT IN PROGRESS ─────────");
                    System.out.println("│  Conveyor: " + getLocalName());
                    System.out.println("│  Status:   Another CFP process is already running for this conveyor.");
                    System.out.println("⏸️ " + getLocalName() + " - Not broadcasting CFP (Produced: " + getProduced() + ", TaskInProgress: " + taskAssignmentInProgress + ")");
                }
                return;
            }
            
            System.out.println("📢 " + getLocalName() + " - Starting CFP broadcast (Product ready)");
            taskAssignmentInProgress = true;
            
            // Create CFP message
            ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
            cfp.setProtocol("pickup-task-cfp");
            cfp.setConversationId("pickup-" + conveyorId + "-" + System.currentTimeMillis());
            cfp.setReplyByDate(new java.util.Date(System.currentTimeMillis() + 2000)); // 2 second deadline
            
            // Find all RobotAgents and check OPA authorization before adding them
            int authorizedRobots = 0;
            int totalRobots = CustomNamespace.robots.size();
            System.out.println("🔍 " + getLocalName() + " - Checking " + totalRobots + " robots for CFP");
            
            // Search for RobotAgents via Directory Facilitator (works across containers)
            try {
                DFAgentDescription template = new DFAgentDescription();
                ServiceDescription sd = new ServiceDescription();
                sd.setType("MaterialHandling");
                template.addServices(sd);
                
                DFAgentDescription[] robotResults = jade.domain.DFService.search(this, template);
                System.out.println("📡 " + getLocalName() + " - Found " + robotResults.length + " robots via DF");
                
                for (DFAgentDescription result : robotResults) {
                    AID robotAID = result.getName();
                    String robotAgentName = robotAID.getLocalName();

                    // OPA CHECK: Can this conveyor communicate with the robot?
                    if (securityManager != null && securityManager.canCommunicateWith(getLocalName(), robotAgentName)) {
                        cfp.addReceiver(robotAID); // Use the full AID from DF (includes platform address)
                        authorizedRobots++;
                        System.out.println("✅ " + getLocalName() + " → " + robotAgentName + " Add as Receiver (OPA: Communication allowed)");
                    } else {
                        System.out.println("🚫 " + getLocalName() + " ✗ " + robotAgentName + " (OPA: Communication blocked)");
                    }
                }
            } catch (Exception e) {
                System.err.println("❌ " + getLocalName() + " - Error searching for robots via DF: " + e.getMessage());
                e.printStackTrace();
                
                // FALLBACK: Use local naming (only works if robots are in same container)
                for (int i = 1; i <= totalRobots; i++) {
                    String robotAgentName = "RobotAgent" + i;
                    if (securityManager != null && securityManager.canCommunicateWith(getLocalName(), robotAgentName)) {
                        AID receiver = new jade.core.AID(robotAgentName, AID.ISGUID);
                        cfp.addReceiver(receiver);
                        authorizedRobots++;
                        System.out.println("🔗 " + getLocalName() + " → " + robotAgentName + " (OPA: allowed, Fallback: local AID)");
                    }
                }
            }
            
            // Only send if there are authorized receivers
            if (authorizedRobots > 0) {
                String content = String.format(
                    "(PickupTaskCFP :conveyor \"%s\" :conveyorId %d :location \"%s\" :time \"%s\" :ffa \"%s\")",
                    getLocalName(),
                    conveyorId,
                    "InputConveyor #" + conveyorId,
                    java.time.Instant.now(), myFFA
                );
                
                cfp.setContent(content);
                send(cfp);
                
                System.out.println("┌─ PICKUP TASK CFP (Call-For-Proposals) ──────────");
                System.out.println("│  📢 CFP SENT");
                System.out.println("│  Time:          " + java.time.Instant.now());
                System.out.println("│  Conveyor:      " + getLocalName() + " (#" + conveyorId + ")");
                System.out.println("│  Recipients:    " + authorizedRobots + " authorized robots");
                System.out.println("│  Conversation:  " + cfp.getConversationId());
                System.out.println("│  Reply Deadline: 2 seconds");
                System.out.println("│  Message:       Requesting bids for pickup task");
                System.out.println("└──────────────────────────────────────────────────");
                
                // Start behavior to collect and evaluate proposals
                addBehaviour(new ProposalCollectorBehaviour(cfp.getConversationId(), authorizedRobots));
            } else {
                System.out.println("⚠️ " + getLocalName() + " - No authorized robots for CFP");
                taskAssignmentInProgress = false;
            }
            
        } catch (Exception e) {
            System.err.println("❌ " + getLocalName() + " - Error broadcasting CFP: " + e.getMessage());
            e.printStackTrace();
            taskAssignmentInProgress = false;
        }
    }

    /**
     * Sends a trust update message to the TrustManagerAgent.
     * @param outcome The outcome of the task, e.g., "SUCCESS" or "FAILURE".
     */
    private void sendTrustUpdate(String outcome) {
        try {
            // Find TrustManagerAgent through Directory Facilitator
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("TrustManagement");
            template.addServices(sd);

            System.out.println("Searching for TrustManagerAgent...");
            DFAgentDescription[] results = DFService.search(this, template);
            System.out.println("Found " + results.length + " results.");

            if (results.length > 0) {
                System.out.println("TrustManagerAgent found: " + results[0].getName());
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

    /**
     * Sends the initial trust score to the TrustManagerAgent.
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
                System.out.println("📈 " + getLocalName() + " - Reported initial trust score: " + score +", Receiver: " + trustManager);
            } else {
                System.err.println("⚠️ " + getLocalName() + " - TrustManagerAgent not found in DF. Cannot report initial trust score.");
            }
        } catch (Exception e) {
            System.err.println("❌ " + getAID().getLocalName() + " - Error reporting initial trust score: " + e.getMessage());
        }
    }
    
    // =====================================================================
    // GETTERS - Clean interface
    // =====================================================================
    public UaVariableNode getProducedNode() {
        return producedNode;
    }
    
    public UaVariableNode getEnabledNode() {
        return enabledNode;
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
                System.out.println("│  Conveyor: " + agentName + " (#" + conveyorId + ")");
                System.out.println("│  Status:   Produced → false");
                System.out.println("└──────────────────────────────────────────────────");
                // Report SUCCESS to TrustManagerAgent
                sendTrustUpdate("SUCCESS");
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
    
    /**
     * Check if conveyor is enabled by OPA policy
     * @return true if conveyor is authorized by OPA, false otherwise
     */
    public boolean isEnabled() {
        return enabledConveyor;
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
            System.out.println("[" + getLocalName() + "] Registering with Directory Facilitator and ProductionAgentManager...");

            // STEP 1: Register this conveyor with Directory Facilitator so RobotAgents can find it
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());
            ServiceDescription sd = new ServiceDescription();
            sd.setType("ConveyorService"); // Service for providing products
            sd.setName(getLocalName() + "-conveyor-service");
            sd.addLanguages("ffa");
            dfd.addServices(sd);
            try {
                DFService.register(this, dfd);
                System.out.println("✅ " + getLocalName() + " registered 'ConveyorService' with DF.");
            } catch (Exception e) {
                System.err.println("❌ " + getLocalName() + " DF registration failed: " + e.getMessage());
            }

            // STEP 2: Find ProductionAgentManager through Directory Facilitator
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sdSearch = new ServiceDescription();
            sdSearch.setType("ManufacturingCoordination");
            template.addServices(sdSearch);

            DFAgentDescription[] results = DFService.search(this, template);

            if (results.length > 0) {
                AID productionManager = results[0].getName();

                // Send registration message
                ACLMessage registration = new ACLMessage(ACLMessage.INFORM);
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
            MessageTemplate mt = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                MessageTemplate.MatchProtocol("production-command")
            );
            
            ACLMessage msg = receive(mt);
            if (msg != null) {
                handleProductionCommand(msg);
            } else {
                block();
            }
        }
        
        private void handleProductionCommand(ACLMessage msg) {
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
        public void onStart() {
            DelayUtils.randomDelay(10, 1000);
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
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("ManufacturingCoordination");
            template.addServices(sd);
            
            DFAgentDescription[] results = jade.domain.DFService.search(this, template);
            
            if (results.length > 0) {
                jade.core.AID productionManager = results[0].getName();
                
                // Create heartbeat message
                ACLMessage heartbeat = new ACLMessage(ACLMessage.INFORM);
                heartbeat.addReceiver(productionManager);
                heartbeat.setProtocol("acknowledgement");
                
                String currentStatus = getProduced() ? "PRODUCING" : "IDLE"; // PRODUCING if a product is ready
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
            ACLMessage ack = new ACLMessage(ACLMessage.INFORM);
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
            ACLMessage ack = new ACLMessage(ACLMessage.INFORM);
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
            ACLMessage ack = new ACLMessage(ACLMessage.INFORM);
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
            System.err.println("[" + getLocalName() + "] Error extracting value: " + e.getMessage());
        }
        return null;
    }
    
    // =====================================================================
    // CONTRACT NET PROTOCOL - PROPOSAL COLLECTION & EVALUATION
    // =====================================================================
    
    /**
     * Behavior to collect proposals from robots and select the best one
     */
    private class ProposalCollectorBehaviour extends jade.core.behaviours.SimpleBehaviour {
        private String conversationId;
        private int expectedProposals;
        private java.util.List<ACLMessage> proposals;
        private long deadline;
        private boolean done = false;
        
        public ProposalCollectorBehaviour(String conversationId, int expectedProposals) {
            this.conversationId = conversationId;
            this.expectedProposals = expectedProposals;
            this.proposals = new java.util.ArrayList<>();
            this.deadline = System.currentTimeMillis() + 2500; // 2.5 seconds to collect all proposals
        }
        
        @Override
        public void action() {
            // Use proper template to ONLY receive PROPOSE messages with our conversation ID
            MessageTemplate template = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                MessageTemplate.MatchConversationId(conversationId)
            );
            
            ACLMessage proposal = myAgent.receive(template);
            
            if (proposal != null) {
                proposals.add(proposal);
                System.out.println("✅ " + getLocalName() + " - MATCHED PROPOSAL from " + proposal.getSender().getLocalName() + 
                                   " (Total: " + proposals.size() + "/" + expectedProposals + ")");
            }
            
            // Check if we should finalize
            if (proposals.size() >= expectedProposals || System.currentTimeMillis() >= deadline) {
                System.out.println("⏰ " + getLocalName() + " - Collection complete: " + proposals.size() + " proposals, deadline reached: " + (System.currentTimeMillis() >= deadline));
                evaluateAndAssignTask();
                done = true;
            } else {
                block(100); // Check every 100ms
            }
        }
        
        @Override
        public boolean done() {
            return done;
        }
        
        /**
         * Evaluate proposals and assign task to best robot
         * CRITICAL: Must only execute ONCE per CFP to prevent multiple winners
         */
        private void evaluateAndAssignTask() {
            // GUARD: Prevent multiple evaluations for the same CFP
            if (done) {
                System.out.println("⚠️ " + getLocalName() + " - evaluateAndAssignTask() called but already done (conversationId: " + conversationId + ")");
                return;
            }
            
            try {
                if (proposals.isEmpty()) {
                    System.out.println("⚠️ " + getLocalName() + " - No proposals received for pickup task");
                    sendTrustUpdate("FAILURE"); // Report FAILURE
                    taskAssignmentInProgress = false;
                    done = true;
                    return;
                }
                
                // Parse proposals and rank them
                java.util.List<RobotProposal> robotProposals = new java.util.ArrayList<>();
                for (ACLMessage proposal : proposals) {
                    RobotProposal rp = parseProposal(proposal);
                    if (rp != null) {
                        robotProposals.add(rp);
                    }
                }
                
                if (robotProposals.isEmpty()) {
                    System.out.println("⚠️ " + getLocalName() + " - No valid proposals to evaluate");
                    taskAssignmentInProgress = false;
                    return;
                }
                
                // Sort proposals: higher priority first, then shorter distance
                java.util.Collections.sort(robotProposals, (a, b) -> {
                    // FAIRNESS: Use effective priority (priority + fairness bonus) for primary sorting
                    double effectivePriorityA = a.priority + a.fairnessBonus;
                    double effectivePriorityB = b.priority + b.fairnessBonus;

                    int priorityCompare = Double.compare(effectivePriorityB, effectivePriorityA);
                    if (priorityCompare != 0) {
                        return priorityCompare;
                    }

                    // If effective priorities are equal, use distance as a tie-breaker
                    return Double.compare(a.distance, b.distance);
                });
                
                // Select winner (first in sorted list)
                RobotProposal winner = robotProposals.get(0);
                
                System.out.println("┌─ PROPOSAL EVALUATION ────────────────────────────");
                System.out.println("│  📊 EVALUATION COMPLETE");
                System.out.println("│  Time:       " + java.time.Instant.now());
                System.out.println("│  Conveyor:   " + getLocalName() + " (#" + conveyorId + ")");
                System.out.println("│  Proposals:  " + robotProposals.size() + " received");
                System.out.println("│  Winner:     " + winner.robotAgent);
                System.out.println("│  Priority:   " + winner.priority);
                System.out.println("│  Effective Priority: " + (winner.priority + winner.fairnessBonus));
                System.out.println("└──────────────────────────────────────────────────");
                
                // Send ACCEPT-PROPOSAL to winner
                ACLMessage accept = winner.originalMessage.createReply();
                accept.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
                accept.setContent("(TaskAccepted :conveyor \"" + getLocalName() + "\" :location \"InputConveyor #" + conveyorId + "\")");
                myAgent.send(accept);
                
                System.out.println("✅ " + getLocalName() + " sent ACCEPT to " + winner.robotAgent);
                
                // Send REJECT-PROPOSAL to all others
                for (RobotProposal rp : robotProposals) {
                    if (!rp.robotAgent.equals(winner.robotAgent)) {
                        ACLMessage reject = rp.originalMessage.createReply();
                        reject.setPerformative(ACLMessage.REJECT_PROPOSAL);
                        reject.setContent("(TaskRejected :reason \"Another robot was selected\")");
                        myAgent.send(reject);
                        
                        System.out.println("❌ " + getLocalName() + " sent REJECT to " + rp.robotAgent);
                    }
                }
                
                // Mark winner and wait for them to exit conveyor area before next CFP
                winnerRobotAgent = winner.robotAgent;
                waitingForWinnerExit = true;
                
                System.out.println("┌─ WAITING FLAG SET ───────────────────────────────");
                System.out.println("│  ⏳ WAITING FOR WINNER EXIT");
                System.out.println("│  Time:         " + java.time.Instant.now());
                System.out.println("│  Conveyor:     " + getLocalName());
                System.out.println("│  Winner:       " + winnerRobotAgent);
                System.out.println("│  Flag Set:     waitingForWinnerExit = true");
                System.out.println("│  Next Step:    Waiting for task-complete notification");
                System.out.println("└──────────────────────────────────────────────────");
                
                // Mark as done to prevent re-execution
                done = true;
                taskAssignmentInProgress = false;
                
            } catch (Exception e) {
                System.err.println("❌ " + getLocalName() + " - Error evaluating proposals: " + e.getMessage());
                e.printStackTrace();
                taskAssignmentInProgress = false;
            }
        }
        
        /**
         * Parse proposal message to extract robot information
         */
        private RobotProposal parseProposal(ACLMessage proposal) {
            try {
                String content = proposal.getContent();
                String robotAgent = proposal.getSender().getLocalName();
                
                // Extract priority and distance from proposal
                int priority = extractIntValue(content, ":priority");
                double distance = extractDoubleValue(content, ":distance");
                double fairnessBonus = extractDoubleValue(content, ":fairnessBonus");

                return new RobotProposal(robotAgent, priority, distance, fairnessBonus, proposal);
                
            } catch (Exception e) {
                System.err.println("❌ " + getLocalName() + " - Error parsing proposal: " + e.getMessage());
                return null;
            }
        }
        
        private int extractIntValue(String content, String key) {
            try {
                int startIndex = content.indexOf(key + " ");
                if (startIndex != -1) {
                    startIndex += key.length() + 1;
                    int endIndex = content.indexOf(" ", startIndex);
                    if (endIndex == -1) endIndex = content.indexOf(")", startIndex);
                    if (endIndex != -1) {
                        return Integer.parseInt(content.substring(startIndex, endIndex).trim());
                    }
                }
            } catch (Exception e) {
                System.err.println("Error extracting int value: " + e.getMessage());
            }
            return 0;
        }
        
        private double extractDoubleValue(String content, String key) {
            try {
                int startIndex = content.indexOf(key + " ");
                if (startIndex != -1) {
                    startIndex += key.length() + 1;
                    int endIndex = content.indexOf(" ", startIndex);
                    if (endIndex == -1) endIndex = content.indexOf(")", startIndex);
                    if (endIndex != -1) {
                        return Double.parseDouble(content.substring(startIndex, endIndex).trim());
                    }
                }
            } catch (Exception e) {
                System.err.println("Error extracting double value: " + e.getMessage());
            }
            return Double.MAX_VALUE;
        }
    }
    
    /**
     * Helper class to store robot proposal information
     */
    private static class RobotProposal {
        final String robotAgent;
        final int priority;
        final double distance;
        final double fairnessBonus;
        final ACLMessage originalMessage;
        
        RobotProposal(String robotAgent, int priority, double distance, double fairnessBonus, ACLMessage originalMessage) {
            this.robotAgent = robotAgent;
            this.priority = priority;
            this.distance = distance;
            this.fairnessBonus = fairnessBonus;
            this.originalMessage = originalMessage;
        }
    }
    
    // =====================================================================
    // PICKUP QUEUE MANAGEMENT
    // =====================================================================
    
    /**
     * Helper class to store robot queue information
     */
    private static class RobotQueueEntry implements Comparable<RobotQueueEntry> {
        final String robotAgent;
        final int priority;
        final long arrivalTime;
        
        RobotQueueEntry(String robotAgent, int priority, long arrivalTime) {
            this.robotAgent = robotAgent;
            this.priority = priority;
            this.arrivalTime = arrivalTime;
        }
        
        @Override
        public int compareTo(RobotQueueEntry other) {
            // Higher priority first
            int priorityCompare = Integer.compare(other.priority, this.priority);
            if (priorityCompare != 0) {
                return priorityCompare;
            }
            // If priorities equal, earlier arrival first
            return Long.compare(this.arrivalTime, other.arrivalTime);
        }
    }

    
    /**
     * Register robot in pickup queue when it arrives at conveyor
     */
    public synchronized void registerForPickup(String robotAgent, int priority) {
        // Check if robot is already the current picker (reserved via CFP)
        if (robotAgent.equals(currentPickingRobot)) {
            System.out.println("✅ " + agentName + " - " + robotAgent + " already reserved as current picker");
            return;
        }
        
        // Check if robot is already in queue
        for (RobotQueueEntry entry : pickupQueue) {
            if (entry.robotAgent.equals(robotAgent)) {
                System.out.println("⚠️ " + agentName + " - " + robotAgent + " already in pickup queue");
                return;
            }
        }
        
        // Add to queue
        RobotQueueEntry entry = new RobotQueueEntry(robotAgent, priority, System.currentTimeMillis());
        pickupQueue.add(entry);
        
        // Sort queue by priority and arrival time
        java.util.List<RobotQueueEntry> sortedQueue = new java.util.ArrayList<>(pickupQueue);
        java.util.Collections.sort(sortedQueue);
        pickupQueue.clear();
        pickupQueue.addAll(sortedQueue);
        
        System.out.println("┌─ PICKUP QUEUE UPDATE ────────────────────────────");
        System.out.println("│  📋 ROBOT REGISTERED");
        System.out.println("│  Time:      " + java.time.Instant.now());
        System.out.println("│  Conveyor:  " + agentName + " (#" + conveyorId + ")");
        System.out.println("│  Robot:     " + robotAgent);
        System.out.println("│  Priority:  " + priority);
        System.out.println("│  Position:  " + (sortedQueue.indexOf(entry) + 1) + " of " + pickupQueue.size());
        System.out.println("│  Queue:     " + getQueueSummary());
        System.out.println("└──────────────────────────────────────────────────");
        
        // Check if this robot can pick up now
        processPickupQueue();
    }
    
    /**
     * Check if robot is next in queue and allow pickup
     */
    public synchronized boolean canPickup(String robotAgent) {
        // If no current picker, check if this robot is first in queue
        if (currentPickingRobot == null && !pickupQueue.isEmpty()) {
            RobotQueueEntry next = pickupQueue.peek();
            if (next != null && next.robotAgent.equals(robotAgent)) {
                currentPickingRobot = robotAgent;
                pickupQueue.poll(); // Remove from queue
                
                System.out.println("✅ " + agentName + " - " + robotAgent + " authorized for pickup (first in queue)");
                return true;
            }
        }
        
        // If this robot is the current picker
        if (robotAgent.equals(currentPickingRobot)) {
            return true;
        }
        
        System.out.println("⏸️ " + agentName + " - " + robotAgent + " must wait (Queue position: " + getQueuePosition(robotAgent) + ")");
        return false;
    }
    
    /**
     * Notify that robot has completed pickup
     */
    public synchronized void notifyPickupComplete(String robotAgent) {
        if (robotAgent.equals(currentPickingRobot)) {
            currentPickingRobot = null;
            
            System.out.println("✅ " + agentName + " - " + robotAgent + " completed pickup");
            
            // Process next robot in queue
            processPickupQueue();
        }
    }
    
    /**
     * Process the pickup queue and notify next robot
     */
    private synchronized void processPickupQueue() {
        if (currentPickingRobot == null && !pickupQueue.isEmpty()) {
            RobotQueueEntry next = pickupQueue.peek();
            if (next != null) {
                System.out.println("📢 " + agentName + " - Next in queue: " + next.robotAgent + " (Priority: " + next.priority + ")");
            }
        }
    }
    
    /**
     * Get robot's position in queue (1-based)
     */
    private int getQueuePosition(String robotAgent) {
        int position = 1;
        for (RobotQueueEntry entry : pickupQueue) {
            if (entry.robotAgent.equals(robotAgent)) {
                return position;
            }
            position++;
        }
        return -1; // Not in queue
    }
    
    /**
     * Get summary of current queue
     */
    private String getQueueSummary() {
        if (pickupQueue.isEmpty()) {
            return "empty";
        }
        
        StringBuilder sb = new StringBuilder();
        int position = 1;
        for (RobotQueueEntry entry : pickupQueue) {
            if (position > 1) sb.append(", ");
            sb.append(position).append(":").append(entry.robotAgent).append("(P").append(entry.priority).append(")");
            position++;
        }
        return sb.toString();
    }
//
//    /**
//     * Notify conveyor that robot has exited the conveyor area
//     * This allows the conveyor to send new CFP broadcasts without risk of collision
//     */
//    public synchronized void notifyRobotExit(String robotAgent) {
//        if (robotAgent.equals(winnerRobotAgent) && waitingForWinnerExit) {
//            System.out.println("┌─ ROBOT EXIT NOTIFICATION ────────────────────────");
//            System.out.println("│  🚀 ROBOT EXITED");
//            System.out.println("│  Time:     " + java.time.Instant.now());
//            System.out.println("│  Conveyor: " + getLocalName() + " (#" + conveyorId + ")");
//            System.out.println("│  Robot:    " + robotAgent);
//            System.out.println("│  Status:   Conveyor area clear - ready for next CFP");
//            System.out.println("└──────────────────────────────────────────────────");
//
//            waitingForWinnerExit = false;
//            winnerRobotAgent = null;
//        }
//    }
//
//    // =====================================================================
//    // TASK COMPLETE NOTIFICATION HANDLER
//    // =====================================================================
//
//    /**
//     * Handle task complete notifications from robots
//     * When a robot completes a task at drop-off location, it notifies conveyors
//     * This allows conveyors to immediately send new CFP if they have products waiting
//     */
//    private class TaskCompleteNotificationHandler extends CyclicBehaviour {
//        private long lastCheckTime = 0;
//
//        @Override
//        public void action() {
//            MessageTemplate mt = MessageTemplate.and(
//                MessageTemplate.MatchPerformative(ACLMessage.INFORM),
//                MessageTemplate.MatchProtocol("task-complete")
//            );
//
//            // Debug: Log that we're checking for messages (every 10 seconds)
//            long now = System.currentTimeMillis();
//            if (now - lastCheckTime > 10000) {
//                System.out.println("🔍 " + getLocalName() + " - Listening for task-complete notifications...");
//                lastCheckTime = now;
//            }
//
//            ACLMessage msg = receive(mt);
//            if (msg != null) {
//                handleTaskCompleteNotification(msg);
//            } else {
//                block();
//            }
//        }
//
//        private void handleTaskCompleteNotification(ACLMessage msg) {
//            try {
//                String content = msg.getContent();
//                String robotName = extractValue(content, ":completed-by");
//                String status = extractValue(content, ":status");
//                String idleRobotsStr = extractValue(content, ":idle-robots");
//                String availableRobots = extractValue(content, ":available-robots");
//
//                int idleRobotsCount = 0;
//                try {
//                    idleRobotsCount = Integer.parseInt(idleRobotsStr);
//                } catch (Exception e) {
//                    // Ignore parse error
//                }
//
//                System.out.println("┌─ TASK COMPLETE RECEIVED ─────────────────────────");
//                System.out.println("│  📬 NOTIFICATION RECEIVED");
//                System.out.println("│  Time:          " + java.time.Instant.now());
//                System.out.println("│  Conveyor:      " + getLocalName() + " (#" + conveyorId + ")");
//                System.out.println("│  From Robot:    " + robotName);
//                System.out.println("│  Status:        " + status);
//                System.out.println("│  Idle Robots:   " + idleRobotsCount);
//                System.out.println("│  Available:     [" + availableRobots + "]");
//                System.out.println("│");
//                System.out.println("│  Conveyor State Check:");
//                System.out.println("│    - Produced:              " + getProduced());
//                System.out.println("│    - TaskInProgress:        " + taskAssignmentInProgress);
//                System.out.println("│    - WaitingForWinnerExit:  " + waitingForWinnerExit);
//                System.out.println("│    - Idle Robots:           " + idleRobotsCount);
//
//                // Check if this conveyor has products waiting and idle robots are available
//                // Also trigger if we were waiting for winner to exit (now robots are available)
//                boolean shouldBroadcast = idleRobotsCount > 0 && !taskAssignmentInProgress &&
//                                         (getProduced() || waitingForWinnerExit);
//
//                if (shouldBroadcast) {
//                    System.out.println("│");
//                    System.out.println("│  ✅ CONDITIONS MET - Triggering CFP");
//
//                    // Clear waiting flag if it was set
//                    if (waitingForWinnerExit) {
//                        System.out.println("│");
//                        System.out.println("│  🔓 CLEARING WAITING FLAG");
//                        System.out.println("│     Old: waitingForWinnerExit = true");
//                        System.out.println("│     Old: winnerRobotAgent = " + winnerRobotAgent);
//                        waitingForWinnerExit = false;
//                        winnerRobotAgent = null;
//                        System.out.println("│     New: waitingForWinnerExit = false");
//                        System.out.println("│     New: winnerRobotAgent = null");
//                    }
//
//                    System.out.println("└──────────────────────────────────────────────────");
//
//                    // Trigger new CFP broadcast
//                    broadcastProductionStatus();
//                } else {
//                    System.out.println("│");
//                    System.out.println("│  ❌ CONDITIONS NOT MET - No CFP broadcast");
//
//                    StringBuilder reasons = new StringBuilder();
//                    if (idleRobotsCount == 0) {
//                        reasons.append("No idle robots; ");
//                    }
//                    if (taskAssignmentInProgress) {
//                        reasons.append("Task assignment in progress; ");
//                    }
//                    if (!getProduced() && !waitingForWinnerExit) {
//                        reasons.append("No product ready and not waiting for winner; ");
//                    }
//
//                    System.out.println("│  Reasons:       " + reasons.toString());
//                    System.out.println("└──────────────────────────────────────────────────");
//                }
//
//            } catch (Exception e) {
//                System.err.println("❌ " + getLocalName() + " - Error handling task complete notification: " + e.getMessage());
//                e.printStackTrace();
//            }
//        }
//    }
    
    // =====================================================================
    // ROBOT EXIT NOTIFICATION HANDLER
    // =====================================================================
    
    /**
     * Handle robot exit notifications
     * When a robot exits the conveyor area, clear the waiting flag so next CFP can be sent
     */
    private class RobotExitNotificationHandler extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                MessageTemplate.MatchProtocol("robot-exit")
            );
            
            ACLMessage msg = receive(mt);
            if (msg != null) {
                handleRobotExitNotification(msg);
            } else {
                block();
            }
        }
        
        private void handleRobotExitNotification(ACLMessage msg) {
            try {
                String content = msg.getContent();
                String robotName = extractValue(content, ":robot");
                
                // Check if this is the winner robot we were waiting for
                if (robotName != null && robotName.equals(winnerRobotAgent) && waitingForWinnerExit) {
                    System.out.println("┌─ ROBOT EXIT NOTIFICATION ────────────────────────");
                    System.out.println("│  🚀 WINNER ROBOT EXITED");
                    System.out.println("│  Time:      " + java.time.Instant.now());
                    System.out.println("│  Conveyor:  " + getLocalName());
                    System.out.println("│  Robot:     " + robotName);
                    System.out.println("│  Action:    Clearing waitingForWinnerExit flag");
                    System.out.println("│  Result:    Next CFP can now be sent");
                    System.out.println("└──────────────────────────────────────────────────");
                    
                    waitingForWinnerExit = false;
                    winnerRobotAgent = null;
                } else {
                    System.out.println("ℹ️ " + getLocalName() + " - Received exit notification from " + robotName + 
                                     " (not the winner " + winnerRobotAgent + ", ignoring)");
                }
                
            } catch (Exception e) {
                System.err.println("❌ " + getLocalName() + " - Error handling robot exit notification: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * Handles FIPA-Request messages from RobotAgents to complete the pickup process.
     */
    private class PickupCompletionHandler extends CyclicBehaviour {
        private final MessageTemplate mt = MessageTemplate.and(
            MessageTemplate.MatchProtocol("fipa-request"),
            MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                MessageTemplate.MatchOntology("conveyor-pickup")
            )
        );

        @Override
        public void action() {
            ACLMessage msg = myAgent.receive(mt);
            if (msg != null) {
                System.out.println("Received pickup completion request from " + msg.getSender().getLocalName());
                String content = msg.getContent();
                String robotName = extractValue(content, ":robot-name");

                if (robotName != null) {
                    // 1. Set produced to false
                    setProduced(false);

                    // 2. Notify that pickup is complete
                    notifyPickupComplete(robotName);

                    // 3. Send a confirmation reply
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.INFORM);
                    reply.setContent("(status :action pickup-complete :result success)");
                    myAgent.send(reply);
                } else {
                    // Send a failure reply
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.FAILURE);
                    reply.setContent("(status :action pickup-complete :result failure :reason \"missing-robot-name\")");
                    myAgent.send(reply);
                }
            } else {
                block();
            }
        }
    }
}



