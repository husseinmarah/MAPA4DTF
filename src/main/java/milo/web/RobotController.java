package milo.web;

import milo.opcua.server.CustomNamespace;
import milo.security.FederationSecurityManager;
import milo.security.KeycloakClient;
import milo.web.data.ConveyorDTO;
import milo.web.data.PropertiesDTO;
import milo.web.data.RobotDTO;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.springframework.web.bind.annotation.*;

import java.security.Key;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*")
public class RobotController {

    private final OpcUaClient opcUaClient;
    private final CustomNamespace customNamespace;

    public RobotController(OpcUaClient opcUaClient, CustomNamespace customNamespace) {
        this.opcUaClient = opcUaClient;
        this.customNamespace = customNamespace;
    }

    @GetMapping("/status")
    public String getStatus() {
        return "Server is running";
    }

    @GetMapping("/robots")
    public List<RobotDTO> getRobots() throws ExecutionException, InterruptedException {
        List<RobotDTO> robots = new ArrayList<>();
        int numRobots = readIntValue("RobotQuantity-unique-identifier");
        for (int i = 0; i < numRobots; i++) {
            int robotNumber = i + 1;
            String location = readStringValue("LocationRobot" + robotNumber);
            String nextLocation = readStringValue("NextLocationRobot" + robotNumber);
            boolean stop = readBooleanValue("StopRobot" + robotNumber);
            boolean enabled = readBooleanValue("EnabledRobot" + robotNumber);
            int batteryLevel = readIntValue("BatteryLevelRobot" + robotNumber);
            boolean carryingProduct = readBooleanValue("CarryingProductRobot" + robotNumber);
            String carriedProduct = readStringValue("CarriedProductRobot" + robotNumber);
            String target = readStringValue("TargetRobot" + robotNumber);
            int priority = readIntValue("PriorityRobot" + robotNumber);
            robots.add(new RobotDTO(robotNumber, location, nextLocation, stop, enabled, batteryLevel, carryingProduct,
                    carriedProduct, target, priority));
        }
        return robots;
    }

    @GetMapping("/conveyors")
    public List<ConveyorDTO> getConveyors() throws ExecutionException, InterruptedException {
        List<ConveyorDTO> conveyors = new ArrayList<>();
        int numConveyors = readIntValue("InputConveyorQuantity-unique-identifier");
        for (int i = 0; i < numConveyors; i++) {
            int conveyorNumber = i + 1;
            boolean produced = readBooleanValue(
                    CustomNamespace.inputConveyors.get(i).getProducedNode().getNodeId().getIdentifier().toString());
            boolean enabled = readBooleanValue(
                    CustomNamespace.inputConveyors.get(i).getEnabledNode().getNodeId().getIdentifier().toString());
            conveyors.add(new ConveyorDTO(conveyorNumber, produced, enabled));
        }
        return conveyors;
    }

    @GetMapping("/properties")
    public PropertiesDTO getProperties() throws ExecutionException, InterruptedException {
        String pathwayProperties = readStringValue("22-unique-identifier");
        String idleProperties = readStringValue("23-unique-identifier");
        String outputConveyorProperties = readStringValue("67-unique-identifier");
        return new PropertiesDTO(pathwayProperties, idleProperties, outputConveyorProperties);
    }

    @PostMapping("/robots/{id}/stop")
    public void setRobotStop(@PathVariable int id, @RequestBody Map<String, Boolean> payload) {
        writeValue("StopRobot" + id, payload.get("stop"));
    }

    @PostMapping("/robots/{id}/target")
    public void setRobotTarget(@PathVariable int id, @RequestBody Map<String, String> payload) {
        writeValue("TargetRobot" + id, payload.get("target"));
    }

    @PostMapping("/robots/{id}/priority")
    public void setRobotPriority(@PathVariable int id, @RequestBody Map<String, Integer> payload) {
        writeValue("PriorityRobot" + id, payload.get("priority"));
    }

    @PostMapping("/conveyors/{id}/produced")
    public void setConveyorProduced(@PathVariable int id, @RequestBody Map<String, Boolean> payload) {
        String nodeId = customNamespace.getInputConveyors().get(id - 1).getProducedNode().getNodeId().getIdentifier()
                .toString();
        writeValue(nodeId, payload.get("produced"));
    }

    @PostMapping("/robots/quantity")
    public void setRobotQuantity(@RequestBody Map<String, Integer> payload) {
        writeValue("RobotQuantity-unique-identifier", payload.get("quantity"));
    }

    @PostMapping("/conveyors/quantity")
    public void setConveyorQuantity(@RequestBody Map<String, Integer> payload) {
        writeValue("InputConveyorQuantity-unique-identifier", payload.get("quantity"));
    }

    @PostMapping("/trust-score")
    public void updateTrustScore(@RequestBody Map<String, Object> payload) {
        String agentName = (String) payload.get("agentName");
        Object scoreObj = payload.get("score");
        double score = scoreObj instanceof Integer ? ((Integer) scoreObj).doubleValue() : (Double) scoreObj;

        // 1. Update SharedTrustScoreService (for immediate UI feedback)
        SharedTrustScoreService.updateTrustScore(agentName, score);

//        // 2. Update trust score in Keycloak directly (as fallback)
//        KeycloakClient.getInstance().updateUserTrustScore(agentName, score);
        // 2. Send trust-update message to TrustManagerAgent
        // This will trigger automatic status change (active <-> blocked) based on thresholds
        // TrustManagerAgent will update both Keycloak trust score AND status attribute
        sendTrustUpdateToTrustManager(agentName, score);
    }

    /**
     * Send trust update to manage agent trust score and status
     * Direct update approach since we're in Spring context, not JADE agent context
     * Updates:
     * - Trust score in FederationSecurityManager
     * - Automatic status change based on thresholds
     * - OPA policy propagation
     */
    private void sendTrustUpdateToTrustManager(String agentName, double newScore) {
        try {
            System.out.println("📤 Processing trust update for: " + agentName + " = " + newScore);
            
            // Update via FederationSecurityManager (updates OPA and internal state)
            FederationSecurityManager securityManager = FederationSecurityManager.getInstance();
            securityManager.updateAgentTrustScore(agentName, newScore);
            
            // Trigger status check and update based on thresholds
            checkAndUpdateAgentStatus(agentName, newScore);
            
            // Force immediate enabled status update in OPC-UA
            updateOpcUaEnabledNode(agentName, newScore);
            
            System.out.println("✅ Trust update completed for " + agentName);
            
        } catch (Exception e) {
            System.err.println("❌ Error processing trust update: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Immediately update OPC-UA enabled node based on trust score
     */
    private void updateOpcUaEnabledNode(String agentName, double trustScore) {
        try {
            final double TRUST_THRESHOLD = 0.5;
            boolean shouldBeEnabled = trustScore >= TRUST_THRESHOLD;
            
            // Extract agent type and number from name (e.g., "RobotAgent3" -> type=Robot, num=3)
            if (agentName.startsWith("RobotAgent")) {
                int robotNum = Integer.parseInt(agentName.replace("RobotAgent", ""));
                String nodeId = "EnabledRobot" + robotNum;
                writeValue(nodeId, shouldBeEnabled);
                System.out.println("🔧 Updated OPC-UA node " + nodeId + ": " + shouldBeEnabled);
                
                // Also update the robot object directly if available
                if (robotNum > 0 && robotNum <= CustomNamespace.robots.size()) {
                    milo.opcua.server.RobotTemplate robot = CustomNamespace.robots.get(robotNum - 1);
                    robot.setEnabled(shouldBeEnabled);
                    System.out.println("🔧 Updated Robot object enabled state: " + shouldBeEnabled);
                }
            } 
            else if (agentName.startsWith("ConveyorAgent")) {
                int conveyorNum = Integer.parseInt(agentName.replace("ConveyorAgent", ""));
                if (conveyorNum > 0 && conveyorNum <= CustomNamespace.inputConveyors.size()) {
                    milo.agents.ConveyorAgent conveyor = CustomNamespace.inputConveyors.get(conveyorNum - 1);
                    conveyor.getEnabledNode().setValue(new org.eclipse.milo.opcua.stack.core.types.builtin.DataValue(
                        new org.eclipse.milo.opcua.stack.core.types.builtin.Variant(shouldBeEnabled)));
                    System.out.println("🔧 Updated Conveyor" + conveyorNum + " enabled state = " + shouldBeEnabled);
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Error updating OPC-UA enabled node: " + e.getMessage());
        }
    }

    /**
     * Check trust score against thresholds and update agent status
     */
    private void checkAndUpdateAgentStatus(String agentName, double newScore) {
        try {
            KeycloakClient keycloakClient = new KeycloakClient();
            
            final double TRUST_THRESHOLD = 0.5; // Block threshold
            final double UNBLOCK_THRESHOLD = 0.7; // Unblock threshold
            
            // Get current status from Keycloak
            KeycloakClient.AuthToken token = FederationSecurityManager.getInstance().getAgentToken(agentName);
            
            if (token != null && token.userAttributes != null) {
                String currentStatus = token.userAttributes.status;
                
                // Check if agent should be blocked
                if (newScore < TRUST_THRESHOLD && "active".equals(currentStatus)) {
                    boolean success = keycloakClient.updateUserStatus(agentName, "blocked");
                    if (success) {
                        System.out.println("┌─ AGENT STATUS CHANGED (via UI) ──────────────────");
                        System.out.println("│  🚫 AGENT: " + agentName);
                        System.out.println("│  STATUS: active -> blocked");
                        System.out.println(String.format("│  REASON: Trust score %.3f < threshold %.3f", newScore, TRUST_THRESHOLD));
                        System.out.println("└──────────────────────────────────────────────────");
                    }
                }
                // Check if blocked agent should be unblocked
                else if (newScore >= UNBLOCK_THRESHOLD && "blocked".equals(currentStatus)) {
                    boolean success = keycloakClient.updateUserStatus(agentName, "active");
                    if (success) {
                        System.out.println("┌─ AGENT STATUS CHANGED (via UI) ──────────────────");
                        System.out.println("│  ✅ AGENT: " + agentName);
                        System.out.println("│  STATUS: blocked -> active");
                        System.out.println(String.format("│  REASON: Trust score %.3f >= threshold %.3f", newScore, UNBLOCK_THRESHOLD));
                        System.out.println("└──────────────────────────────────────────────────");
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Error checking agent status: " + e.getMessage());
        }
    }

    private String readStringValue(String nodeId) throws ExecutionException, InterruptedException {
        NodeId node = new NodeId(2, nodeId);
        DataValue value = opcUaClient.readValue(0, TimestampsToReturn.Neither, node).get();
        Object val = value.getValue().getValue();
        return val != null ? (String) val : "";
    }

    private boolean readBooleanValue(String nodeId) throws ExecutionException, InterruptedException {
        NodeId node = new NodeId(2, nodeId);
        DataValue value = opcUaClient.readValue(0, TimestampsToReturn.Neither, node).get();
        Object val = value.getValue().getValue();
        return val != null ? (boolean) val : false;
    }

    private int readIntValue(String nodeId) throws ExecutionException, InterruptedException {
        try {
            NodeId node = new NodeId(2, nodeId);
            DataValue value = opcUaClient.readValue(0, TimestampsToReturn.Neither, node).get();
            Object val = value.getValue().getValue();
            return val != null ? ((Number) val).intValue() : 0;
        } catch (Exception e) {
            System.err.println("Error reading " + nodeId + ": " + e.getMessage());
            return 0;
        }
    }

    private void writeValue(String nodeId, Object value) {
        try {
            NodeId node = new NodeId(2, nodeId);
            DataValue dataValue = new DataValue(new Variant(value));
            opcUaClient.writeValue(node, dataValue).get();
        } catch (Exception e) {
            System.err.println("Error writing " + nodeId + ": " + e.getMessage());
        }
    }
}
