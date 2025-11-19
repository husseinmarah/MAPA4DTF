package milo.opcua.server;

import com.google.common.collect.ImmutableSet;
import milo.agents.ConveyorAgent;
import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.api.DataItem;
import org.eclipse.milo.opcua.sdk.server.api.ManagedNamespace;
import org.eclipse.milo.opcua.sdk.server.api.MonitoredItem;
import org.eclipse.milo.opcua.sdk.server.model.nodes.objects.FolderTypeNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNodeContext;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;

/**
 * Manufacturing OPC-UA Namespace - Template Configurable
 * Clean, organized, and fully configurable from SystemConfig
 */
public class CustomNamespace extends ManagedNamespace {
    private static final Logger logger = LoggerFactory.getLogger(CustomNamespace.class);
    public static final String URI = SystemConfig.NAMESPACE_URI;

    // =====================================================================
    // COMPONENT COLLECTIONS - Organized by type
    // =====================================================================
    public static List<RobotTemplate> robots = new ArrayList<>();
    public static List<ConveyorAgent> inputConveyors = new ArrayList<>();
    
    // =====================================================================
    // SYSTEM PROPERTIES - Clear organization
    // =====================================================================
    private Map<String, UaVariableNode> systemProperties = new HashMap<>();
    private UaVariableNode robotQuantity;
    private UaVariableNode inputConveyorQuantity;

    // Legacy references for backward compatibility
    static UaVariableNode pathwayProperties;
    public static UaVariableNode idleProperties;
    static UaVariableNode inputconveyorProperties;
    public static UaVariableNode outputconveyorProperties;

    private final SubscriptionModel subscriptionModel;
    private UaFolderNode mainFolder;

    public CustomNamespace(OpcUaServer server) throws Exception {
        super(server, URI);
        this.subscriptionModel = new SubscriptionModel(server, this);
        
        // Register items immediately in constructor like the working version
        registerItems(getNodeContext());
        startBatteryLevelReduction();
        System.out.println("CustomNamespace constructor completed");
    }

    // =====================================================================
    // MAIN REGISTRATION - Clean flow
    // =====================================================================
    private void registerItems(final UaNodeContext context) throws Exception {
        System.out.println("Registering items");

        // 1. Setup main folder
        setupMainFolder(context);
        
        // 2. Create all component types immediately
        createAllComponents(context);
        
        // 3. Create all system properties immediately
        createAllSystemProperties(context);
        
        // 4. Load all JSON data immediately
        loadAllJsonData();
        
        // 5. Add all nodes to folder immediately like working version
        addAllNodesToFolderImmediate(context);
    }

    // =====================================================================
    // SETUP METHODS - Well organized
    // =====================================================================
    private void setupMainFolder(UaNodeContext context) {
        mainFolder = new UaFolderNode(
                context,
                newNodeId(1),
                newQualifiedName("FirstFolder"),
                LocalizedText.english("MainFolder"));
        context.getNodeManager().addNode(mainFolder);

        final Optional<UaNode> objectsFolder = context.getServer()
                .getAddressSpaceManager()
                .getManagedNode(Identifiers.ObjectsFolder);
        objectsFolder.ifPresent(node -> ((FolderTypeNode) node).addComponent(mainFolder));
    }

    private void createAllComponents(UaNodeContext context) {
        // Create robots immediately like old working version
        for (int i = 1; i <= SystemConfig.NUM_ROBOTS; i++) {
            RobotTemplate robot = createRobotImmediate(context, i);
            robots.add(robot);
        }

        // Create input conveyors immediately
        for (int i = 1; i <= SystemConfig.NUM_INPUT_CONVEYORS; i++) {
            ConveyorAgent conveyor = createInputConveyorImmediate(context, i);
            inputConveyors.add(conveyor);
        }
    }

    private void createAllSystemProperties(UaNodeContext context) {
        // Create properties using SystemConfig like we designed
        for (SystemConfig.ComponentProperty prop : SystemConfig.COMPONENT_PROPERTIES) {
            UaVariableNode node = createSimpleVariableNode(context, prop.nodeId, Identifiers.String, prop.name);
            systemProperties.put(prop.name, node);
        }
        
        // Set legacy references for backward compatibility
        pathwayProperties = systemProperties.get("pathwayProperties");
        idleProperties = systemProperties.get("idleProperties");
        outputconveyorProperties = systemProperties.get("outputconveyorProperties");
        inputconveyorProperties = systemProperties.get("inputconveyorProperties");

        // Create quantity nodes using SystemConfig values
        robotQuantity = createSimpleVariableNode(context, "RobotQuantity-unique-identifier", Identifiers.Int32, "RobotQuantity");
        inputConveyorQuantity = createSimpleVariableNode(context, "InputConveyorQuantity-unique-identifier", Identifiers.Int32, "InputConveyorQuantity");
        
        // Set initial values using SystemConfig
        robotQuantity.setValue(new DataValue(new Variant(SystemConfig.NUM_ROBOTS)));
        inputConveyorQuantity.setValue(new DataValue(new Variant(SystemConfig.NUM_INPUT_CONVEYORS)));
    }

    private void addAllNodesToFolderImmediate(UaNodeContext context) {
        // Add system properties to folder using SystemConfig
        systemProperties.values().forEach(node -> addNodeToFolderImmediate(context, node));
        
        // Add quantity nodes
        addNodeToFolderImmediate(context, robotQuantity);
        addNodeToFolderImmediate(context, inputConveyorQuantity);
    }

    // Simple node creation like old working version
    private UaVariableNode createSimpleVariableNode(UaNodeContext context, String nodeId, NodeId dataType, String displayName) {
        UaVariableNode node = new UaVariableNode.UaVariableNodeBuilder(context)
                .setNodeId(newNodeId(nodeId))
                .setAccessLevel(AccessLevel.READ_WRITE)
                .setUserAccessLevel(AccessLevel.READ_WRITE)
                .setDataType(dataType)
                .setBrowseName(newQualifiedName(displayName))
                .setDisplayName(LocalizedText.english(displayName))
                .setDescription(LocalizedText.english("Get " + displayName))
                .setTypeDefinition(Identifiers.BaseDataVariableType)
                .build();
        return node;
    }

    // Simple folder addition like old working version
    private void addNodeToFolderImmediate(UaNodeContext context, UaVariableNode node) {
        if (mainFolder != null) {
            mainFolder.addOrganizes(node);
            context.getNodeManager().addNode(node);
        }
    }

    private void loadAllJsonData() throws Exception {
        JSONParser parser = new JSONParser();
        
        // Load all component property data using SystemConfig
        for (SystemConfig.ComponentProperty prop : SystemConfig.COMPONENT_PROPERTIES) {
            JSONArray dataArray = (JSONArray) parser.parse(new FileReader(prop.jsonFile));
            systemProperties.get(prop.name).setValue(new DataValue(new Variant(dataArray.toString())));
        }
    }

    private void addAllNodesToFolder() {
        // Add system properties
        systemProperties.values().forEach(node -> addNodeToFolder(node));
        
        // Add quantity nodes
        addNodeToFolder(robotQuantity);
        addNodeToFolder(inputConveyorQuantity);
    }

    // =====================================================================
    // COMPONENT CREATION - Immediate like working version but using SystemConfig
    // =====================================================================
    private RobotTemplate createRobotImmediate(UaNodeContext context, int robotNumber) {
        List<UaVariableNode> robotNodes = new ArrayList<>();
        
        // Create all robot nodes using SystemConfig like we designed
        for (SystemConfig.RobotConfig attr : SystemConfig.ROBOTS) {
            String nodeId = attr.getNodeId(robotNumber);
            String displayName = "Robot " + robotNumber + " " + attr.displayName;
            
            UaVariableNode node = createSimpleVariableNode(context, nodeId, attr.dataType, displayName);
            
            // Handle special default values based on attribute name
            Object defaultValue = attr.defaultValue;
            if ("location".equals(attr.name)) {
                defaultValue = "empty" + robotNumber;
            } else if ("priority".equals(attr.name)) {
                defaultValue = SystemConfig.NUM_ROBOTS + 1 - robotNumber;
            }
            
            node.setValue(new DataValue(new Variant(defaultValue)));
            addNodeToFolderImmediate(context, node);
            robotNodes.add(node);
        }

        // Return robot with all nodes using SystemConfig order
        return new RobotTemplate(
            robotNodes.get(0), robotNodes.get(1), robotNodes.get(2), robotNodes.get(3), robotNodes.get(4),
            robotNodes.get(5), robotNodes.get(6), robotNodes.get(7), robotNodes.get(8)
        );
    }

    private ConveyorAgent createInputConveyorImmediate(UaNodeContext context, int conveyorNumber) {
        // Use SystemConfig for conveyor creation
        // Create "produced" node
        SystemConfig.ConveyorConfig producedAttr = SystemConfig.INPUT_CONVEYORS[0]; // "produced" attribute
        String producedNodeId = producedAttr.getNodeId("InputConveyor", conveyorNumber);
        String producedDisplayName = "Input Conveyor " + conveyorNumber + " " + producedAttr.displayName;
        
        UaVariableNode produced = createSimpleVariableNode(context, producedNodeId, producedAttr.dataType, producedDisplayName);
        produced.setValue(new DataValue(new Variant(producedAttr.defaultValue)));
        addNodeToFolderImmediate(context, produced);
        
        // Create "enabled" node
        SystemConfig.ConveyorConfig enabledAttr = SystemConfig.INPUT_CONVEYORS[1]; // "enabled" attribute
        String enabledNodeId = enabledAttr.getNodeId("InputConveyor", conveyorNumber);
        String enabledDisplayName = "Input Conveyor " + conveyorNumber + " " + enabledAttr.displayName;
        
        UaVariableNode enabled = createSimpleVariableNode(context, enabledNodeId, enabledAttr.dataType, enabledDisplayName);
        enabled.setValue(new DataValue(new Variant(enabledAttr.defaultValue)));
        addNodeToFolderImmediate(context, enabled);
        
        return new ConveyorAgent(produced, enabled, conveyorNumber);
    }

    // =====================================================================
    // NODE CREATION HELPERS - Reusable and clean
    // =====================================================================
    private UaVariableNode createRobotAttributeNode(SystemConfig.RobotConfig attr, int robotNumber) {
        String displayName = "Robot " + robotNumber + " " + attr.displayName;
        String nodeId = attr.getNodeId(robotNumber);
        
        // Handle special default values based on attribute name
        Object defaultValue = attr.defaultValue;
        if ("location".equals(attr.name)) {
            defaultValue = "empty" + robotNumber;
        } else if ("priority".equals(attr.name)) {
            defaultValue = SystemConfig.NUM_ROBOTS + 1 - robotNumber;
        }
        
        UaVariableNode node = createUaVariableNode(
            newNodeId(nodeId), 
            AccessLevel.READ_WRITE, AccessLevel.READ_WRITE, 
            attr.dataType, 
            displayName, 
            "Updating the " + displayName, 
            "Get the " + displayName
        );
        
        node.setValue(new DataValue(new Variant(defaultValue)));
        return node;
    }

    private UaVariableNode createInputConveyorAttributeNode(SystemConfig.ConveyorConfig attr, int conveyorNumber) {
        String displayName = "Input Conveyor " + conveyorNumber + " " + attr.displayName;
        String nodeId = attr.getNodeId("InputConveyor", conveyorNumber);
        
        UaVariableNode node = createUaVariableNode(
            newNodeId(nodeId),
            AccessLevel.READ_WRITE,
            AccessLevel.READ_WRITE,
            attr.dataType,
            displayName,
            "Updating " + displayName,
            "Get " + displayName
        );
        
        node.setValue(new DataValue(new Variant(attr.defaultValue)));
        return node;
    }
    
    private UaVariableNode createComponentPropertyNode(SystemConfig.ComponentProperty prop) {
        return createUaVariableNode(
            newNodeId(prop.nodeId), 
            AccessLevel.READ_WRITE, AccessLevel.READ_WRITE, 
            Identifiers.String, 
            prop.name, 
            "Updating the " + prop.name, 
            "Get the " + prop.name
        );
    }

    private UaVariableNode createQuantityNode(String name, int value) {
        UaVariableNode node = createUaVariableNode(
            newNodeId(name + "-unique-identifier"), 
            AccessLevel.READ_WRITE, AccessLevel.READ_WRITE, 
            Identifiers.Int32, 
            name, 
            "Updating " + name, 
            "Get " + name
        );
        
        node.setValue(new DataValue(new Variant(value)));
        return node;
    }

    // =====================================================================
    // UTILITY METHODS - Clean and simple
    // =====================================================================
    private void addNodeToFolder(UaVariableNode node) {
        if (mainFolder != null) {
            // Add to NodeManager first, then organize in folder
            getNodeContext().getNodeManager().addNode(node);
            mainFolder.addOrganizes(node);
        }
    }

    // =====================================================================
    // SYSTEM METHODS - Background tasks and lifecycle
    // =====================================================================
    private void startBatteryLevelReduction() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(() -> {
            for (RobotTemplate robot : robots) {
                int batteryLevel = robot.getBatteryLevel();
                if (batteryLevel > 0) {
                    robot.setBatteryLevel(batteryLevel - 1);
                }
            }
        }, 0, 5, TimeUnit.SECONDS);
    }

    // =====================================================================
    // OPC-UA INFRASTRUCTURE - Required overrides
    // =====================================================================

    @Override
    public void onDataItemsCreated(final List<DataItem> dataItems) {
        this.subscriptionModel.onDataItemsCreated(dataItems);
    }

    @Override
    public void onDataItemsModified(final List<DataItem> dataItems) {
        this.subscriptionModel.onDataItemsModified(dataItems);
    }

    @Override
    public void onDataItemsDeleted(final List<DataItem> dataItems) {
        this.subscriptionModel.onDataItemsDeleted(dataItems);
    }

    @Override
    public void onMonitoringModeChanged(final List<MonitoredItem> monitoredItems) {
        this.subscriptionModel.onMonitoringModeChanged(monitoredItems);
    }

    // =====================================================================
    // NODE CREATION UTILITY - Clean and reusable
    // =====================================================================
    public UaVariableNode createUaVariableNode(NodeId nodeId, ImmutableSet<AccessLevel> accessLevel, 
            ImmutableSet<AccessLevel> userAccessLevel, NodeId dataType, String qualifiedName, 
            String displayName, String description) {
        
        UaVariableNode node = new UaVariableNode.UaVariableNodeBuilder(getNodeContext())
                .setNodeId(nodeId)
                .setAccessLevel(accessLevel)
                .setUserAccessLevel(userAccessLevel)
                .setDataType(dataType)
                .setBrowseName(newQualifiedName(qualifiedName))
                .setDisplayName(LocalizedText.english(displayName))
                .setDescription(LocalizedText.english(description))
                .setTypeDefinition(Identifiers.BaseDataVariableType)
                .setValueRank(-1)
                .setArrayDimensions(null)
                .build();
        
        return node;
    }

    // =====================================================================
    // PUBLIC API - For external access
    // =====================================================================
    public static List<ConveyorAgent> getInputConveyors() {
        return inputConveyors;
    }
    
    public static List<RobotTemplate> getRobots() {
        return robots;
    }
}