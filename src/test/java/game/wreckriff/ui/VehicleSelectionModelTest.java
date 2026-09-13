package game.wreckriff.ui;

import game.wreckriff.config.VehicleDefinition;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VehicleSelectionModelTest {
    @Test void browsingIsCyclicAndCommitReturnsOnlyTheFinalPreviewOnce() {
        var model=new VehicleSelectionModel("rivet");
        assertEquals(VehicleDefinition.RIVET,model.preview());
        assertEquals(VehicleDefinition.SPARK,model.browse(-1));
        assertEquals(VehicleDefinition.RIVET,model.browse(1));
        assertEquals(VehicleDefinition.GRINDER,model.browse(1));
        assertEquals(VehicleDefinition.GRINDER,model.commit().orElseThrow());
        assertTrue(model.commit().isEmpty());
        assertEquals(VehicleDefinition.GRINDER,model.browse(1));
    }
    @Test void cancellingDoesNotCommitOrChangeTheSavedSelectionOnReentry() {
        var model=new VehicleSelectionModel("spark");
        model.browse(1);model.cancel();
        assertTrue(model.commit().isEmpty());
        assertEquals(VehicleDefinition.SPARK,new VehicleSelectionModel("spark").preview());
    }
    @Test void everyAvailableChassisCanBeCommittedWithoutBrowsing() {
        for(var definition:VehicleDefinition.values())
            assertEquals(definition,new VehicleSelectionModel(definition.id()).commit().orElseThrow());
    }
}
