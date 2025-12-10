package milo.opcua.server;

import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;

/**
 * Master Configuration - All system parameters in one place
 * Easy to modify, template-friendly
 * 
 * NUM_ROBOTS determines the number of RobotAgent instances created
 * NUM_INPUT_CONVEYORS determines the number of ConveyorAgent instances created
 * Each agent manages a specific robot/conveyor and executes the same logic
 */
public class SystemConfig {

    // =====================================================================
    // BASIC SYSTEM QUANTITIES - Determines number of agent instances
    // =====================================================================
    public static final int NUM_ROBOTS = 4;
    public static final int NUM_INPUT_CONVEYORS = 2;

    // =====================================================================
    // SERVER CONFIGURATION
    // =====================================================================
    public static final int SERVER_PORT = 4840;
    public static final String SERVER_NAME = "Manufacturing OPC UA Server";
    public static final String NAMESPACE_URI = "urn:manufacturing:opcua:namespace";

    // =====================================================================
    // ROBOT CONFIGURATION - Define what attributes each robot has
    // Format: new RobotConfig("attributeName", "Display Name", DataType,
    // defaultValue)
    // =====================================================================
    public static final RobotConfig[] ROBOTS = {
            new RobotConfig("location", "location", Identifiers.String, "initial"),
            new RobotConfig("nextLocation", "nextLocation", Identifiers.String, ""),
            new RobotConfig("batteryLevel", "batteryLevel", Identifiers.Int32, 100),
            new RobotConfig("target", "target", Identifiers.String, ""),
            new RobotConfig("stop", "stop", Identifiers.Boolean, false),
            new RobotConfig("priority", "priority", Identifiers.Int32, 1),
            new RobotConfig("carryingProduct", "carryingProduct", Identifiers.Boolean, false),
            new RobotConfig("carriedProduct", "carriedProduct", Identifiers.String, ""),
            new RobotConfig("enabled", "enabled", Identifiers.Boolean, false), // Start disabled, OPA will enable if
                                                                               // authorized
            new RobotConfig("trustScore", "trustScore", Identifiers.Double, 0.5),
    };

    // =====================================================================
    // CONVEYOR CONFIGURATION - Define input conveyor attributes
    // =====================================================================
    public static final ConveyorConfig[] INPUT_CONVEYORS = {
            new ConveyorConfig("produced", "produced", Identifiers.Boolean, false),
            new ConveyorConfig("enabled", "enabled", Identifiers.Boolean, false), // Start disabled, OPA will enable if
                                                                                  // authorized
            new ConveyorConfig("simulationName", "simulationName", Identifiers.String, ""),
            new ConveyorConfig("simulationFolder", "simulationFolder", Identifiers.String, ""),
            new ConveyorConfig("trustScore", "trustScore", Identifiers.Double, 0.5)
    };

    // =====================================================================
    // COMPONENT PROPERTIES - JSON files and component-wide properties
    // =====================================================================
    public static final ComponentProperty[] COMPONENT_PROPERTIES = {
            new ComponentProperty("pathwayProperties", "pathwayProperties-unique-identifier",
                    "configs/pathwayProperties.json"),
            new ComponentProperty("idleProperties", "idleProperties-unique-identifier", "configs/idleProperties.json"),
            new ComponentProperty("outputconveyorProperties", "outputconveyorProperties-unique-identifier",
                    "configs/outputconveyorProperties.json"),
            new ComponentProperty("inputconveyorProperties", "inputconveyorProperties-unique-identifier",
                    "configs/inputconveyorProperties.json")
    };

    // =====================================================================
    // CONFIGURATION CLASSES - Don't modify these
    // =====================================================================
    public static class RobotConfig {
        public final String name;
        public final String displayName;
        public final NodeId dataType;
        public final Object defaultValue;

        public RobotConfig(String name, String displayName, NodeId dataType, Object defaultValue) {
            this.name = name;
            this.displayName = displayName;
            this.dataType = dataType;
            this.defaultValue = defaultValue;
        }

        public String getNodeId(int robotNumber) {
            // Format: AttributeNameRobot# (e.g., EnabledRobot1, TargetRobot2)
            return name.substring(0, 1).toUpperCase() + name.substring(1) + "Robot" + robotNumber;
        }
    }

    public static class ConveyorConfig {
        public final String name;
        public final String displayName;
        public final NodeId dataType;
        public final Object defaultValue;

        public ConveyorConfig(String name, String displayName, NodeId dataType, Object defaultValue) {
            this.name = name;
            this.displayName = displayName;
            this.dataType = dataType;
            this.defaultValue = defaultValue;
        }

        public String getNodeId(String conveyorType, int conveyorNumber) {
            // Format: AttributeNameConveyorType# (e.g., EnabledInputConveyor1,
            // ProducedInputConveyor2)
            return name.substring(0, 1).toUpperCase() + name.substring(1) + conveyorType + conveyorNumber;
        }
    }

    public static class ComponentProperty {
        public final String name;
        public final String nodeId;
        public final String jsonFile;

        public ComponentProperty(String name, String nodeId, String jsonFile) {
            this.name = name;
            this.nodeId = nodeId;
            this.jsonFile = jsonFile;
        }
    }
}
