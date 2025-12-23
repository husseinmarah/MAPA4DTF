package milo.utils;

import jade.core.AID;
import jade.core.Agent;
import jade.lang.acl.ACLMessage;
import milo.agents.ConveyorAgent;
import milo.agents.RobotAgent;
import milo.opcua.server.CustomNamespace;
import milo.opcua.server.RobotTemplate;

/**
 * Agent Coordination Broker - Mediator for inter-agent communication
 * 
 * This class encapsulates all direct method calls between agents, providing a clean
 * interface for agent-to-agent coordination without tight coupling.
 * 
 * Benefits:
 * - Decouples agents from each other
 * - Centralizes communication logic
 * - Makes it easier to switch to message-based communication
 * - Improves testability and maintainability
 */
public class AgentCoordinationBroker {
    
    // Singleton instance
    private static AgentCoordinationBroker instance;
    
    private AgentCoordinationBroker() {
        // Private constructor for singleton
    }
    
    /**
     * Get singleton instance of the broker
     */
    public static synchronized AgentCoordinationBroker getInstance() {
        if (instance == null) {
            instance = new AgentCoordinationBroker();
        }
        return instance;
    }
    
    // =====================================================================
    // CONVEYOR STATE QUERIES
    // =====================================================================
    
    /**
     * Check if conveyor has produced product
     * @param conveyorAgentName Name of the conveyor agent (e.g., "ConveyorAgent1")
     * @return true if conveyor has produced product, false otherwise
     */
    public boolean isConveyorProduced(String conveyorAgentName) {
        ConveyorAgent conveyor = findConveyorByName(conveyorAgentName);
        if (conveyor != null) {
            return conveyor.getProduced();
        }
        return false;
    }
    
    /**
     * Check if conveyor is enabled by OPA policy
     * @param conveyorAgentName Name of the conveyor agent
     * @return true if conveyor is enabled, false otherwise
     */
    public boolean isConveyorEnabled(String conveyorAgentName) {
        ConveyorAgent conveyor = findConveyorByName(conveyorAgentName);
        if (conveyor != null) {
            return conveyor.isEnabled();
        }
        return false;
    }
    
    /**
     * Get conveyor information string
     * @param conveyorAgentName Name of the conveyor agent
     * @return Information string about the conveyor
     */
    public String getConveyorInfo(String conveyorAgentName) {
        ConveyorAgent conveyor = findConveyorByName(conveyorAgentName);
        if (conveyor != null) {
            return conveyor.getConveyorInfo();
        }
        return "Conveyor not found";
    }
    
    // =====================================================================
    // PICKUP QUEUE COORDINATION
    // =====================================================================
    
    /**
     * Register robot in conveyor's pickup queue
     * @param robotAgentName Name of the robot agent
     * @param conveyorAgentName Name of the conveyor agent
     * @param priority Robot's priority level
     * @return true if registration successful, false otherwise
     */
    public boolean registerRobotForPickup(String robotAgentName, String conveyorAgentName, int priority) {
        ConveyorAgent conveyor = findConveyorByName(conveyorAgentName);
        if (conveyor != null) {
            conveyor.registerForPickup(robotAgentName, priority);
            return true;
        }
        return false;
    }
    
    /**
     * Check if robot can pickup from conveyor
     * @param robotAgentName Name of the robot agent
     * @param conveyorAgentName Name of the conveyor agent
     * @return true if robot is authorized to pickup, false otherwise
     */
    public boolean canRobotPickup(String robotAgentName, String conveyorAgentName) {
        ConveyorAgent conveyor = findConveyorByName(conveyorAgentName);
        if (conveyor != null) {
            return conveyor.canPickup(robotAgentName);
        }
        return false;
    }
    
    /**
     * Notify conveyor that robot has completed pickup
     * @param robotAgentName Name of the robot agent
     * @param conveyorAgentName Name of the conveyor agent
     * @return true if notification successful, false otherwise
     */
    public boolean notifyPickupComplete(String robotAgentName, String conveyorAgentName) {
        ConveyorAgent conveyor = findConveyorByName(conveyorAgentName);
        if (conveyor != null) {
            conveyor.notifyPickupComplete(robotAgentName);
            return true;
        }
        return false;
    }
    
    // =====================================================================
    // ROBOT STATE QUERIES
    // =====================================================================
    
    /**
     * Check if robot is enabled by OPA policy
     * @param robotIndex Robot index (0-based)
     * @return true if robot is enabled, false otherwise
     */
    public boolean isRobotEnabled(int robotIndex) {
        if (robotIndex >= 0 && robotIndex < CustomNamespace.robots.size()) {
            RobotTemplate robot = CustomNamespace.robots.get(robotIndex);
            return robot.isEnabled();
        }
        return false;
    }
    
    /**
     * Check if robot is carrying a product
     * @param robotIndex Robot index (0-based)
     * @return true if robot is carrying product, false otherwise
     */
    public boolean isRobotCarryingProduct(int robotIndex) {
        if (robotIndex >= 0 && robotIndex < CustomNamespace.robots.size()) {
            RobotTemplate robot = CustomNamespace.robots.get(robotIndex);
            return robot.isCarryingProduct();
        }
        return false;
    }
    
    /**
     * Get robot's current location
     * @param robotIndex Robot index (0-based)
     * @return Robot's location string
     */
    public String getRobotLocation(int robotIndex) {
        if (robotIndex >= 0 && robotIndex < CustomNamespace.robots.size()) {
            RobotTemplate robot = CustomNamespace.robots.get(robotIndex);
            return robot.getLocation();
        }
        return "";
    }
    
    /**
     * Get robot's priority
     * @param robotIndex Robot index (0-based)
     * @return Robot's priority level
     */
    public int getRobotPriority(int robotIndex) {
        if (robotIndex >= 0 && robotIndex < CustomNamespace.robots.size()) {
            RobotTemplate robot = CustomNamespace.robots.get(robotIndex);
            return robot.getPriority();
        }
        return 0;
    }
    
    // =====================================================================
    // CONVEYOR STATE MODIFICATIONS
    // =====================================================================
    
    /**
     * Set conveyor's produced status
     * @param conveyorAgentName Name of the conveyor agent
     * @param produced New produced status
     * @return true if operation successful, false otherwise
     */
    public boolean setConveyorProduced(String conveyorAgentName, boolean produced) {
        ConveyorAgent conveyor = findConveyorByName(conveyorAgentName);
        if (conveyor != null) {
            conveyor.setProduced(produced);
            return true;
        }
        return false;
    }
    
    /**
     * Set conveyor's trust score
     * @param conveyorAgentName Name of the conveyor agent
     * @param score Trust score value
     * @return true if operation successful, false otherwise
     */
    public boolean setConveyorTrustScore(String conveyorAgentName, double score) {
        ConveyorAgent conveyor = findConveyorByName(conveyorAgentName);
        if (conveyor != null) {
            conveyor.setTrustScore(score);
            return true;
        }
        return false;
    }
    
    // =====================================================================
    // MESSAGE-BASED COMMUNICATION (Future-proof for distributed systems)
    // =====================================================================
    
    /**
     * Send pickup request message to conveyor (message-based alternative)
     * This method can be used instead of direct method calls for better decoupling
     * 
     * @param sender Sending agent
     * @param conveyorAgentName Name of the conveyor agent
     * @param robotAgentName Name of the robot agent
     */
    public void sendPickupRequest(Agent sender, String conveyorAgentName, String robotAgentName) {
        ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
        request.addReceiver(new AID(conveyorAgentName, AID.ISLOCALNAME));
        request.setProtocol("fipa-request");
        request.setOntology("conveyor-pickup");
        request.setContent("(action (pickup-complete :robot-name \"" + robotAgentName + "\"))");
        sender.send(request);
    }
    
    /**
     * Send robot exit notification to conveyor (message-based)
     * 
     * @param sender Sending agent
     * @param conveyorAgentName Name of the conveyor agent
     * @param robotAgentName Name of the robot agent
     */
    public void sendRobotExitNotification(Agent sender, String conveyorAgentName, String robotAgentName) {
        ACLMessage notification = new ACLMessage(ACLMessage.INFORM);
        notification.addReceiver(new AID(conveyorAgentName, AID.ISLOCALNAME));
        notification.setProtocol("robot-exit");
        notification.setContent("(robot-exit :robot-name \"" + robotAgentName + "\")");
        sender.send(notification);
    }
    
    // =====================================================================
    // HELPER METHODS
    // =====================================================================
    
    /**
     * Find conveyor agent by name
     * @param conveyorAgentName Name of the conveyor agent (e.g., "ConveyorAgent1")
     * @return ConveyorAgent instance or null if not found
     */
    private ConveyorAgent findConveyorByName(String conveyorAgentName) {
        // Extract conveyor number from name (e.g., "ConveyorAgent1" -> 1)
        try {
            String numberStr = conveyorAgentName.replace("ConveyorAgent", "");
            int conveyorNumber = Integer.parseInt(numberStr);
            int index = conveyorNumber - 1; // Convert to 0-based index
            
            if (index >= 0 && index < CustomNamespace.getInputConveyors().size()) {
                return CustomNamespace.getInputConveyors().get(index);
            }
        } catch (NumberFormatException e) {
            System.err.println("⚠️ AgentCoordinationBroker - Invalid conveyor name format: " + conveyorAgentName);
        }
        return null;
    }
    
    /**
     * Get conveyor agent by index
     * @param index Conveyor index (0-based)
     * @return ConveyorAgent instance or null if not found
     */
    public ConveyorAgent getConveyorByIndex(int index) {
        if (index >= 0 && index < CustomNamespace.getInputConveyors().size()) {
            return CustomNamespace.getInputConveyors().get(index);
        }
        return null;
    }
    
    /**
     * Get total number of conveyors
     * @return Number of conveyors
     */
    public int getConveyorCount() {
        return CustomNamespace.getInputConveyors().size();
    }
    
    /**
     * Get total number of robots
     * @return Number of robots
     */
    public int getRobotCount() {
        return CustomNamespace.robots.size();
    }
    
    /**
     * Convert conveyor name to index
     * @param conveyorAgentName Name of the conveyor agent
     * @return 0-based index or -1 if invalid
     */
    public int getConveyorIndex(String conveyorAgentName) {
        try {
            String numberStr = conveyorAgentName.replace("ConveyorAgent", "");
            int conveyorNumber = Integer.parseInt(numberStr);
            return conveyorNumber - 1; // Convert to 0-based index
        } catch (NumberFormatException e) {
            return -1;
        }
    }
    
    /**
     * Convert robot name to index
     * @param robotAgentName Name of the robot agent (e.g., "RobotAgent1")
     * @return 0-based index or -1 if invalid
     */
    public int getRobotIndex(String robotAgentName) {
        try {
            String numberStr = robotAgentName.replace("RobotAgent", "");
            int robotNumber = Integer.parseInt(numberStr);
            return robotNumber - 1; // Convert to 0-based index
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
