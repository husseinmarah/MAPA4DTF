package milo.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import milo.opcua.server.CustomNamespace;
import milo.web.data.ConveyorDTO;
import milo.web.data.PropertiesDTO;
import milo.web.data.RobotDTO;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class RobotWebSocketHandler extends TextWebSocketHandler {

    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    private final OpcUaClient opcUaClient;
    private final CustomNamespace customNamespace;
    private final ObjectMapper objectMapper = new ObjectMapper();


    public RobotWebSocketHandler(OpcUaClient opcUaClient, CustomNamespace customNamespace) {
        this.opcUaClient = opcUaClient;
        this.customNamespace = customNamespace;

        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(this::sendDataToAllSessions, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        sendData(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session);
    }

    private void sendDataToAllSessions() {
        for (WebSocketSession session : sessions) {
            try {
                sendData(session);
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    private void sendData(WebSocketSession session) throws IOException, ExecutionException, InterruptedException {
        Map<String, Object> data = new HashMap<>();
        data.put("robots", getRobots());
        data.put("conveyors", getConveyors());
        data.put("properties", getProperties());

        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(data)));
    }

    private List<RobotDTO> getRobots() throws ExecutionException, InterruptedException {
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

    private List<ConveyorDTO> getConveyors() throws ExecutionException, InterruptedException {
        List<ConveyorDTO> conveyors = new ArrayList<>();
        int numConveyors = readIntValue("InputConveyorQuantity-unique-identifier");
        for (int i = 0; i < numConveyors; i++) {
            int conveyorNumber = i + 1;
            boolean produced = readBooleanValue(customNamespace.getInputConveyors().get(i).getProducedNode().getNodeId().getIdentifier().toString());
            conveyors.add(new ConveyorDTO(conveyorNumber, produced));
        }
        return conveyors;
    }

    private PropertiesDTO getProperties() throws ExecutionException, InterruptedException {
        String pathwayProperties = readStringValue("22-unique-identifier");
        String idleProperties = readStringValue("23-unique-identifier");
        String outputConveyorProperties = readStringValue("67-unique-identifier");
        return new PropertiesDTO(pathwayProperties, idleProperties, outputConveyorProperties);
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
}
