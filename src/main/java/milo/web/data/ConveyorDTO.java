package milo.web.data;

public class ConveyorDTO {
    private int id;
    private boolean produced;

    public ConveyorDTO(int id, boolean produced) {
        this.id = id;
        this.produced = produced;
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
}
