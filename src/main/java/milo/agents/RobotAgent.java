package milo.agents;

import jade.core.Agent;
import jade.core.behaviours.ParallelBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import milo.opcua.server.CustomNamespace;
import milo.federation.FederationHelper;
import milo.opcua.server.RobotTemplate;
import milo.opcua.server.SystemConfig;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import java.io.FileReader;
import java.util.ArrayList;
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
    private static final int AGENT_INTERVAL = 500; // Fixed interval
    private int robotId; // Specific robot this agent manages
    private RobotTemplate myRobot; // Reference to this agent's robot

    // =====================================================================
    // FEDERATION SUPPORT
    // =====================================================================
    private String myFFA; // This agent's Federation Fractal Address
    private boolean federationEnabled = false; // Whether federation is active

    // =====================================================================
    // UTILITY METHODS
    // =====================================================================
    private double calculateDistance(double x1, double y1, double x2, double y2) {
        return Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2));
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
            }
        }
        
        System.out.println("Agent " + getLocalName() + " started managing Robot " + robotId + " with interval: " + AGENT_INTERVAL + "ms");
        
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
        parallelBehaviour.addSubBehaviour(robotBehavior);
        parallelBehaviour.addSubBehaviour(new ProductionCommandHandler()); // Handle production commands
        parallelBehaviour.addSubBehaviour(new HeartbeatBehaviour(this, 10000)); // Send heartbeat every 10 seconds
        addBehaviour(parallelBehaviour);
        
        // Register with ProductionAgentManager
        registerWithProductionManager();
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
                    System.out.println("[" + getLocalName() + "] Federation workflow started: " + workflowId);
                }
                
                // Add federation-specific behaviors
                addBehaviour(new FederationMaintenanceBehaviour());
                addBehaviour(new FederationMessageHandler());
                
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
        
        System.out.println("[" + getLocalName() + "] Initializing horizontal federation with peer robots...");
        
        // Discover and connect to other robot agents at the same level
        // Using pattern matching to find peer robots
        String peerPattern = "EU/Plant7.Manufacturing.Production.OpcUA.MultiAgentSystem.Robot*::MaterialHandling.*";
        System.out.println("[" + getLocalName() + "] Peer discovery pattern: " + peerPattern);
        
        // This would be used for coordination between robots of the same type
        // Implementation would involve periodic peer discovery and coordination
    }
    
    /**
     * Initialize vertical federation with production management layer
     */
    private void initializeVerticalFederation() {
        if (!federationEnabled) return;
        
        System.out.println("[" + getLocalName() + "] Initializing vertical federation with production manager...");
        
        // Connect to higher-level production management
        String managerPattern = "EU/Plant7.Manufacturing.Production.**.ProductionManager::ManufacturingCoordination";
        System.out.println("[" + getLocalName() + "] Manager discovery pattern: " + managerPattern);
        
        // This would be used for receiving high-level production commands
        // and reporting status to management layer
    }

    // =====================================================================
    // MAIN ROBOT BEHAVIOR - Well organized
    // =====================================================================
    TickerBehaviour robotBehavior = new TickerBehaviour(this, AGENT_INTERVAL) {
        @Override
        public void onTick() {
            // 1. Display robot status
            displayRobotStatus();

            // 2. Handle conveyor production
            handleConveyorProduction();

            // 3. Handle robot pickup and delivery
            handleRobotOperations();
        }
    };

    // =====================================================================
    // ORGANIZED BEHAVIOR METHODS - Agent-specific operations
    // =====================================================================
    private void displayRobotStatus() {
        if (myRobot != null) {
            System.out.println("Robot " + robotId + " Location: " + myRobot.getLocation() + ", Next Location: " + myRobot.getNextLocation());
        }
    }



    private void handleConveyorProduction() {
        checkConveyorProduction();
    }

    private void handleRobotOperations() {
        if (myRobot != null) {
            // Handle product pickup for this specific robot
            checkProductPickup(myRobot);

            // Handle product dropoff for this specific robot
            checkProductDropoff(myRobot);

            // Check for new targets for this specific robot
            checkAndSetNewTarget(myRobot);
        }
    }

    // =====================================================================
    // ROBOT MOVEMENT
    // =====================================================================


    private void checkConveyorProduction() {
        // Check dynamic input conveyors
        for (int i = 0; i < CustomNamespace.getInputConveyors().size(); i++) {
            ConveyorAgent conveyor = CustomNamespace.getInputConveyors().get(i);
            if (conveyor.getProduced()) {
                String targetName = getConveyorTargetName(i + 1);
                setRobotTarget(targetName);
            }
        }
    }

    private String getConveyorTargetName(int conveyorNumber) {
        if (conveyorNumber == 1) {
            return "InputConveyor";
        } else {
            return "InputConveyor #" + conveyorNumber;
        }
    }

    private boolean isInputConveyorTarget(String target) {
        return target.equals("InputConveyor") || target.startsWith("InputConveyor #");
    }

    private void setRobotTarget(String target) {
        // First, check if any robot is already assigned to this target and still needs to pick up
        for (RobotTemplate robot : CustomNamespace.robots) {
            if (robot.getTarget().equals(target) && !robot.isCarryingProduct()) {
                return; // Only skip if robot is still going to this conveyor for pickup
            }
        }

        try {
            // Parse input conveyor locations and idle locations using SystemConfig.COMPONENT_PROPERTIES
            String inputConveyorFile = null;
            String idleFile = null;
            for (SystemConfig.ComponentProperty prop : SystemConfig.COMPONENT_PROPERTIES) {
                if (prop.name.equals("inputconveyorProperties")) inputConveyorFile = prop.jsonFile;
                if (prop.name.equals("idleProperties")) idleFile = prop.jsonFile;
            }
            JSONParser parser = new JSONParser();
            JSONArray inputConveyorLocations = (JSONArray) parser.parse(new FileReader(inputConveyorFile));
            JSONArray idleLocations = (JSONArray) parser.parse(new FileReader(idleFile));

            // Find the target conveyor coordinates
            JSONObject targetConveyor = null;
            int conveyorIndex = -1;

            // More generic approach to find conveyor index
            if (target.equals("InputConveyor")) {
                conveyorIndex = 0;
            } else if (target.startsWith("InputConveyor #")) {
                try {
                    String numberStr = target.substring("InputConveyor #".length());
                    int conveyorNumber = Integer.parseInt(numberStr);
                    conveyorIndex = conveyorNumber - 1; // Convert to 0-based index
                } catch (NumberFormatException e) {
                    System.err.println("Error parsing conveyor number from target: " + target);
                }
            }

            if (conveyorIndex >= 0 && conveyorIndex < inputConveyorLocations.size()) {
                targetConveyor = (JSONObject) inputConveyorLocations.get(conveyorIndex);
            }

            if (targetConveyor == null) return;

            // Find closest available robot
            RobotTemplate closestRobot = null;
            double minDistance = Double.MAX_VALUE;
            // int availableCount = 0;

            for (RobotTemplate robot : CustomNamespace.robots) {
                int robotIndex = CustomNamespace.robots.indexOf(robot);
                // Robot is available if: no target OR target is idle location, AND not carrying product
                boolean hasNoTarget = robot.getTarget().isEmpty();
                boolean returningToIdle = robot.getTarget().startsWith("Idle Location");
                boolean isAvailable = (hasNoTarget || returningToIdle) && !robot.isCarryingProduct();

                if (isAvailable) {
                    // availableCount++;
                    JSONObject robotLocation = (JSONObject) idleLocations.get(robotIndex);

                    double distance = calculateDistance(
                            ((Number) robotLocation.get("X")).doubleValue(),
                            ((Number) robotLocation.get("Y")).doubleValue(),
                            ((Number) targetConveyor.get("X")).doubleValue(),
                            ((Number) targetConveyor.get("Y")).doubleValue()
                    );

                    if (distance < minDistance) {
                        minDistance = distance;
                        closestRobot = robot;
                    }
                }
            }

            // Assign target to closest robot if one was found
            if (closestRobot != null) {
                // int selectedIndex = CustomNamespace.robots.indexOf(closestRobot);
                closestRobot.setTarget(target);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void checkProductPickup(RobotTemplate robot) {
        String location = robot.getLocation();
        // boolean isCarryingProduct = robot.isCarryingProduct();

        // Check for pickup at dynamic input conveyors
        for (int i = 0; i < CustomNamespace.getInputConveyors().size(); i++) {
            String conveyorTargetName = getConveyorTargetName(i + 1);
            if (location.equals(conveyorTargetName)) {
                ConveyorAgent conveyor = CustomNamespace.getInputConveyors().get(i);
                if (conveyor.getProduced()) {
                    System.out.println("Robot picking up product from " + conveyorTargetName);
                    robot.setCarryingProduct(true);
                    conveyor.setProduced(false);
                    break; // Exit loop once we find a match
                }
            }
        }
    }

    private void checkProductDropoff(RobotTemplate robot) {
        String location = robot.getLocation();
        boolean isCarryingProduct = robot.isCarryingProduct();
        String target = robot.getTarget();

        // Check if robot is carrying a product and is at a drop-off location
        if (isCarryingProduct && dropOffConveyorNamesContains(location) && dropOffConveyorNamesContains(target)) {
            // Robot has reached drop-off location, set CarryingProduct to false
            robot.setCarryingProduct(false);
            System.out.println("Robot dropped off product at " + location);
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
        boolean isCarryingProduct = robot.isCarryingProduct();
        String currentTarget = robot.getTarget();
        String currentLocation = robot.getLocation();
        int robotIndex = CustomNamespace.robots.indexOf(robot);

        // When robot is carrying a product and needs a drop-off location
        if (isCarryingProduct && (isInputConveyorTarget(currentTarget) || currentTarget.isEmpty())) {

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
                    robot.setTarget(dropOffTarget);
                    System.out.println("Set drop-off target " + dropOffTarget + " for robot");
                } else {
                    System.out.println("No drop-off conveyors available.");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
        // When robot has dropped off product and needs to return to idle location
        else if (!isCarryingProduct && dropOffConveyorNamesContains(currentLocation)) {
            // Set target to corresponding idle location
            String idleLocation = "Idle Location" + (robotIndex == 0 ? "" : " #" + (robotIndex + 1));
            robot.setTarget(idleLocation);
            System.out.println("Robot returning to idle location: " + idleLocation);
        }
        // When robot has reached its idle location
        else if (currentLocation.startsWith("Idle Location")) {
            if (currentTarget.startsWith("Idle Location")) {
                robot.setTarget("");
                System.out.println("Robot reached idle location. Ready for new tasks.");
                checkConveyorProduction();
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
            if (isFederationMessage(msg)) {
                handleFederationMessage(msg);
            } else {
                // Handle other message types or forward them
                handleNonFederationMessage(msg);
            }
        }
        
        private boolean isFederationMessage(ACLMessage msg) {
            String content = msg.getContent();
            return content != null && (
                content.contains("Federation") ||
                content.contains("FFA") ||
                content.contains("Coordination") ||
                msg.getProtocol() != null && msg.getProtocol().equals("federation-coordination")
            );
        }
        
        private void handleFederationMessage(ACLMessage msg) {
            String content = msg.getContent();
            String senderName = msg.getSender().getLocalName();
            
            System.out.println("[" + getLocalName() + "] Received federation message from " + senderName + ": " + content);
            
            if (content.contains("PeerCoordination")) {
                handlePeerCoordinationMessage(msg);
            } else if (content.contains("ProductionCommand")) {
                handleProductionCommandMessage(msg);
            } else if (content.contains("StatusRequest")) {
                handleStatusRequestMessage(msg);
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
                System.out.println("[" + getLocalName() + "] Registration sent to ProductionAgentManager");
            } else {
                System.out.println("[" + getLocalName() + "] ProductionAgentManager not found in Directory Facilitator");
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
            jade.lang.acl.MessageTemplate mt = jade.lang.acl.MessageTemplate.and(
                jade.lang.acl.MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                jade.lang.acl.MessageTemplate.MatchProtocol("production-command")
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
        protected void onTick() {
            sendHeartbeat();
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
     * Execute pickup task
     */
    private boolean executePickupTask() {
        try {
            if (myRobot != null && !myRobot.isCarryingProduct()) {
                // Find nearest conveyor with products
                ConveyorAgent nearestConveyor = findNearestConveyorWithProduct();
                if (nearestConveyor != null) {
                    // Move to conveyor and pickup
                    System.out.println("[" + getLocalName() + "] Moving to pickup location");
                    // Simulate movement and pickup
                    Thread.sleep(3000);
                    return true;
                } else {
                    System.out.println("[" + getLocalName() + "] No products available for pickup");
                    return false;
                }
            } else {
                System.out.println("[" + getLocalName() + "] Robot already carrying product or not available");
                return false;
            }
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error in pickup task: " + e.getMessage());
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
            jade.domain.FIPAAgentManagement.DFAgentDescription template = new jade.domain.FIPAAgentManagement.DFAgentDescription();
            jade.domain.FIPAAgentManagement.ServiceDescription sd = new jade.domain.FIPAAgentManagement.ServiceDescription();
            sd.setType("ManufacturingCoordination");
            template.addServices(sd);
            
            jade.domain.FIPAAgentManagement.DFAgentDescription[] results = jade.domain.DFService.search(this, template);
            
            if (results.length > 0) {
                jade.core.AID productionManager = results[0].getName();
                
                ACLMessage heartbeat = new ACLMessage(ACLMessage.INFORM);
                heartbeat.addReceiver(productionManager);
                heartbeat.setProtocol("acknowledgement");
                
                String currentStatus = myRobot != null && myRobot.isCarryingProduct() ? "BUSY" : "IDLE";
                heartbeat.setContent(
                    "(Heartbeat " +
                    ":agent-id \"" + getLocalName() + "\" " +
                    ":status \"" + currentStatus + "\" " +
                    ":timestamp \"" + new java.util.Date() + "\" " +
                    ":robot-id \"" + robotId + "\" " +
                    ":location \"" + (myRobot != null ? myRobot.getLocation() : "UNKNOWN") + "\")"
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
}