package milo.web.data;

public class ConveyorDTO {
    private int id;
    private boolean produced;
    private boolean enabled;

    public ConveyorDTO(int id, boolean produced, boolean enabled) {
        this.id = id;
        this.produced = produced;
        this.enabled = enabled;
    }

    // Getters and setters
    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public boolean isProduced() {
        return produced;
    }

    public void setProduced(boolean produced) {
        this.produced = produced;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
