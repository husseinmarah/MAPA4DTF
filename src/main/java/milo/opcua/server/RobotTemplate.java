package milo.opcua.server;

import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;

public class RobotTemplate {
    private UaVariableNode location;
    private UaVariableNode nextLocation;
    private UaVariableNode batteryLevel;
    private UaVariableNode target;
    private UaVariableNode stop;
    private UaVariableNode priority;
    private UaVariableNode carryingProduct;
    private UaVariableNode carriedProduct;

    public RobotTemplate(UaVariableNode location, UaVariableNode nextLocation, UaVariableNode batteryLevel,
                         UaVariableNode target, UaVariableNode stop, UaVariableNode priority,
                         UaVariableNode carryingProduct, UaVariableNode carriedProduct) {
        this.location = location;
        this.nextLocation = nextLocation;
        this.batteryLevel = batteryLevel;
        this.target = target;
        this.stop = stop;
        this.priority = priority;
        this.carryingProduct = carryingProduct;
        this.carriedProduct = carriedProduct;
    }

    public String getLocation() {
        return (String) location.getValue().getValue().getValue();
    }

    public String getNextLocation() {
        return (String) nextLocation.getValue().getValue().getValue();
    }

    public int getBatteryLevel() {
        return (int) batteryLevel.getValue().getValue().getValue();
    }

    public String getTarget() {
        return (String) target.getValue().getValue().getValue();
    }

    public boolean isStop() {
        return (boolean) stop.getValue().getValue().getValue();
    }

    public int getPriority() {
        return (int) priority.getValue().getValue().getValue();
    }

    public boolean isCarryingProduct() {
        return (boolean) carryingProduct.getValue().getValue().getValue();
    }

    public String getCarriedProduct() {
        return (String) carriedProduct.getValue().getValue().getValue();
    }

    public void setLocation(String value) {
        location.setValue(new DataValue(new Variant(value)));
    }

    public void setNextLocation(String value) {
        nextLocation.setValue(new DataValue(new Variant(value)));
    }

    public void setBatteryLevel(int value) {
        batteryLevel.setValue(new DataValue(new Variant(value)));
    }

    public void setTarget(String value) {
        target.setValue(new DataValue(new Variant(value)));
    }

    public void setStop(boolean value) {
        stop.setValue(new DataValue(new Variant(value)));
    }

    public void setPriority(int value) {
        priority.setValue(new DataValue(new Variant(value)));
    }

    public void setCarryingProduct(boolean value) {
        carryingProduct.setValue(new DataValue(new Variant(value)));
    }

    public void setCarriedProduct(String value) {
        carriedProduct.setValue(new DataValue(new Variant(value)));
    }
}