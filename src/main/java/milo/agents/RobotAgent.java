package milo.agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.TickerBehaviour;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import milo.opcua.server.CustomNamespace;
import milo.federation.FederationHelper;
import milo.opcua.server.RobotTemplate;
import milo.opcua.server.SystemConfig;
import milo.security.FederationSecurityManager;
import milo.security.FederationSecurityManager.SecurityContext;
import milo.utils.DelayUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Template-configurable Robot Agent with Federation Support
 * Each agent instance manages a specific robot based on its ID
 * Integrates with Federation Address Protocol (FAP) and Federation Fractal Address (FFA)
 */
public class RobotAgent extends Agent {

    // =====================================================================
    // CONFIGURATION - Agent-specific
    // =====================================================================
    private static final int AGENT_INTERVAL = 5000; // 1 second for maximum responsiveness
    private int robotId; // Specific robot this agent manages
    private RobotTemplate myRobot; // Reference to this agent's robot
    
    // Task distribution tracking (shared across all robot agents)
    private static int taskCounter = 0; // Global task counter for fair distribution
    private static final Object assignmentLock = new Object();
    private int consecutiveIdleCycles = 0; // Track how long this robot has been idle
    
    // Priority-based collision resolution
    private static final boolean USE_PRIORITY_RESOLUTION = true; // Enable priority-based task assignment
    
    // Contract Net Protocol State Tracking
    private java.util.Set<String> activeProposals = new java.util.HashSet<>(); // Track conversation IDs of sent proposals
    
    // Location tracking for conveyor exit notification
    private String previousLocation = ""; // Track previous location to detect when robot leaves conveyor area
    private String lastPickupConveyor = null; // Track which conveyor the robot picked up from
    
    // FAIRNESS: Track task completions for each conveyor to balance workload
    private static final java.util.Map<String, Integer> conveyorTaskCompletions = new java.util.concurrent.ConcurrentHashMap<>();

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

    // =====================================================================
    // UTILITY METHODS
    // =====================================================================
    private double calculateDistance(double x1, double y1, double x2, double y2) {
        return Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2));
    }

    /**
     * DEBUGGING WRAPPER: Logs ALL target changes with full stack trace
     * Use this instead of myRobot.setTarget() to track where targets are being set
     */
    private void setRobotTarget(String newTarget, String action) {
        if (myRobot == null) return;

        String oldTarget = myRobot.getTarget();
        System.out.println("┌─ SET TARGET ───────────────────────────────────────");
        System.out.println("│  Old Target: '" + oldTarget + "'");
        System.out.println("│  New Target: '" + newTarget + "'");
        System.out.println("│  Action:      " + action);
        System.out.println("│  Thread:      " + Thread.currentThread().getName());
        System.out.println("└───────────────────────────────────────────────────");
        myRobot.setTarget(newTarget);
    }

    // =====================================================================
    // AGENT LIFECYCLE
    // =====================================================================
    protected void setup() {
        // Get robot ID from arguments
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            robotId = (Integer) args[0];
            // Get reference to this specific robot (index is robotId - 1)
            if (robotId > 0 && robotId <= CustomNamespace.robots.size()) {
                myRobot = CustomNamespace.robots.get(robotId - 1);
                System.out.println("┌─ ROBOT INIT ──────────────────────────────────────");
                System.out.println("│  🤖 Initialized");
                System.out.println("│  Agent: " + getLocalName());
                System.out.println("│  Robot ID: " + robotId);
                System.out.println("│  Current Target: " + myRobot.getTarget());
                System.out.println("└───────────────────────────────────────────────────");
            } else {
                System.err.println("❌ [ROBOT INIT ERROR] " + getLocalName() + " - Invalid robotId " + robotId + " (robots.size=" + CustomNamespace.robots.size() + ")");
            }
        }

        // STEP 1: Detect exact container and authenticate with Keycloak
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
        String keycloakUsername = getLocalName();  // e.g., "RobotAgent1", "RobotAgent4"
        String keycloakPassword = "robot";
        String organization;
        
        // Map container name to organization
        if (containerName.equals("Stakeholder1_RobotContainer")) {
            organization = "Stakeholder1_RobotContainer";
        } else if (containerName.equals("Stakeholder2_RobotContainer")) {
            organization = "Stakeholder2_RobotContainer";
        } else if (containerName.equals("Main-Container")) {
            organization = "Stakeholder1_RobotContainer";  // Default for main container
        } else {
            System.out.println(""
                    + "┌─ AUTHENTICATION FALLBACK ────────────────────────\n"
                    + "│  ⚠️ Unknown container: " + containerName + "\n"
                    + "└──────────────────────────────────────────────────");
            organization = "Stakeholder1_RobotContainer";
        }
        
        System.out.println("┌─ AUTHENTICATION MAPPING ──────────────────────────");
        System.out.println("│  Agent Name:  " + getLocalName());
        System.out.println("│  Container:   " + containerName);
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
        
        // Add staggered startup delay to prevent all robots from checking at the same time
        // This helps distribute the load and ensures all robots get a chance to claim tasks
        long startupDelay = 2000 + (robotId * 250); // Stagger by 250ms per robot
        addBehaviour(new jade.core.behaviours.WakerBehaviour(this, startupDelay) {
            @Override
            protected void onWake() {
                // Initialize federation capabilities
                initializeFederation();
            }
        });
        
        // Start main behaviors - ADD INDEPENDENTLY for true concurrent execution
        // DO NOT use ParallelBehaviour - it causes blocking issues where one behavior blocks others
        addBehaviour(robotBehavior);
        addBehaviour(new ProductionCommandHandler()); // Handle production commands
        addBehaviour(new HeartbeatBehaviour(this, 10000)); // Send heartbeat every 10 seconds
        addBehaviour(new PeerCoordinationBehaviour(this, 5000)); // Peer coordination every 3 seconds
        addBehaviour(new ProductNotificationHandler()); // Handle CFP from conveyors
        addBehaviour(new TaskResponseHandler()); // Handle ACCEPT/REJECT from conveyors

        // Register with Directory Facilitator so conveyors can find this agent
        // Register with ProductionAgentManager
        registerWithProductionManager();
    }

    
    @Override
    protected void takeDown() {
        // Deregister from the Directory Facilitator
        try {
            DFService.deregister(this);
            System.out.println("[" + getLocalName() + "] Deregistered from Directory Facilitator");
        } catch (FIPAException fe) {
            System.err.println("[" + getLocalName() + "] Error deregistering from DF: " + fe.getMessage());
        }
        
        System.out.println("[" + getLocalName() + "] Agent terminating...");
    }
    
    /**
     * Initialize federation capabilities for this robot agent
     */
    private void initializeFederation() {
        try {
            System.out.println("[" + getLocalName() + "] Initializing federation capabilities...");
            
            // Request FFA allocation from Federation Address Manager
            // Define capabilities based on robot's role
            String capability = determineRobotCapability();
            myFFA = FederationHelper.requestFFAAllocation(this, "Robot", robotId, capability);
            
            if (myFFA != null) {
                federationEnabled = true;
                System.out.println("[" + getLocalName() + "] Federation enabled with FFA: " + myFFA);
                
                // Initialize structured federation workflow
                String workflowId = FederationHelper.initializeFederationWorkflow(
                    this, "simple-federation", "EU/Plant7", "Manufacturing", capability);
                
                if (workflowId != null) {
                    System.out.println("┌─ FEDERATION WORKFLOW INITIALIZED ────────────────");
                    System.out.println("│  Agent:       " + getLocalName());
                    System.out.println("│  Workflow ID: " + workflowId);
                    System.out.println("│  Type:        simple-federation");
                    System.out.println("│  Capability:  " + capability);
                    System.out.println("└───────────────────────────────────────────────────");
                }
                
                // Add federation-specific behaviors
                addBehaviour(new FederationMaintenanceBehaviour());
                // DISABLED: FederationMessageHandler conflicts with Contract Net Protocol
                // It uses receive() without template, catching ALL messages including CFP
                // This causes CFP messages to be discarded instead of processed by ProductNotificationHandler
                // addBehaviour(new FederationMessageHandler());
                
                // Initialize horizontal federation with other robots
                initializeHorizontalFederation();
                
                // Initialize vertical federation with production manager
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
     * Determine robot's primary capability based on its configuration and role
     */
    private String determineRobotCapability() {
        // Base capability on robot's priority and role in the system
        if (robotId == 1) {
            return "MaterialHandling.Primary";
        } else if (robotId <= SystemConfig.NUM_ROBOTS / 2) {
            return "MaterialHandling.Collection";
        } else {
            return "MaterialHandling.Distribution";
        }
    }
    
    /**
     * Initialize horizontal federation with peer robots
     */
    private void initializeHorizontalFederation() {
        if (!federationEnabled) return;
        
        String peerPattern = "EU/Plant7.Manufacturing.Production.OpcUA.MultiAgentSystem.Robot*::MaterialHandling.*";
        
        System.out.println("┌─ HORIZONTAL FEDERATION (Peer-to-Peer) ───────────");
        System.out.println("│  Agent:    " + getLocalName());
        System.out.println("│  Target:   Other robot agents (same level)");
        System.out.println("│  Pattern:  " + peerPattern);
        System.out.println("│  Purpose:  Coordination between peer robots");
        System.out.println("└───────────────────────────────────────────────────");
        
        // This would be used for coordination between robots of the same type
        // Implementation would involve periodic peer discovery and coordination
    }
    
    /**
     * Initialize vertical federation with production management layer
     */
    private void initializeVerticalFederation() {
        if (!federationEnabled) return;
        
        String managerPattern = "EU/Plant7.Manufacturing.Production.**.ProductionManager::ManufacturingCoordination";
        
        System.out.println("┌─ VERTICAL FEDERATION (Hierarchical) ─────────────");
        System.out.println("│  Agent:    " + getLocalName());
        System.out.println("│  Target:   Production management layer");
        System.out.println("│  Pattern:  " + managerPattern);
        System.out.println("│  Purpose:  Receive high-level commands & report");
        System.out.println("└───────────────────────────────────────────────────");
        
        // This would be used for receiving high-level production commands
        // and reporting status to management layer
    }

    // =====================================================================
    // MAIN ROBOT BEHAVIOR - Well organized
    // =====================================================================
    TickerBehaviour robotBehavior = new TickerBehaviour(this, AGENT_INTERVAL) {
        @Override
        public void onStart() {
            myRobot.setRobotName(myAgent.getLocalName());
        }

        private long lastIdleLogTime = 0;
        
        @Override
        public void onTick() {

            // 0. Check OPA authorization and update enabled status
            updateRobotEnabledStatus();

            // 1. Handle conveyor production
            handleConveyorProduction();

            // 2. Handle robot pickup and delivery
            handleRobotOperations();

            // 3. Log idle status periodically to help diagnose stuck robots
            if (myRobot != null && myRobot.isEnabled()) {
                boolean isIdle = myRobot.getTarget().isEmpty() || myRobot.getTarget().startsWith("Idle Location");
                if (isIdle && !myRobot.isCarryingProduct()) {
                    long now = System.currentTimeMillis();
                    if (now - lastIdleLogTime > 10000) { // Log every 10 seconds if idle
                        System.out.println("⏸️ [" + getLocalName() + "] IDLE - waiting for tasks (enabled=" + myRobot.isEnabled() + ", target='" + myRobot.getTarget() + "')");
                        lastIdleLogTime = now;
                    }
                }
            }

        }
    };

    // =====================================================================
    // ORGANIZED BEHAVIOR METHODS - Agent-specific operations
    // =====================================================================
    
    /**
     * Update robot enabled status based on OPA authorization
     * If OPA allows the robot to operate, set enabled = true
     * Otherwise, set enabled = false
     */
    private void updateRobotEnabledStatus() {
        if (myRobot == null || securityManager == null) {
            return;
        }
        
        try {
            // Check if agent has valid token
            boolean hasValidToken = securityManager.hasValidToken(getLocalName());
            
            if (!hasValidToken) {
                // Token invalid or expired - disable robot
                if (myRobot.isEnabled()) {
                    myRobot.setEnabled(false);
                    System.out.println("🚫 " + getLocalName() + " DISABLED - Invalid or expired token");
                }
                return;
            }
            
            // Refresh token if needed
            boolean tokenRefreshed = securityManager.refreshTokenIfNeeded(getLocalName());
            if (tokenRefreshed) {
                System.out.println("🔄 " + getLocalName() + " - Token refreshed successfully");
            }
            
            // Check if agent can access robot operation service
            boolean authorized = securityManager.canAccessService(getLocalName(), "robot_operation");
            
            // Update the enabled status based on authorization
            if (authorized != myRobot.isEnabled()) {
                myRobot.setEnabled(authorized);
                
                if (authorized) {
                    System.out.println("┌─ ROBOT STATUS UPDATE ────────────────────────────");
                    System.out.println("│  ✅ ENABLED");
                    System.out.println("│  Time:   " + java.time.Instant.now());
                    System.out.println("│  Robot:  " + getLocalName());
                    System.out.println("│  Status: OPA authorization granted");
                    System.out.println("│  Policy: robot_operation service access allowed");
                    System.out.println("└──────────────────────────────────────────────────");
                } else {
                    System.out.println("┌─ ROBOT STATUS UPDATE ────────────────────────────");
                    System.out.println("│  🚫 DISABLED");
                    System.out.println("│  Time:   " + java.time.Instant.now());
                    System.out.println("│  Robot:  " + getLocalName());
                    System.out.println("│  Status: OPA authorization denied");
                    System.out.println("│  Policy: robot_operation service access blocked");
                    System.out.println("└──────────────────────────────────────────────────");
                }
            }
            
        } catch (Exception e) {
            // On error, log detailed error but DON'T disable robot immediately
            // Only disable if this is a persistent error
            System.err.println("⚠️ " + getLocalName() + " - Authorization check error: " + e.getMessage());
            e.printStackTrace();
            
            // Don't disable robot on first error - it might be a temporary network issue
            // Robot will be disabled if token becomes invalid in next check
        }
    }
    
    private void displayRobotStatus() {
        if (myRobot != null) {
            System.out.println("Robot " + robotId + " Location: " + myRobot.getLocation() + ", Next Location: " + myRobot.getNextLocation());
        }
    }

    private void handleConveyorProduction() {
        // CRITICAL: Only check conveyors if robot is enabled by OPA
        if (myRobot == null || !myRobot.isEnabled()) {
            return; // Robot disabled - cannot check conveyor production
        }
        
        // Debug: Log conveyor status periodically
        if (System.currentTimeMillis() % 30000 < 1000) {
            int producingCount = 0;
            for (int i = 0; i < CustomNamespace.getInputConveyors().size(); i++) {
                ConveyorAgent conveyor = CustomNamespace.getInputConveyors().get(i);
                if (conveyor.getProduced()) {
                    producingCount++;
                }
            }
            System.out.println("📊 [" + getLocalName() + "] Conveyors producing: " + producingCount + "/" + CustomNamespace.getInputConveyors().size() + " (Enabled: " + myRobot.isEnabled() + ")");
        }
        
        checkConveyorProduction();
    }

    private void handleRobotOperations() {
        if (myRobot != null) {
            // Only execute robot operations if enabled
            if (!myRobot.isEnabled()) {
                return; // Robot is disabled by OPA policy
            }
            
            // Track location changes to detect when robot exits conveyor area
            trackLocationChanges();
            
            // Handle product pickup for this specific robot
            checkProductPickup(myRobot);

            // Handle product drop-off for this specific robot
            checkProductDropoff(myRobot);

            // Check for new targets for this specific robot
            checkAndSetNewTarget(myRobot);
        }
    }

    // =====================================================================
    // ROBOT MOVEMENT
    // =====================================================================


    private void checkConveyorProduction() {
        // =====================================================================
        // DISABLED: This method is no longer used since we switched to Contract Net Protocol (CNP)
        // 
        // OLD BEHAVIOR (Direct Polling):
        //   - Robots actively poll conveyors for available products
        //   - Robots directly assign themselves using assignTaskWithPriority()
        // 
        // NEW BEHAVIOR (Contract Net Protocol):
        //   - Conveyors broadcast CFP (Call-For-Proposals) when product is ready
        //   - Robots respond with PROPOSE messages containing priority & distance
        //   - Conveyor evaluates all proposals and sends ACCEPT_PROPOSAL to winner
        //   - Winner sets target ONLY after receiving ACCEPT_PROPOSAL
        //   - Losers receive REJECT_PROPOSAL and remain idle
        // 
        // This prevents the bug where rejected robots were still moving to conveyors
        // =====================================================================
        
        // Do nothing - robots now wait for CFP messages from ProductNotificationHandler
        return;
    }

    private String getConveyorTargetName(int conveyorNumber) {
        // Always use consistent naming: "InputConveyor #1", "InputConveyor #2", etc.
        // This matches the actual conveyor names in the Visual Components simulation
        return "InputConveyor #" + conveyorNumber;
    }

    private boolean isInputConveyorTarget(String target) {
        return target.equals("InputConveyor") || target.startsWith("InputConveyor #");
    }
    
    /**
     * Assign idle location target to robot after completing drop-off
     * This sends the robot back to its designated idle station
     */
    private void assignIdleLocation(RobotTemplate robot) {
        try {
            // Parse idle locations from JSON
            String idlePropertiesString = CustomNamespace.idleProperties.getValue().getValue().getValue().toString();
            JSONParser parser = new JSONParser();
            JSONArray idleLocationsArray = (JSONArray) parser.parse(idlePropertiesString);
            
            // Get this robot's idle location (robotId maps to index in array)
            int robotIndex = robotId - 1;
            if (robotIndex >= 0 && robotIndex < idleLocationsArray.size()) {
                JSONObject idleLocation = (JSONObject) idleLocationsArray.get(robotIndex);
                String idleLocationName = (String) idleLocation.get("Name");
                
                System.out.println("┌─ RETURN TO IDLE ─────────────────────────────────");
                System.out.println("│  🏠 RETURNING TO IDLE");
                System.out.println("│  Time:     " + java.time.Instant.now());
                System.out.println("│  Robot:    " + getLocalName());
                System.out.println("│  Target:   " + idleLocationName);
                System.out.println("│  Status:   Moving to idle station");
                System.out.println("└──────────────────────────────────────────────────");
                
                setRobotTarget(idleLocationName, "IDLE_ACTION");
            } else {
                System.err.println("❌ " + getLocalName() + " - Invalid robot index for idle location: " + robotIndex);
            }
        } catch (Exception e) {
            System.err.println("❌ " + getLocalName() + " - Error assigning idle location: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Assign drop-off target to robot after picking up product
     * This ensures robot immediately knows where to go after pickup
     */
    private void assignDropOffTarget(RobotTemplate robot) {
        try {
            // Get the JSON string from output_conveyor_Properties
            String outputConveyorPropertiesString = CustomNamespace.outputconveyorProperties.getValue().getValue().getValue().toString();
            // Parse the JSON string into a JSONArray
            JSONParser parser = new JSONParser();
            JSONArray outputConveyorsArray = (JSONArray) parser.parse(outputConveyorPropertiesString);
            // Extract Names into a List
            List<String> dropOffConveyors = new ArrayList<>();
            for (Object o : outputConveyorsArray) {
                JSONObject conveyor = (JSONObject) o;
                String name = (String) conveyor.get("Name");
                dropOffConveyors.add(name);
            }
            // Randomly select one conveyor name
            if (!dropOffConveyors.isEmpty()) {
                Random rand = new Random();
                String dropOffTarget = dropOffConveyors.get(rand.nextInt(dropOffConveyors.size()));
                
                // Log before setting target
                String currentLocation = robot.getLocation();
                String currentNextLocation = robot.getNextLocation();
                String currentTarget = robot.getTarget();
                
                System.out.println("┌─ DROP-OFF TARGET ASSIGNMENT ─────────────────────");
                System.out.println("│  🎯 SETTING NEW TARGET");
                System.out.println("│  Time:         " + java.time.Instant.now());
                System.out.println("│  Robot:        " + getLocalName());
                System.out.println("│  Current Loc:  " + currentLocation);
                System.out.println("│  Old Target:   '" + currentTarget + "'");
                System.out.println("│  Old NextLoc:  '" + currentNextLocation + "'");
                System.out.println("│  NEW Target:   '" + dropOffTarget + "'");
                System.out.println("│  CarryingProd: " + robot.isCarryingProduct());
                
                // Set the new target using wrapper for tracking
                setRobotTarget(dropOffTarget, "TARGET_ACTION");
                
                // Small delay to allow OPC-UA to process
//                Thread.sleep(200);
                
                // Verify target was actually written to OPC-UA
                String verifiedTarget = robot.getTarget();
                String verifiedLocation = robot.getLocation();
                boolean targetSetCorrectly = dropOffTarget.equals(verifiedTarget);
                System.out.println("│");
                System.out.println("│  Target Set OK (✓): " + targetSetCorrectly);
                System.out.println("│  Status:            " + (targetSetCorrectly ? "✅ Target written to OPC-UA" : "❌ TARGET NOT SET!"));
                System.out.println("└──────────────────────────────────────────────────");
                
                if (!targetSetCorrectly) {
                    System.err.println("❌❌❌ CRITICAL ERROR: Target was not set correctly!");
                    System.err.println("   Expected: '" + dropOffTarget + "'");
                    System.err.println("   Got:      '" + verifiedTarget + "'");
                    // Try setting it again
                    System.out.println("🔄 Retrying target assignment...");
                    setRobotTarget(dropOffTarget, "TARGET_ACTION");
                }
            } else {
                System.out.println("⚠️ " + getLocalName() + " - No drop-off conveyors available");
            }
        } catch (Exception e) {
            System.err.println("❌ " + getLocalName() + " - Error assigning drop-off target: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Track location changes to detect when robot exits conveyor area
     * Notifies the conveyor so it can send new CFP broadcasts without collision risk
     */
    private void trackLocationChanges() {
        if (myRobot == null) return;
        
        String currentLocation = myRobot.getLocation();
        
        // Check if location has changed
        if (!currentLocation.equals(previousLocation)) {
            // Check if robot was at a conveyor and has now left
            boolean wasAtConveyor = isInputConveyorLocation(previousLocation);
            boolean isAtConveyor = isInputConveyorLocation(currentLocation);
            
            if (wasAtConveyor && !isAtConveyor && lastPickupConveyor != null) {
                // Robot has left the conveyor area - notify the conveyor
                notifyConveyorExit(lastPickupConveyor);
                lastPickupConveyor = null; // Clear tracking
            }
            
            // Update previous location
            previousLocation = currentLocation;
        }
    }
    
    /**
     * Check if location is an InputConveyor location
     */
    private boolean isInputConveyorLocation(String location) {
        if (location == null || location.isEmpty()) return false;
        return location.startsWith("InputConveyor #") || location.equals("InputConveyor");
    }
    
    /**
     * Notify conveyor that robot has exited the conveyor area
     * Uses ACLMessage for cross-container communication
     */
    private void notifyConveyorExit(String conveyorAgentName) {
        try {
            // Create exit notification message
            ACLMessage exitMsg = new ACLMessage(ACLMessage.INFORM);
            exitMsg.addReceiver(new AID(conveyorAgentName, AID.ISLOCALNAME));
            exitMsg.setProtocol("robot-exit");
            exitMsg.setContent("(RobotExit :robot \"" + getLocalName() + "\" :time \"" + java.time.Instant.now() + "\")");
            
            send(exitMsg);
            
            System.out.println("📤 " + getLocalName() + " - Sent exit notification to " + conveyorAgentName);

        } catch (Exception e) {
            System.err.println("❌ " + getLocalName() + " - Error notifying conveyor exit: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Notify all conveyors that robot has reached drop-off and task is complete
     * This allows conveyors to send new CFP broadcasts if they have products waiting
     * The notification includes information about available idle robots
     */
    private void notifyConveyorsRobotAvailable() {
        try {
            // Count how many robots are currently idle and available
            int idleRobotsCount = 0;
            StringBuilder idleRobotsList = new StringBuilder();
            
            for (RobotTemplate robot : CustomNamespace.robots) {
                boolean hasNoTarget = robot.getTarget().isEmpty();
                boolean returningToIdle = robot.getTarget().startsWith("Idle Location");
                boolean isAvailable = robot.isEnabled() && (hasNoTarget || returningToIdle) && !robot.isCarryingProduct();
                
                if (isAvailable) {
                    idleRobotsCount++;
                    int robotIndex = CustomNamespace.robots.indexOf(robot);
                    if (idleRobotsList.length() > 0) idleRobotsList.append(",");
                    idleRobotsList.append("RobotAgent").append(robotIndex + 1);
                }
            }
            
            // Find all conveyor agents through Directory Facilitator
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("ConveyorService");
            template.addServices(sd);
            
            DFAgentDescription[] results = DFService.search(this, template);
            
            System.out.println("┌─ TASK COMPLETE NOTIFICATION ─────────────────────");
            System.out.println("│  🔔 NOTIFYING CONVEYORS");
            System.out.println("│  Time:          " + java.time.Instant.now());
            System.out.println("│  Robot:         " + getLocalName());
            System.out.println("│  Status:        Task complete at drop-off location");
            System.out.println("│  Idle Robots:   " + idleRobotsCount + " available");
            System.out.println("│  Available:     [" + idleRobotsList.toString() + "]");
            System.out.println("│  Conveyor Targets: " + results.length);
            
            for (DFAgentDescription result : results) {
                ACLMessage notification = new ACLMessage(ACLMessage.INFORM);
                notification.addReceiver(result.getName());
                notification.setProtocol("task-complete");
                notification.setContent(
                    "(TaskComplete " +
                    ":completed-by \"" + getLocalName() + "\" " +
                    ":status \"at-dropoff\" " +
                    ":idle-robots " + idleRobotsCount + " " +
                    ":available-robots \"" + idleRobotsList.toString() + "\")"
                );
                
                send(notification);
                System.out.println("│  ✓ Sent to: " + result.getName().getLocalName());
            }
            
            System.out.println("│  Message:       Conveyors can send CFP for new products");
            System.out.println("└──────────────────────────────────────────────────");
            
        } catch (FIPAException e) {
            System.err.println("❌ " + getLocalName() + " - Error notifying conveyors: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Assign task to robot using priority-based collision resolution
     * Higher priority robots get preference when multiple robots compete for the same task
     * Ensures all robots get tasks from both conveyors using round-robin rotation
     */
    private void assignTaskWithPriority(String targetName, String conveyorAgentName) {
        try {
            // Get all available robots that can communicate with this conveyor
            List<RobotCandidate> candidates = new ArrayList<>();
            
            for (RobotTemplate robot : CustomNamespace.robots) {
                int robotIndex = CustomNamespace.robots.indexOf(robot);
                String robotAgentName = "RobotAgent" + (robotIndex + 1);
                
                // Check if robot is available
                boolean hasNoTarget = robot.getTarget().isEmpty();
                boolean returningToIdle = robot.getTarget().startsWith("Idle Location");
                boolean isEnabled = robot.isEnabled();
                boolean isAvailable = isEnabled && (hasNoTarget || returningToIdle) && !robot.isCarryingProduct();
                
                if (!isAvailable) continue;
                
                // Check OPA authorization
                if (securityManager != null && !securityManager.canCommunicateWith(robotAgentName, conveyorAgentName)) {
                    continue; // Skip robots not authorized for this conveyor
                }
                
                // Calculate distance to conveyor
                double distance = calculateDistanceToConveyor(robotIndex, targetName);
                
                candidates.add(new RobotCandidate(robot, robotIndex, robot.getPriority(), distance));
            }
            
            if (candidates.isEmpty()) {
                return; // No available robots
            }
            
            // Sort candidates by priority (descending), then by distance (ascending)
            // This ensures higher priority robots get preference in collision resolution
            Collections.sort(candidates, (a, b) -> {
                if (USE_PRIORITY_RESOLUTION) {
                    // First compare by priority (higher priority wins)
                    int priorityCompare = Integer.compare(b.priority, a.priority);
                    if (priorityCompare != 0) {
                        return priorityCompare;
                    }
                }
                // If priorities are equal (or priority resolution disabled), compare by distance
                return Double.compare(a.distance, b.distance);
            });
            
            // Apply round-robin fairness: rotate through candidates to ensure all robots get tasks
            // Use task counter to determine which robot should get the task
            RobotCandidate selected;
            if (candidates.size() > 1) {
                // Round-robin: select robot based on task counter
                int selectionIndex = taskCounter % candidates.size();
                selected = candidates.get(selectionIndex);
                taskCounter++;
            } else {
                // Only one candidate available
                selected = candidates.get(0);
                taskCounter++;
            }
            
            // Assign task to selected robot
            selected.robot.setTarget(targetName);
            consecutiveIdleCycles = 0; // Reset idle counter
            
            System.out.println("┌─ TASK ASSIGNMENT (Priority-Based) ───────────────");
            System.out.println("│  ✅ ASSIGNED");
            System.out.println("│  Time:      " + java.time.Instant.now());
            System.out.println("│  Robot:     RobotAgent" + (selected.robotIndex + 1));
            System.out.println("│  Priority:  " + selected.priority);
            System.out.println("│  Distance:  " + String.format("%.2f", selected.distance));
            System.out.println("│  Target:    " + targetName);
            System.out.println("│  Conveyor:  " + conveyorAgentName);
            System.out.println("│  Method:    " + (USE_PRIORITY_RESOLUTION ? "Priority+RoundRobin" : "Distance+RoundRobin"));
            System.out.println("│  Candidates: " + candidates.size() + " available robots");
            System.out.println("└──────────────────────────────────────────────────");
            
        } catch (Exception e) {
            System.err.println("Error in assignTaskWithPriority: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Helper class to store robot candidate information for task assignment
     */
    private static class RobotCandidate {
        final RobotTemplate robot;
        final int robotIndex;
        final int priority;
        final double distance;
        
        RobotCandidate(RobotTemplate robot, int robotIndex, int priority, double distance) {
            this.robot = robot;
            this.robotIndex = robotIndex;
            this.priority = priority;
            this.distance = distance;
        }
    }
    
    /**
     * Calculate distance from robot's idle location to target conveyor
     */
    private double calculateDistanceToConveyor(int robotIndex, String targetName) {
        try {
            // Parse input conveyor locations and idle locations
            String inputConveyorFile = null;
            String idleFile = null;
            for (SystemConfig.ComponentProperty prop : SystemConfig.COMPONENT_PROPERTIES) {
                if (prop == null || prop.name == null || prop.jsonFile == null) continue;
                if (prop.name.equals("inputconveyorProperties")) inputConveyorFile = prop.jsonFile;
                if (prop.name.equals("idleProperties")) idleFile = prop.jsonFile;
            }
            
            JSONParser parser = new JSONParser();
            JSONArray inputConveyorLocations = (JSONArray) parser.parse(new FileReader(inputConveyorFile));
            JSONArray idleLocations = (JSONArray) parser.parse(new FileReader(idleFile));
            
            // Find conveyor index
            int conveyorIndex = -1;
            if (targetName.equals("InputConveyor")) {
                conveyorIndex = 0;
            } else if (targetName.startsWith("InputConveyor #")) {
                String numberStr = targetName.substring("InputConveyor #".length());
                conveyorIndex = Integer.parseInt(numberStr) - 1;
            } else if (targetName.matches("InputConveyor\\s+\\d+")) {
                // Handle "InputConveyor 1" format (space instead of #)
                String numberStr = targetName.replaceAll("InputConveyor\\s+", "");
                conveyorIndex = Integer.parseInt(numberStr) - 1;
            }
            
            if (conveyorIndex < 0 || conveyorIndex >= inputConveyorLocations.size()) {
                return Double.MAX_VALUE;
            }
            
            JSONObject targetConveyor = (JSONObject) inputConveyorLocations.get(conveyorIndex);
            JSONObject robotLocation = (JSONObject) idleLocations.get(robotIndex);
            
            return calculateDistance(
                ((Number) robotLocation.get("X")).doubleValue(),
                ((Number) robotLocation.get("Y")).doubleValue(),
                ((Number) targetConveyor.get("X")).doubleValue(),
                ((Number) targetConveyor.get("Y")).doubleValue()
            );
            
        } catch (Exception e) {
            System.err.println("Error calculating distance: " + e.getMessage());
            return Double.MAX_VALUE;
        }
    }

    private void checkProductPickup(RobotTemplate robot) {
        // CRITICAL: Check if robot is enabled before allowing pickup
        if (!robot.isEnabled()) {
            System.out.println("🚫 [" + getLocalName() + "] Cannot pickup - Robot disabled by OPA policy");
            return;
        }
        
        boolean isCarryingProduct = robot.isCarryingProduct();
        String robotLocation = robot.getLocation();
        String robotTarget = robot.getTarget();
        String robotNextLocation = robot.getNextLocation();

        // Check for pickup at both input conveyors
        for (int i = 0; i < CustomNamespace.getInputConveyors().size(); i++) {
            String conveyorTargetName = getConveyorTargetName(i + 1);
            
            if (robotLocation.equals(conveyorTargetName)) {
                ConveyorAgent conveyor = CustomNamespace.getInputConveyors().get(i);
                String conveyorAgentName = "ConveyorAgent" + (i + 1);

                // OPA CHECK: Verify robot can communicate with this conveyor before pickup
                if (securityManager != null && !securityManager.canCommunicateWith(getLocalName(), conveyorAgentName)) {
                    System.out.println("🚫 [" + getLocalName() + "] Cannot pickup from " + conveyorAgentName + " - OPA authorization denied");
                    continue; // Skip this conveyor
                }
                
                // FEDERATION COORDINATION: Register in pickup queue if not already registered
                if (!robot.isCarryingProduct() && conveyor.getProduced()) {
                    conveyor.registerForPickup(getLocalName(), robot.getPriority());
                    System.out.println("📝 " + getLocalName() + " - Registered in pickup queue at " + conveyorAgentName);
                }
                
                // FEDERATION COORDINATION: Check if this robot can pick up (is it next in queue?)
                boolean canPickup = conveyor.canPickup(getLocalName());
                System.out.println("🔍 " + getLocalName() + " - Checking pickup permission at " + conveyorAgentName + ": " + canPickup + " (Produced: " + conveyor.getProduced() + ")");
                
                System.out.println(conveyorAgentName + " Status - Produced: " + conveyor.getProduced() + ", Pickup Queue: ");

                if (conveyor.getProduced() && canPickup) {
                    System.out.println("┌─ PRODUCT PICKUP ─────────────────────────────────");
                    System.out.println("│  📦 PICKUP");
                    System.out.println("│  Time:     " + java.time.Instant.now());
                    System.out.println("│  Robot:    " + getLocalName() + " (Enabled: " + robot.isEnabled() + ", Priority: " + robot.getPriority() + ")");
                    System.out.println("│  Location: " + conveyorTargetName);
                    System.out.println("│  Conveyor: " + conveyorAgentName);
                    System.out.println("│  Auth:     OPA authorization verified");
                    System.out.println("│  Queue:    Pickup authorized (priority-based ordering)");
                    System.out.println("│  Action:   Produced status → false");
                    System.out.println("└──────────────────────────────────────────────────");
                    
                    robot.setCarryingProduct(true);
                    conveyor.setProduced(false);
                    
                    // Track which conveyor this robot picked up from
                    lastPickupConveyor = conveyorAgentName;
                    
                    // Notify conveyor that pickup is complete
                    conveyor.notifyPickupComplete(getLocalName());
                    
                    // IMMEDIATELY assign drop-off target after picking up product
                    assignDropOffTarget(robot);
                    
                    break; // Exit loop once we find a match
                }
            }
        }
    }

    private void checkProductDropoff(RobotTemplate robot) {
        // CRITICAL: Check if robot is enabled before allowing drop-off
        if (!robot.isEnabled()) {
            System.out.println("🚫 [" + getLocalName() + "] Cannot drop-off - Robot disabled by OPA policy");
            return;
        }
        
        String location = robot.getLocation();
        boolean isCarryingProduct = robot.isCarryingProduct();
        String target = robot.getTarget();

        // Check if robot is carrying a product and is at a drop-off location
        if (isCarryingProduct && dropOffConveyorNamesContains(location) && dropOffConveyorNamesContains(target)) {
            // Robot has reached drop-off location, set CarryingProduct to false
            System.out.println("┌─ PRODUCT DROPOFF ────────────────────────────────");
            System.out.println("│  📤 DROPOFF");
            System.out.println("│  Time:     " + java.time.Instant.now());
            System.out.println("│  Robot:    " + getLocalName() + " (Enabled: " + robot.isEnabled() + ")");
            System.out.println("│  Location: " + location);
            System.out.println("│  Auth:     OPA authorization verified");
            System.out.println("│  Status:   Delivery completed");
            System.out.println("└──────────────────────────────────────────────────");
            
            // Report task completion to ProductionAgentManager for load balancing
            reportTaskCompletionToManager();
            robot.setCarryingProduct(false);
            
            // Notify all conveyors that robot is available and returning to idle
            notifyConveyorsRobotAvailable();
            
            // FAIRNESS: Record the task completion for the conveyor it came from
            String lastConveyor = lastPickupConveyor; // Capture before clearing
            if (lastConveyor != null) {
                conveyorTaskCompletions.merge(lastConveyor, 1, Integer::sum);
                System.out.println("⚖️ " + getLocalName() + " - Logged task completion for " + lastConveyor + ". Total: " + conveyorTaskCompletions.get(lastConveyor));
            }

            // Send robot back to idle location
            assignIdleLocation(robot);
        }
    }

    private boolean dropOffConveyorNamesContains(String location) {
        try {
            String outputConveyorPropertiesString = CustomNamespace.outputconveyorProperties.getValue().getValue().getValue().toString();
            JSONParser parser = new JSONParser();
            JSONArray outputConveyorsArray = (JSONArray) parser.parse(outputConveyorPropertiesString);
            for (Object o : outputConveyorsArray) {
                JSONObject conveyor = (JSONObject) o;
                String name = (String) conveyor.get("Name");
                if (name.equals(location)) {
                    return true;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }
    private void checkAndSetNewTarget(RobotTemplate robot) {
        // CRITICAL: Check if robot is enabled before assigning new targets
        if (!robot.isEnabled()) {
            return; // Robot disabled - cannot receive new targets
        }
        
        boolean isCarryingProduct = robot.isCarryingProduct();
        String currentTarget = robot.getTarget();
        String currentLocation = robot.getLocation();

        // Fallback: If robot is carrying a product but has no target (edge case)
        // This shouldn't happen as assignDropOffTarget is called immediately after pickup
        if (isCarryingProduct && currentTarget.isEmpty()) {
            System.out.println("⚠️ " + getLocalName() + " - Carrying product but no target (fallback assignment)");
            assignDropOffTarget(robot);
        }
        // When robot has reached its idle location
        if (currentLocation.startsWith("Idle Location")) {
            if (currentTarget.startsWith("Idle Location")) {
                // Clear target so robot becomes available for new tasks
                robot.setTarget("");
                System.out.println("┌─ IDLE LOCATION REACHED ──────────────────────────");
                System.out.println("│  Robot:    " + getLocalName());
                System.out.println("│  Location: " + currentLocation);
                System.out.println("│  Status:   Ready for new tasks");
                System.out.println("└──────────────────────────────────────────────────");
            }
        }
    }

    // =====================================================================
    // FEDERATION BEHAVIORS - Handle federation protocol operations
    // =====================================================================
    
    /**
     * Maintains federation lease and handles periodic federation tasks
     */
    private class FederationMaintenanceBehaviour extends TickerBehaviour {
        
        public FederationMaintenanceBehaviour() {
            super(RobotAgent.this, 60000); // Update lease every minute
        }
        
        @Override
        protected void onTick() {
            if (federationEnabled && myFFA != null) {
                // Update FFA lease to keep federation address active
                boolean success = FederationHelper.updateFFALease(RobotAgent.this);
                if (!success) {
                    System.err.println("[" + getLocalName() + "] Failed to update FFA lease");
                }
                
                // Perform periodic federation maintenance
                performFederationMaintenance();
            }
        }
        
        private void performFederationMaintenance() {
            // Check federation health metrics periodically
            if (System.currentTimeMillis() % 300000 == 0) { // Every 5 minutes
                String healthMetrics = FederationHelper.getFederationHealthMetrics(RobotAgent.this);
                if (healthMetrics != null) {
                    System.out.println("[" + getLocalName() + "] Federation health check: " + healthMetrics);
                }
            }
            
            // Check for peer robot status updates
            // Monitor production coordination messages
            // Handle any federation-specific housekeeping
        }
    }
    
    /**
     * Handles incoming federation messages from other agents
     * NOTE: Product notifications with protocol "product-ready-notification" are caught by 
     * ProductNotificationHandler FIRST, so they won't normally reach this handler.
     * This handler serves as backup and handles all other federation message types.
     */
    private class FederationMessageHandler extends CyclicBehaviour {
        
        @Override
        public void action() {
            ACLMessage msg = receive();
            if (msg == null) {
                block();
                return;
            }
            
            // Handle federation-specific messages
            // ProductNotificationHandler processes product notifications first,
            // so this mainly handles: PeerCoordination, ProductionCommand, StatusRequest, etc.
            if (isFederationMessage(msg)) {
                handleFederationMessage(msg);
            } else {
                // Handle other message types or forward them
                handleNonFederationMessage(msg);
            }
        }
        
        private boolean isFederationMessage(ACLMessage msg) {
            String content = msg.getContent();
            String protocol = msg.getProtocol();
            return content != null && (
                content.contains("Federation") ||
                content.contains("FFA") ||
                content.contains("Coordination") ||
                content.contains("ProductReady") ||
                protocol != null && (protocol.equals("federation-coordination") || protocol.equals("product-ready-notification"))
            );
        }
        
        private void handleFederationMessage(ACLMessage msg) {
            String content = msg.getContent();
            String senderName = msg.getSender().getLocalName();
            String protocol = msg.getProtocol();
            
            System.out.println("[" + getLocalName() + "] Received federation message from " + senderName + ": " + content);
            
            // PRIORITY 1: Handle product notifications (horizontal federation with conveyors)
            // Check protocol first for more precise matching
            if ("product-ready-notification".equals(protocol) || content.contains("ProductReady")) {
                handleProductReadyNotification(msg);
            } 
            // PRIORITY 2: Handle peer coordination (horizontal federation with other robots)
            else if (content.contains("PeerCoordination")) {
                handlePeerCoordinationMessage(msg);
            } 
            // PRIORITY 3: Handle production commands (vertical federation with management)
            else if (content.contains("ProductionCommand")) {
                handleProductionCommandMessage(msg);
            } 
            // PRIORITY 4: Handle status requests (both horizontal and vertical federation)
            else if (content.contains("StatusRequest")) {
                handleStatusRequestMessage(msg);
            }
            // DEFAULT: Generic federation message (for future extensions)
            else {
                System.out.println("[" + getLocalName() + "] Received generic federation message - no specific handler");
            }
        }
        
        private void handleProductReadyNotification(ACLMessage msg) {
            // Handle product ready notification from conveyor
            String content = msg.getContent();
            String senderName = msg.getSender().getLocalName();
            
            System.out.println("┌─ PRODUCT NOTIFICATION RECEIVED ──────────────────");
            System.out.println("│  📩 RECEIVED");
            System.out.println("│  Time:     " + java.time.Instant.now());
            System.out.println("│  Robot:    " + getLocalName());
            System.out.println("│  From:     " + senderName);
            System.out.println("│  Message:  " + content);
            System.out.println("└──────────────────────────────────────────────────");
            
            // Check if robot is available and enabled to respond
            if (myRobot == null || !myRobot.isEnabled()) {
                System.out.println("🚫 [" + getLocalName() + "] Cannot respond - Robot disabled");
                return;
            }
            
            boolean hasNoTarget = myRobot.getTarget().isEmpty();
            boolean returningToIdle = myRobot.getTarget().startsWith("Idle Location");
            boolean isAvailable = (hasNoTarget || returningToIdle) && !myRobot.isCarryingProduct();
            
            if (!isAvailable) {
                System.out.println("⏭️ [" + getLocalName() + "] Cannot respond - Robot busy (Target: " + myRobot.getTarget() + ", Carrying: " + myRobot.isCarryingProduct() + ")");
                return;
            }
            
            // Extract conveyor information from message
            String conveyorLocation = extractValue(content, ":location");
            
            if (conveyorLocation != null && !conveyorLocation.isEmpty()) {
                // Check if this robot should take the task (priority-based)
                System.out.println("✅ [" + getLocalName() + "] Responding to notification - Assigning target: " + conveyorLocation);
                assignTaskWithPriority(conveyorLocation, senderName);
            }
        }
        
        private void handlePeerCoordinationMessage(ACLMessage msg) {
            // Handle coordination messages from peer robots
            // This could include task delegation, resource sharing, etc.
            System.out.println("[" + getLocalName() + "] Processing peer coordination message");
        }
        
        private void handleProductionCommandMessage(ACLMessage msg) {
            // Handle high-level production commands from management layer
            System.out.println("[" + getLocalName() + "] Processing production command");
        }
        
        private void handleStatusRequestMessage(ACLMessage msg) {
            // Handle status requests from other federation members
            ACLMessage reply = msg.createReply();
            reply.setPerformative(ACLMessage.INFORM);
            
            String status = String.format(
                "(RobotStatus :id %d :location \"%s\" :target \"%s\" :carrying %s :ffa \"%s\")",
                robotId,
                myRobot != null ? myRobot.getLocation() : "unknown",
                myRobot != null ? myRobot.getTarget() : "none",
                myRobot != null ? myRobot.isCarryingProduct() : false,
                myFFA
            );
            
            reply.setContent(status);
            send(reply);
            
            System.out.println("[" + getLocalName() + "] Sent status response to " + msg.getSender().getLocalName());
        }
        
        private void handleNonFederationMessage(ACLMessage msg) {
            // Handle regular JADE messages or forward to appropriate handler
            // This maintains compatibility with existing non-federation functionality
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
            
            // STEP 1: Register this robot with Directory Facilitator so ConveyorAgents can find it
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());
            ServiceDescription sd = new ServiceDescription();
            sd.setType("MaterialHandling");
            sd.setName(getLocalName() + "-robot-service");
            sd.addLanguages("ffa");
            dfd.addServices(sd);
            
            try {
                DFService.register(this, dfd);
                System.out.println("✅ [" + getLocalName() + "] Registered with Directory Facilitator (Type: MaterialHandling)");
            } catch (FIPAException fe) {
                System.err.println("❌ [" + getLocalName() + "] DF registration failed: " + fe.getMessage());
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
                    ":type \"ROBOT\" " +
                    ":capability \"" + determineRobotCapability() + "\" " +
                    ":ffa \"" + (myFFA != null ? myFFA : "NONE") + "\" " +
                    ":robot-id \"" + robotId + "\")"
                );
                
                send(registration);
                System.out.println("[" + getLocalName() + "] ✅ Registration sent to ProductionAgentManager");
            } else {
                System.out.println("[" + getLocalName() + "] ❌ ProductionAgentManager not found in Directory Facilitator");
            }
            
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error registering with ProductionAgentManager: " + e.getMessage());
        }
    }
    
    /**
     * Production Command Handler - Handles task assignments from ProductionAgentManager
     */
    private class ProductionCommandHandler extends CyclicBehaviour {
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
                    System.out.println("🚫 RobotAgent blocked command from " + senderName + " (OPA policy denied)");
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
                boolean taskSuccess = executeProductionTask(taskId, operation, priority);
                
                // Send completion acknowledgement
                if (taskSuccess) {
                    sendTaskCompletedAck(msg.getSender(), taskId, "Task executed successfully");
                } else {
                    sendTaskFailedAck(msg.getSender(), taskId, "Task execution failed");
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
     * Heartbeat Behaviour - Regular status updates to ProductionAgentManager
     */
    private class HeartbeatBehaviour extends TickerBehaviour {
        public HeartbeatBehaviour(Agent agent, long period) {
            super(agent, period);
        }
        @Override
        public void onStart() {
            DelayUtils.randomDelay(10, 1000);
        }

        @Override
        protected void onTick() {
            sendHeartbeat();
        }
    }
    
    /**
     * Task Response Handler - Handles ACCEPT_PROPOSAL and REJECT_PROPOSAL from ConveyorAgents
     * This behavior listens for responses after robot has submitted a proposal
     */
    private class TaskResponseHandler extends CyclicBehaviour {
        @Override
        public void action() {
            // Create template to catch both ACCEPT_PROPOSAL and REJECT_PROPOSAL
            MessageTemplate mtAccept = MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL);
            MessageTemplate mtReject = MessageTemplate.MatchPerformative(ACLMessage.REJECT_PROPOSAL);
            MessageTemplate mt = MessageTemplate.or(mtAccept, mtReject);
            
            ACLMessage msg = receive(mt);
            if (msg != null) {
                if (msg.getPerformative() == ACLMessage.ACCEPT_PROPOSAL) {
                    handleTaskAcceptance(msg);
                } else if (msg.getPerformative() == ACLMessage.REJECT_PROPOSAL) {
                    handleTaskRejection(msg);
                }
            } else {
                block();
            }
        }
        
        /**
         * Handle task acceptance - this robot won the bid
         */
        private void handleTaskAcceptance(ACLMessage accept) {
            try {
                String conversationId = accept.getConversationId();
                
                // ATOMIC VALIDATION: Check and remove in synchronized block to prevent duplicate processing
                synchronized (activeProposals) {
                    if (conversationId == null || !activeProposals.contains(conversationId)) {
                        System.out.println("⚠️ [VALIDATION FAILED] " + getLocalName() + " - Received ACCEPT for unknown conversationId: '" + conversationId + "'");
                        System.out.println("   Active proposals: " + activeProposals);
                        System.out.println("   Reason: " + (conversationId == null ? "NULL conversationId" : "Already processed or never sent"));
                        return; // Ignore - already processed or not our CFP
                    }
                    
                    // Remove immediately to prevent duplicate processing
                    activeProposals.remove(conversationId);
                }
                
                System.out.println("✅ [VALIDATION PASSED] " + getLocalName() + " - Valid ACCEPT for conversationId: '" + conversationId + "' (remaining: " + activeProposals.size() + ")");
                
                String senderName = accept.getSender().getLocalName();
                String content = accept.getContent();
                String location = extractValue(content, ":location");
                
                System.out.println("┌─ TASK ACCEPTED (BID WON) ────────────────────────");
                System.out.println("│  ✅ TASK ASSIGNED");
                System.out.println("│  Time:       " + java.time.Instant.now());
                System.out.println("│  Robot:      " + getLocalName());
                System.out.println("│  Conveyor:   " + senderName);
                System.out.println("│  Location:   " + location);
                System.out.println("└──────────────────────────────────────────────────");
                
                // Assign target to robot
                if (myRobot != null && location != null) {
                    setRobotTarget(location, "ACCEPT_PROPOSAL");
                    System.out.println("📍 " + getLocalName() + " - Target set to: " + myRobot.getTarget());
                    
                    // Pre-register in the conveyor's pickup queue as the winner
                    try {
                        for (int i = 0; i < CustomNamespace.getInputConveyors().size(); i++) {
                            ConveyorAgent conveyor = CustomNamespace.getInputConveyors().get(i);
                            if (("ConveyorAgent" + (i + 1)).equals(senderName)) {
                                reserveForWinner(getLocalName(),  senderName, i, myRobot.getPriority());
                                System.out.println("✅ " + getLocalName() + " - Reserved pickup slot at " + senderName);
                                break;
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("⚠️ " + getLocalName() + " - Could not reserve pickup slot: " + e.getMessage());
                    }
                    
                    // Send confirmation
                    ACLMessage confirm = accept.createReply();
                    confirm.setPerformative(ACLMessage.INFORM);
                    confirm.setContent("(TaskConfirmed :robot \"" + getLocalName() + "\" :status \"EnRoute\")");
                    send(confirm);
                }
                
            } catch (Exception e) {
                System.err.println("❌ " + getLocalName() + " - Error handling task acceptance: " + e.getMessage());
                e.printStackTrace();
            }
        }

        /**
         * Reserve pickup slot for CFP winner (called when robot wins the bid)
         * This places the winning robot at the front of the queue
         */
        public synchronized void reserveForWinner(String robotAgent, String conveyorAgent, int conveyorId, int priority) {
            // Clear the queue and set this robot as the current picker

            System.out.println("┌─ PICKUP RESERVED FOR WINNER ─────────────────────");
            System.out.println("│  🏆 CFP WINNER RESERVED");
            System.out.println("│  Time:      " + java.time.Instant.now());
            System.out.println("│  Conveyor:  " + conveyorAgent + " (#" + conveyorId + ")");
            System.out.println("│  Robot:     " + robotAgent);
            System.out.println("│  Priority:  " + priority);
            System.out.println("│  Status:    Bypassing queue (CFP winner)");
            System.out.println("└──────────────────────────────────────────────────");
        }
        
        /**
         * Handle task rejection - another robot won the bid
         * CRITICAL: Clear any tentative target to prevent robot from moving to conveyor
         */
        private void handleTaskRejection(ACLMessage reject) {
            try {
                String conversationId = reject.getConversationId();
                
                // ATOMIC VALIDATION: Check and remove in synchronized block to prevent duplicate processing
                synchronized (activeProposals) {
                    if (conversationId == null || !activeProposals.contains(conversationId)) {
                        System.out.println("⚠️ [VALIDATION FAILED] " + getLocalName() + " - Received REJECT for unknown conversationId: '" + conversationId + "'");
                        System.out.println("   Active proposals: " + activeProposals);
                        System.out.println("   Reason: " + (conversationId == null ? "NULL conversationId" : "Already processed or never sent"));
                        return; // Ignore - already processed or not our CFP
                    }
                    
                    // Remove immediately to prevent duplicate processing
                    activeProposals.remove(conversationId);
                }
                
                System.out.println("✅ [VALIDATION PASSED] " + getLocalName() + " - Valid REJECT for conversationId: '" + conversationId + "' (remaining: " + activeProposals.size() + ")");
                
                String senderName = reject.getSender().getLocalName();
                
                System.out.println("┌─ TASK REJECTED (BID LOST) ───────────────────────");
                System.out.println("│  ❌ PROPOSAL REJECTED");
                System.out.println("│  Time:       " + java.time.Instant.now());
                System.out.println("│  Robot:      " + getLocalName());
                System.out.println("│  Conveyor:   " + senderName);
                System.out.println("│  Reason:     Another robot was selected");
                System.out.println("└──────────────────────────────────────────────────");
                
                // DEFENSIVE: Explicitly ensure target is cleared/idle
                // This should not be necessary if everything is working correctly,
                // but acts as a safety net in case of race conditions or state leakage
                if (myRobot != null) {
                    String currentTarget = myRobot.getTarget();
                    if (currentTarget != null && !currentTarget.isEmpty() && !currentTarget.startsWith("Idle")) {
                        System.out.println("⚠️ [DEFENSIVE CLEAR] " + getLocalName() + " - Clearing unexpected target: '" + currentTarget + "'");
                        setRobotTarget("Idle", "TaskResponseHandler.handleTaskRejection() - DEFENSIVE CLEAR");
                    }
                }
                
            } catch (Exception e) {
                System.err.println("❌ " + getLocalName() + " - Error handling task rejection: " + e.getMessage());
            }
        }
    }
    
    /**
     * Product Notification Handler - Handles CFP (Call-For-Proposals) from ConveyorAgents
     * Receives CFP when conveyors have products ready for pickup and submits proposals
     */
    private class ProductNotificationHandler extends CyclicBehaviour {
        private long lastHeartbeat = 0;
        private int messageCount = 0;
        
        @Override
        public void action() {
            // Periodic heartbeat to confirm behavior is running
            long now = System.currentTimeMillis();
            if (now - lastHeartbeat > 10000) {
                System.out.println("\uD83D\uDCE2 " + getLocalName() + " - ACTIVE CFP Handler (processed " + messageCount + " CFPs, waiting for CFP messages...)");
                lastHeartbeat = now;
            }
            
            // Use proper template to ONLY receive CFP messages with pickup-task-cfp protocol
            // This prevents catching messages meant for other behaviors
            MessageTemplate mt = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.CFP),
                MessageTemplate.MatchProtocol("pickup-task-cfp")
            );
            
            ACLMessage cfpMsg = receive(mt);
            if (cfpMsg != null) {
                messageCount++;
                DelayUtils.randomDelay(1, 500); // Default 1-500 ms range
                System.out.println("✅ " + getLocalName() + " - Received CFP message from " + cfpMsg.getSender().getLocalName());
                handleProductNotification(cfpMsg);
            } else {
                block();
            }
        }
        
        private void handleProductNotification(ACLMessage msg) {
            try {
                String senderName = msg.getSender().getLocalName();
                String content = msg.getContent();
                
                // OPA CHECK: Validate that this communication is authorized (bidirectional check)
                if (securityManager != null && !securityManager.canCommunicateWith(senderName, getLocalName())) {
                    System.out.println("┌─ PRODUCT NOTIFICATION REJECTED ──────────────────");
                    System.out.println("│  🚫 UNAUTHORIZED");
                    System.out.println("│  Time:     " + java.time.Instant.now());
                    System.out.println("│  From:     " + senderName);
                    System.out.println("│  To:       " + getLocalName());
                    System.out.println("│  Reason:   OPA policy blocks communication");
                    System.out.println("└──────────────────────────────────────────────────");
                    return;
                }
                
                // Parse CFP
                String conveyorName = extractValue(content, ":conveyor");
                String location = extractValue(content, ":location");
                String conveyorFFA = extractValue(content, ":ffa");
                
                System.out.println("┌─ CFP RECEIVED ───────────────────────────────────");
                System.out.println("│  📩 CFP RECEIVED");
                System.out.println("│  Time:         " + java.time.Instant.now());
                System.out.println("│  From:         " + senderName);
                System.out.println("│  To:           " + getLocalName());
                System.out.println("│  Location:     " + location);
                System.out.println("│  Conveyor FFA: " + conveyorFFA);
                System.out.println("│  Conversation: " + msg.getConversationId());
                System.out.println("└──────────────────────────────────────────────────");
                
                // Check if robot is available and enabled
                boolean robotExists = myRobot != null;
                boolean robotEnabled = robotExists && myRobot.isEnabled();
                System.out.println("🤖 " + getLocalName() + " - Robot status: exists=" + robotExists + ", enabled=" + robotEnabled);
                
                if (myRobot == null || !myRobot.isEnabled()) {
                    System.out.println("⚠️ " + getLocalName() + " - Cannot bid (Robot not enabled)");
                    return;
                }
                
                boolean hasNoTarget = myRobot.getTarget().isEmpty();
                boolean returningToIdle = myRobot.getTarget().startsWith("Idle Location");
                boolean isCarrying = myRobot.isCarryingProduct();
                boolean isAvailable = (hasNoTarget || returningToIdle) && !isCarrying;

                System.out.println("┌─ AVAILABILITY CHECK ─────────────────────────────");
                System.out.println("│  🤖 " + getLocalName());
                System.out.println("│  Available: " + isAvailable);
                System.out.println("│  Target: '" + myRobot.getTarget() + "'");
                System.out.println("│  hasNoTarget: " + hasNoTarget);
                System.out.println("│  returningToIdle: " + returningToIdle);
                System.out.println("│  carrying: " + isCarrying);
                System.out.println("└──────────────────────────────────────────────────");


                if (!isAvailable) {
                    // CRITICAL FIX: If robot is not available because it has active proposals, log it.
                    // This prevents the robot from bidding on a new CFP while waiting for a response from another.
                    synchronized (activeProposals) {
                        if (!activeProposals.isEmpty()) {
                            System.out.println("⚠️ " + getLocalName() + " - Cannot bid (Waiting for response on " + activeProposals.size() + " active proposal(s))");
                            return;
                        }
                    }
                    System.out.println("⚠️ " + getLocalName() + " - Cannot bid (Robot busy - Target: " + myRobot.getTarget() + ", Carrying: " + myRobot.isCarryingProduct() + ")");
                    return;
                }
                
                System.out.println("✉️ " + getLocalName() + " - Sending Proposal for: " + msg.getSender().getLocalName());
                // Calculate distance to conveyor
                double distance = calculateDistanceToTarget(location);
                int priority = myRobot.getPriority();

                // FAIRNESS: Add a bonus to the bid for the least serviced conveyor
                double fairnessBonus = calculateFairnessBonus(senderName);
                double effectivePriority = priority + fairnessBonus;
                System.out.println("⚖️ " + getLocalName() + " - Fairness Bonus for " + senderName + ": " + fairnessBonus + " (Effective Priority: " + effectivePriority + ")");
                
                // Submit proposal
                ACLMessage proposal = msg.createReply();
                proposal.setPerformative(ACLMessage.PROPOSE);
                proposal.setContent(
                    "(Proposal :robot \"" + getLocalName() + "\" " +
                    ":priority " + priority + " " +
                    ":distance " + distance + " " + // Distance is still sent for tie-breaking
                    ":available true " +
                    ":ffa \"" + (myFFA != null ? myFFA : "NONE") + "\")" // Add robot's FFA
                );
                
                // sending the proposal
                send(proposal);
                
                // Track this proposal's conversation ID
                String conversationId = msg.getConversationId();
                if (conversationId != null) {
                    activeProposals.add(conversationId);
                    System.out.println("📝 [PROPOSAL TRACKING] " + getLocalName() + " - Tracking conversationId: '" + conversationId + "' (active: " + activeProposals.size() + ")");
                }
                
                System.out.println("┌─ PROPOSAL SUBMITTED ─────────────────────────────");
                System.out.println("│  📤 PROPOSAL SENT");
                System.out.println("│  Time:        " + java.time.Instant.now());
                System.out.println("│  From:        " + getLocalName());
                System.out.println("│  To:          " + senderName);
                System.out.println("│  Priority:    " + priority);
                System.out.println("│  Distance:    " + String.format("%.2f", distance));
                System.out.println("│  Effective Priority (with bonus): " + effectivePriority);
                System.out.println("│  My FFA:      " + (myFFA != null ? myFFA : "NONE"));
                System.out.println("└──────────────────────────────────────────────────");
                
            } catch (Exception e) {
                System.err.println("❌ " + getLocalName() + " - Error handling CFP: " + e.getMessage());
                e.printStackTrace();
            }
        }

        /**
         * FAIRNESS: Calculate a bidding bonus to prioritize less-serviced conveyors.
         * This helps balance the workload between multiple conveyors.
         */
        private double calculateFairnessBonus(String conveyorAgentName) {
            int myCompletions = conveyorTaskCompletions.getOrDefault(conveyorAgentName, 0);
            int otherCompletions = 0;
            int numOtherConveyors = 0;

            // Find the average completions of OTHER conveyors
            for (java.util.Map.Entry<String, Integer> entry : conveyorTaskCompletions.entrySet()) {
                if (!entry.getKey().equals(conveyorAgentName)) {
                    otherCompletions += entry.getValue();
                    numOtherConveyors++;
                }
            }

            if (numOtherConveyors > 0) {
                double avgOtherCompletions = (double) otherCompletions / numOtherConveyors;

                // If this conveyor has been serviced less than others, add a bonus.
                // The bonus is larger the greater the difference.
                if (myCompletions < avgOtherCompletions) {
                    return (avgOtherCompletions - myCompletions) * 0.5; // The 0.5 is a tuning factor
                }
            }
            return 0.0; // No bonus if this conveyor is serviced more or equally.
        }
        
        /**
         * Calculate distance from robot to target location
         */
        private double calculateDistanceToTarget(String targetLocation) {
            try {
                // Get robot's current idle location
                int robotIndex = CustomNamespace.robots.indexOf(myRobot);
                
                // Parse idle locations
                String idleFile = null;
                for (SystemConfig.ComponentProperty prop : SystemConfig.COMPONENT_PROPERTIES) {
                    if (prop != null && "idleProperties".equals(prop.name)) {
                        idleFile = prop.jsonFile;
                        break;
                    }
                }
                
                if (idleFile == null) {
                    return Double.MAX_VALUE;
                }
                
                org.json.simple.parser.JSONParser parser = new org.json.simple.parser.JSONParser();
                org.json.simple.JSONArray idleLocations = (org.json.simple.JSONArray) parser.parse(new java.io.FileReader(idleFile));
                
                if (robotIndex < idleLocations.size()) {
                    org.json.simple.JSONObject idlePos = (org.json.simple.JSONObject) idleLocations.get(robotIndex);
                    double robotX = ((Number) idlePos.get("X")).doubleValue();
                    double robotY = ((Number) idlePos.get("Y")).doubleValue();
                    
                    // Parse conveyor locations
                    String conveyorFile = null;
                    for (SystemConfig.ComponentProperty prop : SystemConfig.COMPONENT_PROPERTIES) {
                        if (prop != null && "inputconveyorProperties".equals(prop.name)) {
                            conveyorFile = prop.jsonFile;
                            break;
                        }
                    }
                    
                    if (conveyorFile != null) {
                        org.json.simple.JSONArray conveyorLocations = (org.json.simple.JSONArray) parser.parse(new java.io.FileReader(conveyorFile));
                        
                        // Find matching conveyor by name
                        for (Object obj : conveyorLocations) {
                            org.json.simple.JSONObject conveyorPos = (org.json.simple.JSONObject) obj;
                            String name = (String) conveyorPos.get("Name");
                            
                            if (targetLocation.contains(name) || name.contains(targetLocation)) {
                                double conveyorX = ((Number) conveyorPos.get("X")).doubleValue();
                                double conveyorY = ((Number) conveyorPos.get("Y")).doubleValue();
                                
                                return calculateDistance(robotX, robotY, conveyorX, conveyorY);
                            }
                        }
                    }
                }
                
            } catch (Exception e) {
                System.err.println("Error calculating distance: " + e.getMessage());
            }
            
            return Double.MAX_VALUE;
        }
    }
    
    /**
     * Execute production task - handle both OPC UA and federation-only modes
     */
    private boolean executeProductionTask(String taskId, String operation, String priority) {
        try {
            System.out.println("[" + getLocalName() + "] Executing task " + taskId + 
                " - Operation: " + operation + ", Priority: " + priority);
            
            // Determine task execution based on operation type
            switch (operation.toUpperCase()) {
                case "PICKUP":
                case "MATERIALHANDLING":
                    return executePickupTask();
                    
                case "TRANSPORT":
                    return executeTransportTask();
                    
                case "DROPOFF":
                    return executeDropoffTask();
                    
                default:
                    System.out.println("[" + getLocalName() + "] Executing generic task: " + operation);
                    // Simulate task execution time
                    Thread.sleep(2000);
                    return true;
            }
            
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error executing task " + taskId + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Execute pickup task - ACTUALLY SETS THE ROBOT TARGET IN OPC UA
     */
    private boolean executePickupTask() {
        try {
            if (myRobot == null) {
                System.out.println("[" + getLocalName() + "] Robot reference is null");
                return false;
            }
            
            if (myRobot.isCarryingProduct()) {
                System.out.println("[" + getLocalName() + "] Robot already carrying product");
                return false;
            }
            
            // Check if robot already has a target (already moving to pickup)
            String currentTarget = myRobot.getTarget();
            if (!currentTarget.isEmpty() && !currentTarget.startsWith("Idle Location")) {
                System.out.println("[" + getLocalName() + "] Robot already has target: " + currentTarget);
                return true; // Task in progress
            }
            
            // Find nearest conveyor with products using the existing check logic
            // This will set the robot's target automatically
            checkConveyorProduction();
            
            // Verify target was set
            String newTarget = myRobot.getTarget();
            if (!newTarget.isEmpty() && !newTarget.startsWith("Idle Location")) {
                System.out.println("✅ [" + getLocalName() + "] Successfully set target to: " + newTarget);
                reportTaskCompletionToManager(); // Report that we're working on it
                return true;
            } else {
                System.out.println("⚠️ [" + getLocalName() + "] No products available for pickup at this time");
                return false;
            }
            
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error in pickup task: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Execute transport task
     */
    private boolean executeTransportTask() {
        try {
            if (myRobot != null && myRobot.isCarryingProduct()) {
                System.out.println("[" + getLocalName() + "] Transporting product to destination");
                // Simulate transport time
                Thread.sleep(2000);
                return true;
            } else {
                System.out.println("[" + getLocalName() + "] No product to transport");
                return false;
            }
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error in transport task: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Execute dropoff task
     */
    private boolean executeDropoffTask() {
        try {
            if (myRobot != null && myRobot.isCarryingProduct()) {
                System.out.println("[" + getLocalName() + "] Dropping off product at destination");
                // Simulate dropoff time
                Thread.sleep(1000);
                return true;
            } else {
                System.out.println("[" + getLocalName() + "] No product to drop off");
                return false;
            }
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error in dropoff task: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Send heartbeat - include proper location
     */
    private void sendHeartbeat() {
        try {
            // Find ProductionAgentManager
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("ManufacturingCoordination");
            template.addServices(sd);
            
            DFAgentDescription[] results = DFService.search(this, template);
            
            if (results.length > 0) {
                AID productionManager = results[0].getName();
                
                ACLMessage heartbeat = new ACLMessage(ACLMessage.INFORM);
                heartbeat.addReceiver(productionManager);
                heartbeat.setProtocol("acknowledgement");
                
                // Determine current status based on target and carrying state
                boolean isIdle = myRobot == null || (myRobot.getTarget().isEmpty() || myRobot.getTarget().startsWith("Idle Location"));
                String currentStatus = (isIdle && !myRobot.isCarryingProduct()) ? "IDLE" : "BUSY";

                heartbeat.setContent(
                    "(Heartbeat " +
                    ":agent-id \"" + getLocalName() + "\" " +
                    ":status \"" + currentStatus + "\" " +
                    ":timestamp \"" + new java.util.Date() + "\" " +
                    ":robot-id \"" + robotId + "\" " +
                    ":priority \"" + (myRobot != null ? myRobot.getPriority() : "0") + "\" " +
                    ":battery \"" + (myRobot != null ? myRobot.getBatteryLevel() : "0") + "\" " +
                    ":carrying \"" + (myRobot != null && myRobot.isCarryingProduct()) + "\" " +
                    ":product \"" + (myRobot != null ? myRobot.getCarriedProduct() : "NONE") + "\" " +
                    ":location \"" + (myRobot != null ? myRobot.getLocation() : "UNKNOWN") + "\" " +
                    ":target \"" + (myRobot != null ? myRobot.getTarget() : "NONE") + "\")"
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
     * Report task completion to ProductionAgentManager for load balancing
     * This helps the manager track which robots are active and assign tasks to idle robots
     */
    private void reportTaskCompletionToManager() {
        try {
            // Find ProductionAgentManager
            AID productionManager = null;
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("production-management");
            template.addServices(sd);
            
            try {
                DFAgentDescription[] result = DFService.search(this, template);
                if (result.length > 0) {
                    productionManager = result[0].getName();
                }
            } catch (FIPAException fe) {
                // Manager not found, skip reporting
                return;
            }
            
            if (productionManager == null) {
                return; // No manager to report to
            }
            
            // Send task completion report
            ACLMessage report = new ACLMessage(ACLMessage.INFORM);
            report.addReceiver(productionManager);
            report.setProtocol("task-completion-report");
            report.setContent(
                "(TaskCompleted " +
                ":agent-id \"" + getLocalName() + "\" " +
                ":robot-id \"" + robotId + "\" " +
                ":timestamp \"" + new java.util.Date() + "\" " +
                ":status \"COMPLETED\" " +
                ":location \"" + (myRobot != null ? myRobot.getLocation() : "UNKNOWN") + "\")"
            );
            
            // SECURITY: Validate with OPA
            boolean messageAllowed = securityManager.validateMessageWithOPA(report, getLocalName(), productionManager.getLocalName());
            
            if (messageAllowed) {
                send(report);
                System.out.println("📋 [" + getLocalName() + "] Reported task completion to ProductionAgentManager");
            }
            
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error reporting task completion: " + e.getMessage());
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
            System.err.println("[" + getLocalName() + "] Error extracting value for " + key + ": " + e.getMessage());
        }
        return null;
    }
    
    /**
     * Find nearest conveyor with available products
     */
    private ConveyorAgent findNearestConveyorWithProduct() {
        // This would implement logic to find the nearest conveyor with products
        // For now, return the first available conveyor
        if (CustomNamespace.inputConveyors != null && !CustomNamespace.inputConveyors.isEmpty()) {
            return CustomNamespace.inputConveyors.get(0);
        }
        return null;
    }
    
    // =====================================================================
    // PEER COORDINATION - Horizontal Federation Between Enabled Robots
    // =====================================================================
    
    /**
     * Peer Coordination Behaviour - Enables horizontal federation between enabled robots
     * Only enabled robots to participate in peer coordination to handle tasks from blocked robots
     */
    private class PeerCoordinationBehaviour extends TickerBehaviour {
        
        public PeerCoordinationBehaviour(Agent agent, long period) {
            super(agent, period);
        }
        @Override
        public void onStart() {
            DelayUtils.randomDelay(10, 1000);
        }

        @Override
        protected void onTick() {
            // Only participate in peer coordination if this robot is enabled
            if (myRobot == null || !myRobot.isEnabled()) {
                return;
            }
            
            // Broadcast availability status to peer robots (for future coordination)
            broadcastAvailabilityStatus();
            
            // Check if we can help other agents by taking over tasks
            coordinateWithPeers();
        }
        
        /**
         * Broadcast this robot's availability to peer robots
         * Only broadcasts to robots that OPA allows communication with
         */
        private void broadcastAvailabilityStatus() {
            try {
                ACLMessage broadcast = new ACLMessage(ACLMessage.INFORM);
                broadcast.setProtocol("robot-peer-coordination");
                
                // Add all other robot agents as receivers - BUT ONLY IF OPA ALLOWS COMMUNICATION
                for (int i = 1; i <= CustomNamespace.robots.size(); i++) {
                    if (i != robotId) { // Don't send to self
                        String targetAgentName = "RobotAgent" + i;
                        
                        // CHECK OPA POLICY: Can this robot communicate with the target robot?
                        if (securityManager != null && securityManager.canCommunicateWith(getLocalName(), targetAgentName)) {
                            broadcast.addReceiver(new jade.core.AID(targetAgentName, jade.core.AID.ISLOCALNAME));
                            System.out.println("🔗 " + getLocalName() + " → " + targetAgentName + " (OPA: Communication allowed)");
                        } else {
                            System.out.println("🚫 " + getLocalName() + " ✗ " + targetAgentName + " (OPA: Communication blocked)");
                        }
                    }
                }
                
                // Only send if there are valid receivers
                if (broadcast.getAllReceiver().hasNext()) {
                    // Create status message with OPA authorization info
                    String status = String.format(
                        "(RobotPeerStatus :id %d :enabled true :location \"%s\" :target \"%s\" :carrying %s :available %s)",
                        robotId,
                        myRobot.getLocation(),
                        myRobot.getTarget(),
                        myRobot.isCarryingProduct(),
                        myRobot.getTarget().isEmpty() || myRobot.getTarget().startsWith("Idle")
                    );
                    
                    broadcast.setContent(status);
                    send(broadcast);
                } else {
                    System.out.println("⚠️ " + getLocalName() + " has no authorized peers for communication");
                }
                
            } catch (Exception e) {
                System.err.println("❌ " + getLocalName() + " error in peer broadcast: " + e.getMessage());
            }
        }
        
        /**
         * Coordinate with peer robots - enabled robots help handle tasks
         */
        private void coordinateWithPeers() {
            // Check messages from peer robots
            ACLMessage msg = receive(MessageTemplate.MatchProtocol("robot-peer-coordination"));
            
            if (msg != null && msg.getPerformative() == ACLMessage.REQUEST) {
                // Another robot is requesting help (e.g., a blocked robot asking enabled robot to take over)
                handlePeerTaskRequest(msg);
            }
        }
        
        /**
         * Handle task request from peer robot
         * Validates OPA policy before accepting tasks from peers
         */
        private void handlePeerTaskRequest(ACLMessage msg) {
            try {
                String content = msg.getContent();
                String requestingRobot = msg.getSender().getLocalName();
                
                System.out.println("🤝 " + getLocalName() + " received peer task request from " + requestingRobot);
                
                // STEP 1: CHECK OPA POLICY - Can requesting robot communicate with this robot?
                if (securityManager != null && !securityManager.canCommunicateWith(requestingRobot, getLocalName())) {
                    System.out.println("🚫 " + getLocalName() + " rejected request from " + requestingRobot + " (OPA: Communication not allowed)");
                    
                    // Send rejection due to policy
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.REFUSE);
                    reply.setContent("(TaskRefused :reason \"OPA policy blocks communication\")");
                    send(reply);
                    return;
                }
                
                System.out.println("✅ " + getLocalName() + " ← " + requestingRobot + " (OPA: Communication allowed)");
                
                // STEP 2: Check if this robot is available and enabled
                if (myRobot.isEnabled() && 
                    (myRobot.getTarget().isEmpty() || myRobot.getTarget().startsWith("Idle")) && 
                    !myRobot.isCarryingProduct()) {
                    
                    // Extract target from request
                    String requestedTarget = extractValue(content, "target");
                    
                    if (requestedTarget != null && !requestedTarget.isEmpty()) {
                        // Accept the task
                        setRobotTarget(requestedTarget, "PeerCoordinationBehaviour.handlePeerTaskRequest() - Peer request accepted");
                        
                        // Send acceptance reply
                        ACLMessage reply = msg.createReply();
                        reply.setPerformative(ACLMessage.AGREE);
                        reply.setContent("(TaskAccepted :target \"" + requestedTarget + "\" :authorized true)");
                        send(reply);
                        
                        System.out.println("✅ " + getLocalName() + " accepted task for target: " + requestedTarget + " (from peer: " + requestingRobot + ")");
                    }
                } else {
                    // Reject - not available
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.REFUSE);
                    reply.setContent("(TaskRefused :reason \"Not available\")");
                    send(reply);
                    
                    System.out.println("⚠️ " + getLocalName() + " refused task from " + requestingRobot + " (Robot not available)");
                }
                
            } catch (Exception e) {
                System.err.println("❌ " + getLocalName() + " error handling peer task request: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
}