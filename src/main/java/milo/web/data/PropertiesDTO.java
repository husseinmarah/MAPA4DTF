package milo.web.data;

public class PropertiesDTO {
    private String pathwayProperties;
    private String idleProperties;
    private String outputConveyorProperties;

    public PropertiesDTO(String pathwayProperties, String idleProperties, String outputConveyorProperties) {
        this.pathwayProperties = pathwayProperties;
        this.idleProperties = idleProperties;
        this.outputConveyorProperties = outputConveyorProperties;
    }

    // Getters and setters
    public String getPathwayProperties() {
        return pathwayProperties;
    }

    public void setPathwayProperties(String pathwayProperties) {
        this.pathwayProperties = pathwayProperties;
    }

    public String getIdleProperties() {
        return idleProperties;
    }

    public void setIdleProperties(String idleProperties) {
        this.idleProperties = idleProperties;
    }

    public String getOutputConveyorProperties() {
        return outputConveyorProperties;
    }

    public void setOutputConveyorProperties(String outputConveyorProperties) {
        this.outputConveyorProperties = outputConveyorProperties;
    }
}
