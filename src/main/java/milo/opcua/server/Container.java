package milo.opcua.server;

import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.util.ExtendedProperties;
import jade.util.leap.Properties;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import milo.agents.*;

/**
 * Dynamic Container - Creates agents based on SystemConfig values
 * Number of agents determined by NUM_ROBOTS and NUM_INPUT_CONVEYORS
 */
public class Container {
    // In Container.java
    public static void startContainer() {
        try {
            Runtime runtime = Runtime.instance();
            Properties properties = new ExtendedProperties();
            properties.setProperty(Profile.GUI, "true");

            Profile profile = new ProfileImpl(properties);
            AgentContainer agentContainer = runtime.createMainContainer(profile);

            // Start Sniffer Agent for monitoring
            AgentController snifferAgent = agentContainer.createNewAgent("sniffer", "jade.tools.sniffer.Sniffer",
                    new Object[0]);
            snifferAgent.start();

            // Start Federation Address Manager first
            AgentController federationManager = agentContainer.createNewAgent("FederationManager",
                    FederationManagerAgent.class.getName(), new Object[] {});
            federationManager.start();
            System.out.println("\uD83D\uDE80 FederationManager Started");

            // Wait a bit for FAM to initialize
            Thread.sleep(3000);

            // Start TrustManager agent
            AgentController trustManager = agentContainer.createNewAgent("TrustManager",
                    TrustManagerAgent.class.getName(), new Object[] {});
            trustManager.start();
            System.out.println("\uD83D\uDE80 TrustManager Started");
            Thread.sleep(2000);

            // Start Production Agent Manager second
            AgentController productionManager = agentContainer.createNewAgent("ProductionManager",
                    ProductionManagerAgent.class.getName(), new Object[] {});
            productionManager.start();
            System.out.println("\uD83D\uDE80 ProductionManager Started");

            // Wait for ProductionManager to register with DF
            Thread.sleep(2000);

            // Create remote containers for different stakeholders
            Profile stakeholder1Profile = new ProfileImpl();
            stakeholder1Profile.setParameter(Profile.CONTAINER_NAME, "Stakeholder1_RobotContainer");
            AgentContainer stakeholder1Container = runtime.createAgentContainer(stakeholder1Profile);

            Profile stakeholder2Profile = new ProfileImpl();
            stakeholder2Profile.setParameter(Profile.CONTAINER_NAME, "Stakeholder2_RobotContainer");
            AgentContainer stakeholder2Container = runtime.createAgentContainer(stakeholder2Profile);

            Profile stakeholder3Profile = new ProfileImpl();
            stakeholder3Profile.setParameter(Profile.CONTAINER_NAME, "Stakeholder3_ConveyorContainer");
            AgentContainer stakeholder3Container = runtime.createAgentContainer(stakeholder3Profile);

            int half = SystemConfig.NUM_ROBOTS / 2;
            // First half in stakeholder1Container
            for (int i = 1; i <= half; i++) {
                String agentName = "RobotAgent" + i;
                Object[] args = { i };
                AgentController robotAgent = stakeholder1Container.createNewAgent(agentName,
                        RobotAgent.class.getName(), args);
                robotAgent.start();
                System.out.println("Started " + agentName + " in Stakeholder1_RobotContainer");
                Thread.sleep(1000);
            }
            // Second half in stakeholder2Container
            for (int i = half + 1; i <= SystemConfig.NUM_ROBOTS; i++) {
                String agentName = "RobotAgent" + i;
                Object[] args = { i };
                AgentController robotAgent = stakeholder2Container.createNewAgent(agentName,
                        RobotAgent.class.getName(), args);
                robotAgent.start();
                System.out.println("Started " + agentName + " in Stakeholder2_RobotContainer");
                Thread.sleep(1000);
            }

            // Create multiple ConveyorAgents in stakeholder3Container
            for (int i = 1; i <= SystemConfig.NUM_INPUT_CONVEYORS; i++) {
                String agentName = "ConveyorAgent" + i;
                Object[] args = { i };
                AgentController conveyorAgent = stakeholder3Container.createNewAgent(agentName,
                        ConveyorAgent.class.getName(), args);
                conveyorAgent.start();
                System.out.println("Started " + agentName + " in Stakeholder3_ConveyorContainer");
                Thread.sleep(1000);
            }

            System.out.println("=== Federation-enabled Multi-Agent System started ===");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
