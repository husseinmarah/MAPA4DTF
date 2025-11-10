package milo.opcua.server;

import milo.agents.ConveyorAgent;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Template-configurable Robot Control UI
 * Clean, organized, and fully configurable from SystemConfig
 */
public class RobotControlUI extends JFrame {
    
    // =====================================================================
    // CONFIGURATION - Template configurable
    // =====================================================================
    private int numRobots = SystemConfig.NUM_ROBOTS;
    private int numInputConveyors = SystemConfig.NUM_INPUT_CONVEYORS;
    private OpcUaClient client;

    // =====================================================================
    // UI COMPONENTS - Organized by type  
    // =====================================================================
    // Robot components
    private JLabel[] locationLabels;
    private JLabel[] nextLocationLabels;
    private JCheckBox[] stopCheckboxes;
    private JLabel[] batteryLevelLabels;
    private JCheckBox[] carryingProductCheckboxes;
    private JLabel[] carriedProductLabels;
    private JTextField[] targetTextFields;
    private JTextField[] priorityTextFields;

    // Conveyor components
    private JCheckBox[] inputConveyorProducedCheckboxes;

    // System property components
    private JTextArea pathwayPropertiesTextArea;
    private JTextArea idlePropertiesTextArea;
    private JTextArea outputConveyorPropertiesTextArea;
    private JTextArea inputConveyorPropertiesTextArea;

    // Configuration components
    private JTextField robotQuantityField;
    private JTextField inputConveyorQuantityField;

    // Statistics components
    private JTextArea productDeliveryStatsTextArea;
    private java.util.Map<String, Integer> productDeliveryCount = new java.util.HashMap<>();
    private java.util.Map<String, Long> productPickupTimes = new java.util.HashMap<>();
    private java.util.Map<String, String> productDeliveryTimes = new java.util.HashMap<>();
    private java.util.Set<String> lastCarriedProducts = new java.util.HashSet<>();

    // Main panels
    private JPanel robotPanel;
    private JPanel conveyorPanel;
    private CustomNamespace namespace;

    private static final Logger logger = Logger.getLogger(RobotControlUI.class.getName());


    public RobotControlUI(OpcUaClient client, CustomNamespace namespace) {
        this.client = client;
        this.namespace = namespace;
        initComponents();
        
        // Initialize server values with SystemConfig values
        initializeServerValues();
        
        startRefreshThread();
    }

    private void initializeServerValues() {
        // Ensure server has the correct initial values from SystemConfig
        try {
            Thread.sleep(500); // Small delay to ensure server is ready
            writeValue("RobotQuantity-unique-identifier", numRobots);
            writeValue("InputConveyorQuantity-unique-identifier", numInputConveyors);
            System.out.println("Initialized server values: Robots=" + numRobots + ", InputConveyors=" + numInputConveyors);
        } catch (Exception e) {
            System.err.println("Error initializing server values: " + e.getMessage());
        }
    }

    private void initComponents() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setTitle("Robot Control UI");
        setPreferredSize(new Dimension(1000, 600));

        JPanel mainPanel = new JPanel(new BorderLayout());

        // Create a panel for Robot Quantity at the top
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JLabel robotQuantityLabel = new JLabel("Robot Quantity: ");
        robotQuantityField = new JTextField(String.valueOf(numRobots), 5);
        topPanel.add(robotQuantityLabel);
        topPanel.add(robotQuantityField);
        JButton updateQuantityButton = new JButton("Update");
        topPanel.add(updateQuantityButton);
        mainPanel.add(topPanel, BorderLayout.NORTH);

        // Add action listener to update button
        updateQuantityButton.addActionListener(e -> updateRobotQuantity());

        // Create tabbed pane for better organization
        JTabbedPane tabbedPane = new JTabbedPane();

        // Robots Panel
        robotPanel = new JPanel(new GridBagLayout());
        robotPanel.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), "Robots", TitledBorder.LEFT, TitledBorder.TOP));
        createRobotPanelComponents();

        // Scroll pane for robots panel
        JScrollPane robotScrollPane = new JScrollPane(robotPanel);
        tabbedPane.addTab("Robots", robotScrollPane);

        // Conveyors Panel with quantity controls
        JPanel conveyorMainPanel = new JPanel(new BorderLayout());
        conveyorMainPanel.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), "Conveyors", TitledBorder.LEFT, TitledBorder.TOP));

        // Input conveyor quantity controls
        JPanel conveyorControlPanel = new JPanel(new FlowLayout());
        conveyorControlPanel.add(new JLabel("Input Conveyor Quantity:"));
        inputConveyorQuantityField = new JTextField(String.valueOf(numInputConveyors), 5);
        conveyorControlPanel.add(inputConveyorQuantityField);
        JButton updateInputConveyorQuantityButton = new JButton("Update");
        updateInputConveyorQuantityButton.addActionListener(e -> updateInputConveyorQuantity());
        conveyorControlPanel.add(updateInputConveyorQuantityButton);

        conveyorMainPanel.add(conveyorControlPanel, BorderLayout.NORTH);

        // Conveyor controls panel
        conveyorPanel = new JPanel(new GridBagLayout());
        createConveyorPanelComponents(conveyorPanel);
        conveyorMainPanel.add(conveyorPanel, BorderLayout.CENTER);

        tabbedPane.addTab("Conveyors", conveyorMainPanel);

        // Properties Panel
        JPanel propertiesPanel = new JPanel(new GridLayout(3, 1));
        propertiesPanel.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), "Properties", TitledBorder.LEFT, TitledBorder.TOP));

        pathwayPropertiesTextArea = new JTextArea();
        idlePropertiesTextArea = new JTextArea();
        outputConveyorPropertiesTextArea = new JTextArea();

        pathwayPropertiesTextArea.setEditable(false);
        idlePropertiesTextArea.setEditable(false);
        outputConveyorPropertiesTextArea.setEditable(false);

        propertiesPanel.add(createTitledPanel("Pathway Properties", pathwayPropertiesTextArea));
        propertiesPanel.add(createTitledPanel("Idle Properties", idlePropertiesTextArea));
        propertiesPanel.add(createTitledPanel("Output Conveyor Properties", outputConveyorPropertiesTextArea));

        tabbedPane.addTab("Properties", new JScrollPane(propertiesPanel));

        // Add TabbedPane to Main Panel
        mainPanel.add(tabbedPane, BorderLayout.CENTER);

        add(mainPanel);
        pack();
        setLocationRelativeTo(null);
    }

    private void createRobotPanelComponents() {
        robotPanel.removeAll();

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Add padding around components
        gbc.insets = new Insets(5, 5, 5, 5); // Insets(top, left, bottom, right)

        // Header Labels
        String[] headers = {"Robot", "Location", "Next Location", "Stop", "Battery Level", "Carrying Product", "Carried Product", "Target", "Priority"};
        for (int i = 0; i < headers.length; i++) {
            gbc.gridx = i;
            gbc.gridy = 0;
            JLabel headerLabel = new JLabel(headers[i], SwingConstants.CENTER);
            headerLabel.setFont(new Font("Arial", Font.BOLD, 12));
            robotPanel.add(headerLabel, gbc);
        }

        // Initialize arrays based on numRobots
        locationLabels = new JLabel[numRobots];
        nextLocationLabels = new JLabel[numRobots];
        stopCheckboxes = new JCheckBox[numRobots];
        batteryLevelLabels = new JLabel[numRobots];
        carryingProductCheckboxes = new JCheckBox[numRobots];
        carriedProductLabels = new JLabel[numRobots];
        targetTextFields = new JTextField[numRobots];
        priorityTextFields = new JTextField[numRobots];

        // Robot details
        for (int i = 0; i < numRobots; i++) {
            int robotNumber = i + 1;

            gbc.gridy = i + 1;
            gbc.fill = GridBagConstraints.BOTH;

            // Robot Name
            gbc.gridx = 0;
            JLabel robotLabel = new JLabel("Robot " + robotNumber, SwingConstants.CENTER);
            robotPanel.add(robotLabel, gbc);

            // Location Label
            gbc.gridx = 1;
            locationLabels[i] = new JLabel("", SwingConstants.CENTER);
            robotPanel.add(locationLabels[i], gbc);

            // Next Location Label
            gbc.gridx = 2;
            nextLocationLabels[i] = new JLabel("", SwingConstants.CENTER);
            robotPanel.add(nextLocationLabels[i], gbc);

            // Stop Checkbox
            gbc.gridx = 3;
            stopCheckboxes[i] = new JCheckBox();
            robotPanel.add(stopCheckboxes[i], gbc);

            // Battery Level Label
            gbc.gridx = 4;
            batteryLevelLabels[i] = new JLabel("", SwingConstants.CENTER);
            robotPanel.add(batteryLevelLabels[i], gbc);

            // Carrying Product Checkbox
            gbc.gridx = 5;
            carryingProductCheckboxes[i] = new JCheckBox();
            carryingProductCheckboxes[i].setEnabled(false); // Read-only
            robotPanel.add(carryingProductCheckboxes[i], gbc);

            // Carried Product Label
            gbc.gridx = 6;
            carriedProductLabels[i] = new JLabel("", SwingConstants.CENTER);
            robotPanel.add(carriedProductLabels[i], gbc);

            // Target TextField
            gbc.gridx = 7;
            targetTextFields[i] = new JTextField(10);
            robotPanel.add(targetTextFields[i], gbc);

            // Priority TextField
            gbc.gridx = 8;
            gbc.fill = GridBagConstraints.NONE; // Don't stretch this field
            priorityTextFields[i] = new JTextField(3);
            priorityTextFields[i].setPreferredSize(new Dimension(50, priorityTextFields[i].getPreferredSize().height));
            priorityTextFields[i].setMaximumSize(new Dimension(50, priorityTextFields[i].getPreferredSize().height));
            robotPanel.add(priorityTextFields[i], gbc);
            gbc.fill = GridBagConstraints.BOTH; // Reset fill for next components

            // Action Listeners
            int index = i;
            stopCheckboxes[i].addActionListener(e -> setRobotStop(robotNumber, stopCheckboxes[index].isSelected()));

            targetTextFields[i].addActionListener(e -> setRobotTarget(robotNumber, targetTextFields[index].getText()));

            priorityTextFields[i].getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    updatePriority(robotNumber);
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    updatePriority(robotNumber);
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    updatePriority(robotNumber);
                }
            });
        }

        // Add Product Delivery Statistics section
        gbc.gridx = 0;
        gbc.gridy = numRobots + 2;
        gbc.gridwidth = 9; // Span across all columns
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;
        gbc.weighty = 0.3;

        JPanel statsPanel = new JPanel(new BorderLayout());
        statsPanel.setBorder(BorderFactory.createTitledBorder("Product Delivery Statistics"));

        productDeliveryStatsTextArea = new JTextArea(5, 50);
        productDeliveryStatsTextArea.setEditable(false);
        productDeliveryStatsTextArea.setText("No products delivered yet");

        JScrollPane statsScrollPane = new JScrollPane(productDeliveryStatsTextArea);
        statsPanel.add(statsScrollPane, BorderLayout.CENTER);

        robotPanel.add(statsPanel, gbc);

        robotPanel.revalidate();
        robotPanel.repaint();
    }

    private void createConveyorPanelComponents(JPanel conveyorPanel) {
        conveyorPanel.removeAll();

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        int row = 0;

        // Input Conveyors Section
        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        JLabel inputLabel = new JLabel("Input Conveyors", SwingConstants.CENTER);
        inputLabel.setFont(new Font("Arial", Font.BOLD, 14));
        conveyorPanel.add(inputLabel, gbc);

        gbc.gridwidth = 1;

        // Initialize input conveyor checkboxes array
        inputConveyorProducedCheckboxes = new JCheckBox[numInputConveyors];

        // Create input conveyor checkboxes dynamically
        for (int i = 0; i < numInputConveyors; i++) {
            int conveyorNumber = i + 1;
            gbc.gridx = i % 2;
            gbc.gridy = row + (i / 2);

            inputConveyorProducedCheckboxes[i] = new JCheckBox("Input Conveyor " + conveyorNumber + " Produced");

            // Add action listener for each input conveyor
            int index = i;
            inputConveyorProducedCheckboxes[i].addActionListener(e ->
                    setInputConveyorProduced(index + 1, inputConveyorProducedCheckboxes[index].isSelected()));

            conveyorPanel.add(inputConveyorProducedCheckboxes[i], gbc);
        }

        conveyorPanel.revalidate();
        conveyorPanel.repaint();
    }

    private JPanel createTitledPanel(String title, JTextArea textArea) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        JScrollPane scrollPane = new JScrollPane(textArea);
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    private void startRefreshThread() {
        Thread refreshThread = new Thread(() -> {
            // Wait for server to initialize properly before starting refresh
            try {
                Thread.sleep(2000); // 2 second delay for server initialization
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            
            while (true) {
                refreshData();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
        refreshThread.start();
    }

    private void refreshData() {
        try {
            for (int i = 0; i < numRobots; i++) {
                int robotNumber = i + 1;
                String locationNodeId = robotNumber + "-location";
                String nextLocationNodeId = robotNumber + "-nextLocation";
                String stopNodeId = robotNumber + "-stop";
                String batteryLevelNodeId = robotNumber + "-batteryLevel";
                String carryingProductNodeId = robotNumber + "-carryingProduct";
                String carriedProductNodeId = robotNumber + "-carriedProduct";
                String targetNodeId = robotNumber + "-target";
                String priorityNodeId = robotNumber + "-priority";

                String location = readStringValue(locationNodeId);
                String nextLocation = readStringValue(nextLocationNodeId);
                boolean stop = readBooleanValue(stopNodeId);
                int batteryLevel = readIntValue(batteryLevelNodeId);
                boolean carryingProduct = readBooleanValue(carryingProductNodeId);
                String carriedProduct = readStringValue(carriedProductNodeId);
                String target = readStringValue(targetNodeId);
                int priority = readIntValue(priorityNodeId);

                locationLabels[i].setText(location);
                nextLocationLabels[i].setText(nextLocation);
                stopCheckboxes[i].setSelected(stop);
                batteryLevelLabels[i].setText(batteryLevel + "%");
                carryingProductCheckboxes[i].setSelected(carryingProduct);
                carriedProductLabels[i].setText(carriedProduct);
                targetTextFields[i].setText(target);
                priorityTextFields[i].setText(String.valueOf(priority));

                // Update product delivery statistics
                updateProductDeliveryStats(robotNumber, carriedProduct);
            }

            // Update input conveyors dynamically
            for (int i = 0; i < numInputConveyors && i < inputConveyorProducedCheckboxes.length; i++) {
                if (i < namespace.getInputConveyors().size()) {
                    ConveyorAgent conveyor = namespace.getInputConveyors().get(i);
                    inputConveyorProducedCheckboxes[i].setSelected(readBooleanValue(conveyor.getProducedNode().getNodeId().getIdentifier().toString()));
                }
            }

            String pathwayProperties = readStringValue("22-unique-identifier");
            String idleProperties = readStringValue("23-unique-identifier");
            String outputConveyorProperties = readStringValue("67-unique-identifier");

            pathwayPropertiesTextArea.setText(pathwayProperties);
            idlePropertiesTextArea.setText(idleProperties);
            outputConveyorPropertiesTextArea.setText(outputConveyorProperties);

            // Update robot quantity field based on server value - only if server has valid value
            try {
                int serverRobotQuantity = readIntValue("RobotQuantity-unique-identifier");
                if (serverRobotQuantity > 0 && serverRobotQuantity != numRobots) {
                    numRobots = serverRobotQuantity;
                    robotQuantityField.setText(String.valueOf(numRobots));
                    createRobotPanelComponents();
                }
            } catch (Exception e) {
                System.err.println("Error reading robot quantity from server, keeping current value: " + numRobots);
            }

            // Update input conveyor quantity field based on server value - only if server has valid value
            try {
                int serverInputConveyorQuantity = readIntValue("InputConveyorQuantity-unique-identifier");
                if (serverInputConveyorQuantity > 0 && serverInputConveyorQuantity != numInputConveyors) {
                    numInputConveyors = serverInputConveyorQuantity;
                    inputConveyorQuantityField.setText(String.valueOf(numInputConveyors));
                    createConveyorPanelComponents(conveyorPanel);
                }
            } catch (Exception e) {
                System.err.println("Error reading input conveyor quantity from server, keeping current value: " + numInputConveyors);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String readStringValue(String nodeId) throws ExecutionException, InterruptedException {
        NodeId node = new NodeId(2, nodeId);
        DataValue value = client.readValue(0, TimestampsToReturn.Neither, node).get();
        Object val = value.getValue().getValue();
        return val != null ? (String) val : "";
    }

    private boolean readBooleanValue(String nodeId) throws ExecutionException, InterruptedException {
        NodeId node = new NodeId(2, nodeId);
        DataValue value = client.readValue(0, TimestampsToReturn.Neither, node).get();
        Object val = value.getValue().getValue();
        return val != null ? (boolean) val : false;
    }

    /**
     * Read integer value from OPC UA server
     * @param nodeId
     * @return
     * @throws ExecutionException
     * @throws InterruptedException
     */
    private int readIntValue(String nodeId) throws ExecutionException, InterruptedException {
        try {
            NodeId node = new NodeId(2, nodeId);
            DataValue value = client.readValue(0, TimestampsToReturn.Neither, node).get();
            Object val = value.getValue().getValue();
            int result = val != null ? ((Number) val).intValue() : 0;
            if (nodeId.contains("Quantity")) {
                // or with more context:
                logger.log(Level.FINE, "Read nodeId={0} value={1} type={2} thread={3}",
                    new Object[]{nodeId, result, val != null ? val.getClass().getSimpleName() : "null", Thread.currentThread().getName()});
            }
            return result;
        } catch (Exception e) {
            System.err.println("Error reading " + nodeId + ": " + e.getMessage());
            return 0;
        }
    }

    private void setRobotStop(int robotNumber, boolean stop) {
        String nodeId = robotNumber + "-stop";
        writeValue(nodeId, stop);
    }

    private void setRobotTarget(int robotNumber, String target) {
        String nodeId = robotNumber + "-target";
        writeValue(nodeId, target);
    }

    private void updatePriority(int robotNumber) {
        String priorityText = priorityTextFields[robotNumber - 1].getText();
        try {
            int priority = Integer.parseInt(priorityText);
            String nodeId = robotNumber + "-priority";
            writeValue(nodeId, priority);
        } catch (NumberFormatException e) {
            // Invalid input, ignore
        }
    }

    private void setInputConveyorProduced(int conveyorNumber, boolean produced) {
        try {
            if (conveyorNumber >= 1 && conveyorNumber <= numInputConveyors && conveyorNumber <= namespace.getInputConveyors().size()) {
                ConveyorAgent conveyor = namespace.getInputConveyors().get(conveyorNumber - 1);
                String nodeId = conveyor.getProducedNode().getNodeId().getIdentifier().toString();
                writeValue(nodeId, produced);
                System.out.println("Input Conveyor " + conveyorNumber + " produced set to: " + produced);
            }
        } catch (Exception e) {
            System.err.println("Error setting input conveyor " + conveyorNumber + " produced: " + e.getMessage());
        }
    }

    private void writeValue(String nodeId, Object value) {
        try {
            NodeId node = new NodeId(2, nodeId);
            DataValue dataValue = new DataValue(new Variant(value));
            client.writeValue(node, dataValue).get();
            if (nodeId.contains("Quantity")) {
                System.out.println("Wrote " + nodeId + " = " + value);
            }
        } catch (Exception e) {
            System.err.println("Error writing " + nodeId + ": " + e.getMessage());
        }
    }

    private void updateInputConveyorQuantity() {
        try {
            int newQuantity = Integer.parseInt(inputConveyorQuantityField.getText());
            if (newQuantity <= 0) {
                JOptionPane.showMessageDialog(this, "Input conveyor quantity must be a positive integer.");
                return;
            }
            if (newQuantity > 10) {
                JOptionPane.showMessageDialog(this, "Input conveyor quantity cannot exceed 10.");
                return;
            }
            numInputConveyors = newQuantity;
            writeValue("InputConveyorQuantity-unique-identifier", numInputConveyors);
            createConveyorPanelComponents(conveyorPanel);
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Please enter a valid number for input conveyor quantity.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateRobotQuantity() {
        try {
            int newQuantity = Integer.parseInt(robotQuantityField.getText());
            if (newQuantity <= 0) {
                JOptionPane.showMessageDialog(this, "Robot quantity must be a positive integer.");
                return;
            }
            numRobots = newQuantity;
            writeValue("RobotQuantity-unique-identifier", numRobots);
            createRobotPanelComponents();
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Invalid robot quantity. Please enter a valid integer.");
        }
    }

    private void updateProductDeliveryStats(int robotNumber, String carriedProduct) {
        String robotKey = "Robot" + robotNumber;

        // Get the last carried product for this robot
        String lastProductKey = null;
        String lastProduct = null;
        for (String key : lastCarriedProducts) {
            if (key.startsWith(robotKey + ":")) {
                lastProductKey = key;
                lastProduct = key.substring((robotKey + ":").length());
                break;
            }
        }

        // Current product (empty string if not carrying anything)
        String currentProduct = (carriedProduct != null && !carriedProduct.trim().isEmpty()) ? carriedProduct.trim() : "";

        // Check if robot picked up a new product
        if (lastProduct == null || lastProduct.isEmpty()) {
            if (!currentProduct.isEmpty()) {
                // Robot just picked up a product
                productPickupTimes.put(currentProduct, System.currentTimeMillis());
            }
        }

        // Check if a delivery occurred (was carrying something, now not carrying it)
        if (lastProduct != null && !lastProduct.isEmpty() && currentProduct.isEmpty()) {
            // A delivery just occurred
            Long pickupTime = productPickupTimes.get(lastProduct);
            if (pickupTime != null) {
                long deliveryTime = System.currentTimeMillis();
                long durationMs = deliveryTime - pickupTime;
                long minutes = durationMs / (60 * 1000);
                long seconds = (durationMs % (60 * 1000)) / 1000;

                String timeString = String.format("%dm %ds", minutes, seconds);
                productDeliveryTimes.put(lastProduct, timeString);
                productPickupTimes.remove(lastProduct); // Clean up
            }

            productDeliveryCount.put(lastProduct,
                    productDeliveryCount.getOrDefault(lastProduct, 0) + 1);
            updateProductDeliveryDisplay();
        }

        // Update the last carried product for this robot
        if (lastProductKey != null) {
            lastCarriedProducts.remove(lastProductKey);
        }

        if (!currentProduct.isEmpty()) {
            lastCarriedProducts.add(robotKey + ":" + currentProduct);
        }
    }

    private void updateProductDeliveryDisplay() {
        if (productDeliveryStatsTextArea == null) return;

        StringBuilder stats = new StringBuilder();
        stats.append("Product Delivery Statistics:\n\n");

        int totalDelivered = 0;
        for (java.util.Map.Entry<String, Integer> entry : productDeliveryCount.entrySet()) {
            String productName = entry.getKey();
            int deliveryCount = entry.getValue();
            String deliveryTime = productDeliveryTimes.get(productName);

            if (deliveryTime != null) {
                stats.append(String.format("%-20s: %d deliveries (delivered in %s)\n",
                        productName, deliveryCount, deliveryTime));
            } else {
                stats.append(String.format("%-20s: %d deliveries\n", productName, deliveryCount));
            }
            totalDelivered += deliveryCount;
        }

        stats.append("\n");
        stats.append(String.format("Total Products Delivered: %d", totalDelivered));

        if (totalDelivered == 0) {
            productDeliveryStatsTextArea.setText("No products delivered yet");
        } else {
            productDeliveryStatsTextArea.setText(stats.toString());
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                OpcUaClient client = OpcUaClient.create("opc.tcp://localhost:4840");
                client.connect().get();
                // Note: You'll need to pass the actual CustomNamespace instance here
                // This is just a placeholder - normally you'd get this from your server
                CustomNamespace namespace = null; // This should be the actual namespace instance
                RobotControlUI ui = new RobotControlUI(client, namespace);
                ui.setVisible(true);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
}