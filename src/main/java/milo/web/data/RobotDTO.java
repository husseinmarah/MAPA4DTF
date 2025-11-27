package milo.web.data;

public class RobotDTO {
    private int id;
    private String location;
    private String nextLocation;
    private boolean stop;
    private boolean enabled;
    private int batteryLevel;
    private boolean carryingProduct;
    private String carriedProduct;
    private String target;
    private int priority;

    public RobotDTO(int id, String location, String nextLocation, boolean stop, boolean enabled, int batteryLevel, boolean carryingProduct, String carriedProduct, String target, int priority) {
        this.id = id;
        this.location = location;
        this.nextLocation = nextLocation;
        this.stop = stop;
        this.enabled = enabled;
        this.batteryLevel = batteryLevel;
        this.carryingProduct = carryingProduct;
        this.carriedProduct = carriedProduct;
        this.target = target;
        this.priority = priority;
    }

    // Getters and setters
    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getNextLocation() {
        return nextLocation;
    }

    public void setNextLocation(String nextLocation) {
        this.nextLocation = nextLocation;
    }

    public boolean isStop() {
        return stop;
    }

    public void setStop(boolean stop) {
        this.stop = stop;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getBatteryLevel() {
        return batteryLevel;
    }

    public void setBatteryLevel(int batteryLevel) {
        this.batteryLevel = batteryLevel;
    }

    public boolean isCarryingProduct() {
        return carryingProduct;
    }

    public void setCarryingProduct(boolean carryingProduct) {
        this.carryingProduct = carryingProduct;
    }

    public String getCarriedProduct() {
        return carriedProduct;
    }

    public void setCarriedProduct(String carriedProduct) {
        this.carriedProduct = carriedProduct;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }
}
