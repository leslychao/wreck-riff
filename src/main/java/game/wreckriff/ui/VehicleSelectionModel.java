package game.wreckriff.ui;

import game.wreckriff.config.VehicleDefinition;
import java.util.Optional;

/** One visit to the garage. Browsing never writes settings or starts a match. */
public final class VehicleSelectionModel {
    private final VehicleDefinition[] vehicles=VehicleDefinition.values();
    private int index;
    private boolean finished;

    public VehicleSelectionModel(String savedVehicleId) {index=VehicleDefinition.forId(savedVehicleId).ordinal();}
    public VehicleDefinition preview() {return vehicles[index];}
    public VehicleDefinition browse(int delta) {
        if(!finished)index=Math.floorMod(index+Integer.signum(delta),vehicles.length);
        return preview();
    }
    public Optional<VehicleDefinition> commit() {
        if(finished)return Optional.empty();
        finished=true;return Optional.of(preview());
    }
    public void cancel() {finished=true;}
}
