package milo.web;

import milo.opcua.server.CustomNamespace;
import milo.opcua.server.SystemConfig;
import milo.web.data.ConveyorDTO;
import milo.web.data.PropertiesDTO;
import milo.web.data.RobotDTO;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api/v1")
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
            robots.add(new RobotDTO(robotNumber, location, nextLocation, stop, enabled, batteryLevel, carryingProduct, carriedProduct, target, priority));
        }
        return robots;
    }

    @GetMapping("/conveyors")
    public List<ConveyorDTO> getConveyors() throws ExecutionException, InterruptedException {
        List<ConveyorDTO> conveyors = new ArrayList<>();
        int numConveyors = readIntValue("InputConveyorQuantity-unique-identifier");
        for (int i = 0; i < numConveyors; i++) {
            int conveyorNumber = i + 1;
            boolean produced = readBooleanValue(CustomNamespace.inputConveyors.get(i).getProducedNode().getNodeId().getIdentifier().toString());
            boolean enabled = readBooleanValue(CustomNamespace.inputConveyors.get(i).getEnabledNode().getNodeId().getIdentifier().toString());
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
        String nodeId = customNamespace.getInputConveyors().get(id - 1).getProducedNode().getNodeId().getIdentifier().toString();
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
